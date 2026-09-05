package com.aieducenter.aiplatform.business.project.domain.model;

/**
 * 版本（#91）：每轮 run 收口自动成版的系统存档点（CONTEXT.md「版本」词条）——
 * 容器内 git 的一个成版 commit（带 Run-Id trailer 锚定收尾卡）。版本正本 =
 * 沙箱工作区的 git log，本记录是其单条目的读面。
 *
 * @param commitHash             成版 commit 的完整 hash（详情/查看当时/回滚的寻址锚）
 * @param committedAtEpochSeconds 成版时刻（git 提交时间，epoch 秒）
 * @param subject                提交主题 = 收口摘要（本轮做了什么）
 * @param runId                  锚定的编码 run（用户面 run 身份，首试 runId）——
 *                               收尾卡（对话史 closing 条目）的联接键
 */
public record WorkspaceVersion(String commitHash, long committedAtEpochSeconds,
        String subject, String runId) {
}
