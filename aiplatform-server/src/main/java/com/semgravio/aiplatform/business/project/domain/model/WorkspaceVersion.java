package com.semgravio.aiplatform.business.project.domain.model;

/**
 * 版本（#91/#93/#626）：系统存档点（CONTEXT.md「版本」词条）——容器内 git 的
 * 一个成版 commit。阶段四（#626，ADR-0056）重建为<b>用户面版本</b>：每轮成功
 * 生成探活通过后平台自动成版，成员判据＝带 {@code Platform-Version} trailer 的
 * 提交（版本号 v1/v2/v3）；回滚版本带 {@code Rollback-From} trailer（历史版本
 * 兼容读法）。跑前快照提交无任何 trailer——不进版本序列。版本正本 = 沙箱工作区
 * 的 git log，本记录是其单条目的读面。
 *
 * @param commitHash             成版 commit 的完整 hash（回滚的寻址锚）
 * @param committedAtEpochSeconds 成版时刻（git 提交时间，epoch 秒）
 * @param subject                提交主题 =「用户那句话」（回滚版＝「回到 vN」）
 * @param versionNo              用户面版本号（v1/v2/v3；仅 Platform-Version 提交有，
 *                               旧 Rollback-From 版本为 null）
 * @param runId                  成版 run 标识（Platform-Version 提交携带，来自
 *                               Run-Id trailer；其余为 null）
 * @param rollbackFrom           回滚的源版本 hash（回滚版携带，其余为 null）
 */
public record WorkspaceVersion(String commitHash, long committedAtEpochSeconds,
        String subject, Integer versionNo, String runId, String rollbackFrom) {
}
