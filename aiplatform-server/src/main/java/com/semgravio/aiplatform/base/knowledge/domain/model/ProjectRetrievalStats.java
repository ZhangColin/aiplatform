package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.time.Instant;

/**
 * 项目命中统计（#643，来自 {@code knw_retrievals} 的只读聚合）：命中次数＝该项目
 * 含命中条目的留痕条数（检索成功但 0 条与降级都不计）；最近命中时间＝这些留痕里
 * 最晚的检索时间。项目没有任何命中记录时不出现在聚合结果里（读面按 0 呈现）。
 *
 * @param projectId    项目标识（留痕表原值，VARCHAR）
 * @param hitCount     命中次数（含命中条目的留痕条数）
 * @param latestHitAt  最近命中时间（含命中条目的留痕里最晚一条；恒非空）
 */
public record ProjectRetrievalStats(
        String projectId,
        int hitCount,
        Instant latestHitAt) {
}
