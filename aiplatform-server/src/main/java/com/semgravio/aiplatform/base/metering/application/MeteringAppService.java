package com.semgravio.aiplatform.base.metering.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import lombok.extern.slf4j.Slf4j;

import com.semgravio.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.semgravio.aiplatform.base.metering.domain.aggregate.UsageEventEntry;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.error.MeteringMessage;
import com.semgravio.aiplatform.base.metering.domain.model.PricedUsageLine;
import com.semgravio.aiplatform.base.metering.domain.model.UsageEvent;
import com.semgravio.aiplatform.base.metering.domain.model.UsageSummary;
import com.semgravio.aiplatform.base.metering.domain.repository.PriceEntryRepository;
import com.semgravio.aiplatform.base.metering.domain.repository.UsageEventAggregations;
import com.semgravio.aiplatform.base.metering.domain.repository.UsageEventRepository;

/**
 * 计量用例（票 #16 + #29）：用量事件幂等采集 + 按 subject 聚合查询（总量/分模型/
 * 分维度 + 平台成本换算与未配价标注，A6 §2 查询侧现算）。#531 起扩计费读面：
 * 按 run 寻址的已配价线集（段结算素材）与计件档单价报价（固定单价项直记素材）
 * ——仍只出用量×单价素材，零商业概念（金额换算归调用方）。
 *
 * <p>幂等 = first-write-wins：eventId 为主键，重复上报（含并发撞主键）静默吸收、
 * 不抛错——调用方按「上报可安全重试」语义使用（A1 §2.1）。采集与聚合只记 token
 * 不记钱；金额换算（平台成本）不落库，每次查询按事件时点生效单价现算。</p>
 */
@Service
@Slf4j
public class MeteringAppService {

    private final UsageEventRepository usageEventRepository;
    private final UsageEventAggregations usageEventAggregations;
    private final PriceEntryRepository priceEntryRepository;

    public MeteringAppService(UsageEventRepository usageEventRepository,
                              UsageEventAggregations usageEventAggregations,
                              PriceEntryRepository priceEntryRepository) {
        this.usageEventRepository = usageEventRepository;
        this.usageEventAggregations = usageEventAggregations;
        this.priceEntryRepository = priceEntryRepository;
    }

    /**
     * 上报一条用量事件（append-only）。同 eventId 已存在（先到或并发先落）则
     * 幂等跳过；撞主键但行仍不可见时以完整性异常上抛。
     *
     * <p>刻意不加 {@code @Transactional}：单行插入由仓储自带事务保证；并发撞键的
     * 吸收（catch 后再查 existsById）要求 save 的事务已独立结束，不处在外层事务中。</p>
     */
    public void report(UsageEvent event) {
        UsageEventEntry entry = UsageEventEntry.of(event);
        if (usageEventRepository.existsById(entry.getEventId())) {
            log.debug("用量事件 {} 重复上报，幂等跳过", entry.getEventId());
            return;
        }
        try {
            usageEventRepository.save(entry);
        } catch (DataIntegrityViolationException e) {
            // 并发同 eventId 撞主键：后落者吸收（first-write-wins）
            if (usageEventRepository.existsById(entry.getEventId())) {
                log.info("用量事件 {} 并发重复上报，幂等吸收", entry.getEventId());
                return;
            }
            throw e;
        }
    }

    /**
     * 按 subject 聚合（总量 + 平台成本 + 未配价 + 分模型 + 分维度），时间窗半开区间，
     * null 侧不限。只读事务快照：五条聚合 SQL 落在同一一致性视图，并发上报不破坏
     * 总量 = Σ分模型 = Σ分维度、cost = 窗口内已配价分量和 的自洽。
     */
    @Transactional(readOnly = true)
    public UsageSummary bySubject(String subject, Instant from, Instant to) {
        if (subject == null || subject.isBlank()) {
            throw new ApplicationException(MeteringMessage.USAGE_SUBJECT_REQUIRED);
        }
        return usageEventAggregations.aggregateBySubject(subject, from, to);
    }

    /**
     * 按 run 寻址的已配价线集（#531 计费段结算读面——「段用量×时点生效单价」的
     * 单源换算）：subject 名下、run_id ∈ runIds 的事件按 (provider, model, 档位,
     * 单价, 币种) 归并（线键保持账号粒度——跨账号同模型各线各的量；单价匹配挂
     * 事件的 vendor 腿，ADR-0053 ②——同 vendor 多账号共用供应商级单价行）。
     * 只读事务；只含已配价分量（未配价分量不进用户账、走
     * unpriced 观测面）。runIds 空返回空清单（调用方跳过结算）；{@code from} 非
     * null 时只计其后事件（计费边界＝翻转时点——翻转前事件平台担）。
     */
    @Transactional(readOnly = true)
    public List<PricedUsageLine> pricedUsageLinesByRuns(String subject, Collection<String> runIds,
            Instant from) {
        if (subject == null || subject.isBlank()) {
            throw new ApplicationException(MeteringMessage.USAGE_SUBJECT_REQUIRED);
        }
        return usageEventAggregations.aggregatePricedLinesByRuns(subject, runIds, from);
    }

    /**
     * 计件档（按张/按件）单价报价（#531 固定单价项直记的计价面）：单价行在
     * {@code at} 时点的生效现值（band NULL 行——计件不带时段维）。无生效行返回
     * empty（调用方跳过直记留观测）；token 档入参拒绝（METER_018——报价面只
     * 服务固定单价项族）。
     */
    @Transactional(readOnly = true)
    public Optional<PricedUsageLine> piecePriceOf(String provider, String model,
            TokenKind kind, Instant at) {
        if (!kind.isCountBased()) {
            throw new ApplicationException(MeteringMessage.PIECE_PRICE_KIND_INVALID);
        }
        if (at == null) {
            at = Instant.now();
        }
        return priceEntryRepository.findEffectivePieceRow(provider, model, kind, at)
                .stream().findFirst()
                .map(row -> new PricedUsageLine(provider, model, kind, 1,
                        row.getUnitPrice(), row.getCurrency()));
    }
}
