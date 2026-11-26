package com.semgravio.aiplatform.business.billing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingPool;
import com.semgravio.aiplatform.business.billing.domain.aggregate.ProjectBilling;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.PieceKind;
import com.semgravio.aiplatform.business.billing.domain.enums.PoolKind;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.billing.domain.error.BillingMessage;
import com.semgravio.aiplatform.business.billing.domain.model.PricedLine;
import com.semgravio.aiplatform.business.billing.domain.model.PricingTier;
import com.semgravio.aiplatform.business.billing.domain.port.UsagePricingPort;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingEntryRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.BillingPoolRepository;
import com.semgravio.aiplatform.business.billing.domain.repository.ProjectBillingRepository;

/**
 * 段结算用例（#531 计费①，ADR-0047 段收口结算）：付费项目结算段收口即结算一次
 * ——按段汇总用量×成本单价×全局折算率落<b>一条</b>扣减行（带金额＋用量＋单价
 * 快照）；固定单价项（联网搜索等计件族）<b>按次直记</b>不等段收口。试用项目一切
 * 调用零扣减行（两轨判定读点内嵌判定，行不落、池不动）。
 *
 * <p><b>段中自由</b>：结算只挂段收口、不查余额不设下限——扣成负照落（负余额软
 * 模型，段边界检查归 #537）。金额＝Σ(token 线 用量×单价)×折算率＋Σ(计件线
 * 件数×单价)；只结算 CNY 价线（非 CNY 线不进用户账、快照留痕＋WARN——平台
 * 吸收，成本观测面仍可见）；全未配价/零金额不落行（不伪装 0 金额扣减）。分位
 * 取整只在行总额一次 HALF_UP（线内不逐线取整，避免漂移）。</p>
 *
 * <p><b>计费边界与幂等</b>：计价线只计付费状态翻转时点（{@code flippedAt}）之后
 * 的事件——翻转前调用＝平台获客成本，机制面过滤不靠「两门都在段边界」的编排
 * 自律。同一 (projectId, 段型, 段址) 至多一条扣减行（库侧部分唯一索引兜底＋
 * 服务层 first-write-wins——段收口重试/重放不双扣，与计量 eventId 幂等同律；
 * 固定单价项的段址＝其发生锚点〔如用量事件 eventId〕，同锚点直记幂等）。</p>
 *
 * <p>段收口缝的编排接线（主智能体轮/run 场两缝；设计会话轮生产者随 #610 设计线
 * 退役，{@code DESIGN_TURN} 历史值保留）归 #537 同缝落位
 * ——本服务收 {@code usageRunIds}（段内各次智能体运行的用量事件寻址键），用量
 * 单源取自计量面（met_usage_events，与平台成本观测同一账）。</p>
 */
@Service
@Slf4j
public class SegmentSettlementAppService {

    private final ProjectBillingRepository projectBillingRepository;
    private final BillingPoolRepository poolRepository;
    private final BillingEntryRepository entryRepository;
    private final BillingAppService billingAppService;
    private final UsagePricingPort usagePricing;

    public SegmentSettlementAppService(ProjectBillingRepository projectBillingRepository,
            BillingPoolRepository poolRepository, BillingEntryRepository entryRepository,
            BillingAppService billingAppService, UsagePricingPort usagePricing) {
        this.projectBillingRepository = projectBillingRepository;
        this.poolRepository = poolRepository;
        this.entryRepository = entryRepository;
        this.billingAppService = billingAppService;
        this.usagePricing = usagePricing;
    }

    /**
     * 段收口结算（三段型各落一条扣减行）：试用项目直接空过（零扣减行——计费边界
     * ＝翻转时间点，翻转前的调用平台担）；付费项目按段用量落扣减行＋扣源池
     * （可扣成负）。零金额/全未配价/已结算过（同段址重试）空过不落行。
     *
     * @param projectId   项目
     * @param segmentType 段型（主智能体轮/run 场/设计会话轮——固定单价项走
     *                    {@link #recordFixedItem}，本口拒收 BILL_013）
     * @param segmentRef  段址（runId 等调用方标识，快照与幂等锚点）
     * @param usageRunIds 段内各次智能体运行的用量事件寻址键（run_id 集）
     */
    @Transactional
    public SettlementOutcome settleSegment(Long projectId, SegmentType segmentType,
            String segmentRef, Collection<String> usageRunIds) {
        if (projectId == null || segmentType == null || !segmentType.isSegment()
                || isBlank(segmentRef) || usageRunIds == null || usageRunIds.isEmpty()) {
            throw new ApplicationException(BillingMessage.SETTLEMENT_SEGMENT_INVALID);
        }
        String ref = segmentRef.trim();
        if (entryRepository.existsByProjectIdAndSegmentTypeAndSegmentRef(
                projectId, segmentType, ref)) {
            return SettlementOutcome.skipped("ALREADY_SETTLED"); // 段收口重试不双扣
        }
        ProjectBilling billing = projectBillingRepository.findByProjectId(projectId).orElse(null);
        if (billing == null || !billing.paid()) {
            return SettlementOutcome.skipped("TRIAL"); // 试用零扣减（行不落、池不动）
        }
        // 计费边界＝翻转时点：翻转前事件＝平台获客成本，机制面过滤
        List<PricedLine> lines = usagePricing.pricedLinesOf(
                Long.toString(projectId), usageRunIds, billing.getFlippedAt());
        return settleLines(billing, segmentType, ref, lines);
    }

    /**
     * 固定单价项按次直记（不等段收口）：联网搜索等计件族每发生一次记一次——
     * 件数×计件单价（不乘折算率）落扣减行（segmentType=FIXED_ITEM）。试用空过；
     * 无生效单价行空过留 WARN（平台吸收、unpriced 观测面可见，不伪装 0 价）；
     * 同 {@code ref}（发生锚点）重放幂等空过。
     *
     * @param pieceKind 计件档（按张/按件——单价行档别）
     * @param units     件数（正整数）
     * @param ref       直记锚点（如用量事件 eventId——用户账与成本账分记的关联缝）
     */
    @Transactional
    public SettlementOutcome recordFixedItem(Long projectId, String provider, String model,
            PieceKind pieceKind, long units, String ref) {
        if (projectId == null || isBlank(provider) || isBlank(model)
                || pieceKind == null || units <= 0 || isBlank(ref)) {
            throw new ApplicationException(BillingMessage.FIXED_ITEM_INVALID);
        }
        String anchor = ref.trim();
        if (entryRepository.existsByProjectIdAndSegmentTypeAndSegmentRef(
                projectId, SegmentType.FIXED_ITEM, anchor)) {
            return SettlementOutcome.skipped("ALREADY_SETTLED");
        }
        ProjectBilling billing = projectBillingRepository.findByProjectId(projectId).orElse(null);
        if (billing == null || !billing.paid()) {
            return SettlementOutcome.skipped("TRIAL");
        }
        var price = usagePricing.piecePriceOf(provider.trim(), model.trim(), pieceKind, Instant.now());
        if (price.isEmpty() || !BillingPool.CURRENCY_CNY.equals(price.get().currency())) {
            log.warn("固定单价项无 CNY 生效单价行，跳过直记（平台吸收）: projectId={}, provider={}, "
                    + "model={}, kind={}, units={}", projectId, provider, model, pieceKind, units);
            return SettlementOutcome.skipped("UNPRICED");
        }
        List<PricedLine> line = List.of(new PricedLine(provider.trim(), model.trim(),
                PricingTier.PIECE, pieceKind.name(), units, price.get().unitPrice(),
                price.get().currency()));
        return settleLines(billing, SegmentType.FIXED_ITEM, anchor, line);
    }

    /** 线集→扣减行（共用收口：金额计算＋快照组装＋扣池＋落行；撞幂等索引＝并发重复收口，吸收空过）。 */
    private SettlementOutcome settleLines(ProjectBilling billing, SegmentType segmentType,
            String segmentRef, List<PricedLine> lines) {
        BigDecimal rate = billingAppService.conversionRate();
        List<PricedLine> billable = new ArrayList<>();
        List<PricedLine> unconverted = new ArrayList<>();
        BigDecimal tokenAmount = BigDecimal.ZERO; // 元（CNY）
        BigDecimal pieceAmount = BigDecimal.ZERO; // 元（CNY）
        for (PricedLine line : lines) {
            if (!BillingPool.CURRENCY_CNY.equals(line.currency())) {
                unconverted.add(line); // 非 CNY 价线不进用户账（快照留痕＋WARN）
                continue;
            }
            billable.add(line);
            BigDecimal lineAmount = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));
            if (line.tier() == PricingTier.TOKEN) {
                tokenAmount = tokenAmount.add(lineAmount);
            } else {
                pieceAmount = pieceAmount.add(lineAmount);
            }
        }
        if (!unconverted.isEmpty()) {
            log.warn("结算段含非 CNY 计价线（不进用户账、平台吸收，快照留痕）: segmentRef={}, "
                    + "lines={}", segmentRef, unconverted.size());
        }
        long amountFen = tokenAmount.multiply(rate).add(pieceAmount)
                .multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        if (amountFen <= 0) {
            return SettlementOutcome.skipped("NO_PRICED_USAGE"); // 全未配价/零金额不落行
        }
        BillingPool pool = lockedSourcePool(billing);
        pool.deduct(amountFen); // 段中自由：不查余额、可扣成负
        try {
            BillingEntry entry = entryRepository.save(BillingEntry.deduction(
                    pool.getId(), billing.getProjectId(), segmentType, segmentRef,
                    amountFen, pool.getBalance(),
                    snapshotOf(rate, billable, unconverted)));
            return SettlementOutcome.settled(entry.getId(), amountFen, pool.getBalance());
        } catch (DataIntegrityViolationException e) {
            // 并发同段址撞部分唯一索引：后落者吸收（first-write-wins，不双扣）
            log.info("结算段并发重复收口，幂等吸收: projectId={}, segmentType={}, segmentRef={}",
                    billing.getProjectId(), segmentType, segmentRef);
            return SettlementOutcome.skipped("ALREADY_SETTLED");
        }
    }

    /** 源池（行锁）：项目源→项目池；账户源→选定账户定格名下的账户池。池未建＝防御性开零余额池（付费项目必有入账史，此路径＝异常自愈不炸结算）。 */
    private BillingPool lockedSourcePool(ProjectBilling billing) {
        if (billing.getDeductionSource() == DeductionSource.ACCOUNT) {
            return poolRepository.findByKindAndAccountId(PoolKind.ACCOUNT,
                            billing.getSourceAccountId())
                    .map(pool -> poolRepository.lockById(pool.getId()).orElseThrow())
                    .orElseGet(() -> {
                        log.warn("付费项目账户源池缺席，防御性开零余额池: projectId={}, accountId={}",
                                billing.getProjectId(), billing.getSourceAccountId());
                        return poolRepository.save(BillingPool.forAccount(billing.getSourceAccountId()));
                    });
        }
        return poolRepository.findByKindAndProjectId(PoolKind.PROJECT, billing.getProjectId())
                .map(pool -> poolRepository.lockById(pool.getId()).orElseThrow())
                .orElseGet(() -> {
                    log.warn("付费项目项目池缺席，防御性开零余额池: projectId={}",
                            billing.getProjectId());
                    return poolRepository.save(BillingPool.forProject(billing.getProjectId()));
                });
    }

    /** 扣减行快照：折算率＋计价线全量（用量/单价/币种）＋未折算线留痕。 */
    private static Map<String, Object> snapshotOf(BigDecimal rate, List<PricedLine> billable,
            List<PricedLine> unconverted) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("rate", rate);
        snapshot.put("lines", billable.stream().map(SegmentSettlementAppService::lineOf).toList());
        if (!unconverted.isEmpty()) {
            snapshot.put("unconvertedLines", unconverted.stream()
                    .map(SegmentSettlementAppService::lineOf).toList());
        }
        return snapshot;
    }

    private static Map<String, Object> lineOf(PricedLine line) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", line.provider());
        map.put("model", line.model());
        map.put("tier", line.tier().name());
        map.put("kind", line.kindName());
        map.put("quantity", line.quantity());
        map.put("unitPrice", line.unitPrice());
        map.put("currency", line.currency());
        return map;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 结算结果：settled＝落行（带行锚点与行后余额）；skipped＝空过（试用/
     * 已结算/无价/零金额——reason 单点口径）。
     */
    public record SettlementOutcome(boolean settled, Long entryId, long amountFen,
            long balanceAfter, String skippedReason) {

        static SettlementOutcome settled(Long entryId, long amountFen, long balanceAfter) {
            return new SettlementOutcome(true, entryId, amountFen, balanceAfter, null);
        }

        static SettlementOutcome skipped(String reason) {
            return new SettlementOutcome(false, null, 0, 0, reason);
        }
    }
}
