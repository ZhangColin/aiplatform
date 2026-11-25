package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.time.Instant;

/**
 * 项目飞轮知识侧统计（#643）：把命中留痕与事后提炼处理记录两张表按项目合并后的
 * 只读读模型——修改轮数、没做对轮数（来自提炼记录）、命中次数与最近命中时间
 * （来自留痕）。没有提炼记录的项目快照名为 null；没有命中的项目最近命中时间为
 * null。项目行本身由消费方（后台项目读面）按项目表组装。
 *
 * @param projectId          项目标识
 * @param projectNameSnapshot 项目名快照（提炼记录里的最新快照名；无记录为 null）
 * @param modificationRounds 修改轮数（版本号 &gt; 1 的提炼记录条数）
 * @param missedRounds       没做对轮数（修改轮中 missed_count &gt; 0 的记录条数）
 * @param hitCount           命中次数（含命中条目的留痕条数）
 * @param latestHitAt        最近命中时间（从未命中为 null）
 */
public record ProjectKnowledgeFlywheelStats(
        String projectId,
        String projectNameSnapshot,
        int modificationRounds,
        int missedRounds,
        int hitCount,
        Instant latestHitAt) {
}
