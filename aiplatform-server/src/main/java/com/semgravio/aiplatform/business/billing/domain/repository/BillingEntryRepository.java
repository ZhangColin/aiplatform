package com.semgravio.aiplatform.business.billing.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.billing.domain.aggregate.BillingEntry;
import com.semgravio.aiplatform.business.billing.domain.enums.EntryKind;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;

/**
 * 流水仓储（append-only 账本读面）：TSID 时间有序——按 id 升序即时间序。
 * 「我的账户」/项目流水（后续票）与对账断言（测试）经此读。
 */
public interface BillingEntryRepository extends BaseRepository<BillingEntry, Long> {

    /** 池的全量流水（入账/扣减/调整混序时间线）。 */
    List<BillingEntry> findByPoolIdOrderByIdAsc(Long poolId);

    /** 项目名下的扣减流水（段归属维度——扣减行必落 projectId；入账/调整行不计）。 */
    List<BillingEntry> findByProjectIdAndKindOrderByIdAsc(Long projectId, EntryKind kind);

    /**
     * 段幂等判定（first-write-wins 前置查）：同 (项目, 段型, 段址) 是否已有扣减行
     * ——段收口重试/重放不双扣（库侧部分唯一索引 uk_bil_entries_segment 兜底）。
     */
    boolean existsByProjectIdAndSegmentTypeAndSegmentRef(Long projectId, SegmentType segmentType,
            String segmentRef);
}
