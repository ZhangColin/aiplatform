package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceVersion;

/**
 * 版本读面（#91）：版本序列单条——成版 commit hash + 收口摘要 + 锚定 run +
 * 成版时刻。正本 = 容器内 git log（无便利表）。
 */
public record VersionResponse(
        String commitHash,
        String subject,
        String runId,
        LocalDateTime committedAt) {

    /** 域条目 → 读面（git epoch 秒转 LocalDateTime，与对话史 at 同 JVM 时区口径）。 */
    public static VersionResponse of(WorkspaceVersion version) {
        return new VersionResponse(
                version.commitHash(),
                version.subject(),
                version.runId(),
                LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(version.committedAtEpochSeconds()),
                        ZoneId.systemDefault()));
    }
}
