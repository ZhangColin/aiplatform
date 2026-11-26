package com.semgravio.aiplatform.base.knowledge.domain.model;

/**
 * 项目飞轮提炼统计（#643，来自 {@code knw_experience_distillations} 的只读聚合）：
 * 修改轮数＝该项目提炼处理记录里版本号大于 1 的条数（用户面版本 v2、v3……）；
 * 没做对轮数＝其中判定「需求写明了但没做对」的记录条数（同一轮多条只算一轮——
 * 记录以版本为唯一键，一条记录即一轮）。
 *
 * @param projectId         项目标识
 * @param projectName       项目名快照（取该项目最新一条提炼记录的快照名）
 * @param modificationRounds 修改轮数（版本号 &gt; 1 的记录条数）
 * @param missedRounds      没做对轮数（修改轮中 missed_count &gt; 0 的记录条数）
 */
public record ProjectDistillationStats(
        long projectId,
        String projectName,
        int modificationRounds,
        int missedRounds) {
}
