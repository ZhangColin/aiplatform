package com.aieducenter.aiplatform.base.metering.application;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.data.jpa.specification.ConditionSpecifications;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.metering.application.dto.command.RepricePriceEntryCommand;
import com.aieducenter.aiplatform.base.metering.application.dto.query.BackofficePriceEntryQuery;
import com.aieducenter.aiplatform.base.metering.application.dto.response.UnitPriceEntryRepriceResponse;
import com.aieducenter.aiplatform.base.metering.application.dto.response.UnitPriceEntryResponse;
import com.aieducenter.aiplatform.base.metering.domain.aggregate.PriceEntry;
import com.aieducenter.aiplatform.base.metering.domain.error.MeteringMessage;
import com.aieducenter.aiplatform.base.metering.domain.model.Operator;
import com.aieducenter.aiplatform.base.metering.domain.repository.PriceEntryRepository;

/**
 * 后台单价表管理写口（#160 成本运营）：行清单读（含历史行）＋原子改价（单调用
 * 关当前行＋开新行，可预发布未来起点）＋停用（即时关行不接新行，此后用量进
 * unpriced）。
 *
 * <p><b>重叠校验</b>（已知维护事故口封口）：表上唯一约束只防同起点、不防跨区间
 * 重叠——换算 SQL 按 ts 区间匹配，重叠行会重复计费。本服务在同键全行上服务端
 * 校验：新开区间 {@code [F, ∞)} 须与除被关行外的既有区间皆不重叠，且起点不撞
 * 任何既有行（同起点被关行自身也拦——关行不改写 {@code effective_from}，直插
 * 会撞唯一约束出 500）。校验在事务内读后判，并发双写的残余竞态由唯一约束兜底
 * 同起点一档（跨区间并发重叠 v1 不设防：单管理员操作面，非开放写口）。</p>
 */
@Service
public class BackofficePriceEntryAppService {

    /** 页大小上界（防一次性拉穿；单价表全量行数量级很小）。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final PriceEntryRepository priceEntryRepository;

    public BackofficePriceEntryAppService(PriceEntryRepository priceEntryRepository) {
        this.priceEntryRepository = priceEntryRepository;
    }

    /**
     * 后台单价行清单（含现行与历史行）：provider/model 精确过滤（可缺省＝全量），
     * 排序服务端定死＝生效起点倒序（价史新段在前）、id 倒序稳定同起点。page 1
     * 基，缺省第 1 页 20 条，size 上界 100。
     */
    @Transactional(readOnly = true)
    public PageResponse<UnitPriceEntryResponse> entries(String provider, String model,
                                                        int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        BackofficePriceEntryQuery query = new BackofficePriceEntryQuery(provider, model);
        Specification<PriceEntry> specification = ConditionSpecifications.fromAnnotation(query);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize,
                Sort.by(Sort.Direction.DESC, "effectiveFrom").and(
                        Sort.by(Sort.Direction.DESC, "id")));
        Page<PriceEntry> result = priceEntryRepository.findAll(specification, pageable);
        return new PageResponse<>(
                result.getContent().stream().map(UnitPriceEntryResponse::of).toList(),
                result.getTotalElements(),
                safePage,
                safeSize);
    }

    /**
     * 原子改价（单调用关当前行＋开新行，同事务）：新行沿用被关行匹配键，单价/
     * 币种取命令；{@code effectiveFrom} 可指定（含未来＝预发布，对齐供应商凌晨
     * 调价），缺省即时。中途任一守卫失败两行都不动（事务回滚）。
     *
     * @throws ApplicationException METER_006 行不存在；METER_004 字段不完整/
     *                              单价负数；METER_010 币种非 ISO 4217；METER_005
     *                              起点早于被关行起点；METER_007 目标非当前行；
     *                              METER_008 生效区间重叠（跨区间或同起点）
     */
    @Transactional
    public UnitPriceEntryRepriceResponse reprice(Long entryId, RepricePriceEntryCommand command,
                                                 Operator operator) {
        PriceEntry current = requireEntry(entryId);
        Instant effectiveFrom = command.effectiveFrom() == null
                ? Instant.now() : command.effectiveFrom();

        assertIntervalFree(current, effectiveFrom);

        current.close(effectiveFrom); // METER_005 起点倒挂 / METER_007 非当前行
        PriceEntry opened = PriceEntry.open(current.getProvider(), current.getModel(),
                current.getTokenKind(), command.unitPrice(), command.currency(), effectiveFrom,
                operator);
        priceEntryRepository.save(current);
        priceEntryRepository.save(opened);
        return new UnitPriceEntryRepriceResponse(UnitPriceEntryResponse.of(current),
                UnitPriceEntryResponse.of(opened));
    }

    /**
     * 停用（即时生效）：关行不接新行，此后用量进 unpriced（缺价不伪装 0）。
     * 对未生效的预发布行停用＝钳到自身起点成空区间（从未生效）。停用动作的
     * 操作者落被关行（停用不接新行，被关行是唯一落点）。
     *
     * @throws ApplicationException METER_006 行不存在；METER_007 目标非当前行
     */
    @Transactional
    public UnitPriceEntryResponse deactivate(Long entryId, Operator operator) {
        PriceEntry current = requireEntry(entryId);
        current.deactivate(Instant.now(), operator);
        priceEntryRepository.save(current);
        return UnitPriceEntryResponse.of(current);
    }

    private PriceEntry requireEntry(Long entryId) {
        return priceEntryRepository.findById(entryId)
                .orElseThrow(() -> new ApplicationException(MeteringMessage.PRICE_ENTRY_NOT_FOUND));
    }

    /**
     * 同键区间校验：拟开区间 {@code [F, ∞)} 与既有行两查——同起点（含被关行
     * 自身：关行不改写起点，直插撞唯一约束）与跨区间重叠（除被关行外，任一行
     * 敞口或终点晚于 F 即重叠——半开区间 [a, b) 与 [F, ∞) 相交当且仅当 F &lt; b）。
     */
    private void assertIntervalFree(PriceEntry closing, Instant effectiveFrom) {
        List<PriceEntry> rows = priceEntryRepository.findByProviderAndModelAndTokenKindOrderByEffectiveFromAsc(
                closing.getProvider(), closing.getModel(), closing.getTokenKind());
        for (PriceEntry row : rows) {
            if (effectiveFrom.equals(row.getEffectiveFrom())) {
                throw new ApplicationException(MeteringMessage.PRICE_ENTRY_INTERVAL_OVERLAPPED);
            }
            if (!row.getId().equals(closing.getId())
                    && (row.getEffectiveTo() == null || effectiveFrom.isBefore(row.getEffectiveTo()))) {
                throw new ApplicationException(MeteringMessage.PRICE_ENTRY_INTERVAL_OVERLAPPED);
            }
        }
    }
}
