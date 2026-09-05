package com.aieducenter.aiplatform.business.project.domain.model;

/**
 * 版本（#91/#93）：每轮 run 收口自动成版或回滚追加成版的系统存档点
 * （CONTEXT.md「版本」词条）——容器内 git 的一个成版 commit。成员判据二选一：
 * 带 Run-Id trailer 锚定收尾卡的 run 版本，或带 Rollback-From trailer 的回滚
 * 版本（回滚只回代码不回数据，历史只追加不改写）。版本正本 = 沙箱工作区的
 * git log，本记录是其单条目的读面。
 *
 * @param commitHash             成版 commit 的完整 hash（详情/查看当时/回滚的寻址锚）
 * @param committedAtEpochSeconds 成版时刻（git 提交时间，epoch 秒）
 * @param subject                提交主题 = 收口摘要（run 版本）或「回滚到…」（回滚版本）
 * @param runId                  锚定的编码 run（用户面 run 身份，首试 runId）——
 *                               收尾卡（对话史 closing 条目）的联接键；回滚版本无 run，为 null
 * @param rollbackFrom           回滚的源版本 hash（回滚版本）；run 版本为 null
 */
public record WorkspaceVersion(String commitHash, long committedAtEpochSeconds,
        String subject, String runId, String rollbackFrom) {
}
