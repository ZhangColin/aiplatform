package com.semgravio.aiplatform.business.project.domain.model;

/**
 * 版本（#91/#93）：系统存档点（CONTEXT.md「版本」词条）——容器内 git 的一个
 * 成版 commit。成员判据＝带 Rollback-From trailer 的回滚版本（回滚只回代码不回
 * 数据，历史只追加不改写）；Run-Id 成版提交随审查与收口组退役（#605），存量
 * Run-Id commit 不再入列、run 收口不再自动成版（版本语义归阶段四重建）。版本
 * 正本 = 沙箱工作区的 git log，本记录是其单条目的读面。
 *
 * @param commitHash             成版 commit 的完整 hash（详情/查看当时/回滚的寻址锚）
 * @param committedAtEpochSeconds 成版时刻（git 提交时间，epoch 秒）
 * @param subject                提交主题 =「回滚到…」
 * @param runId                  恒为 null（Run-Id 联接随收口成版提交退役 #605；
 *                               字段留至阶段四版本重建）
 * @param rollbackFrom           回滚的源版本 hash
 */
public record WorkspaceVersion(String commitHash, long committedAtEpochSeconds,
        String subject, String runId, String rollbackFrom) {
}
