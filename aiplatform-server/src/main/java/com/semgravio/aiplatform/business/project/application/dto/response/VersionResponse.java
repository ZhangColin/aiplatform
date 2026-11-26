package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersion;

/**
 * 版本读面（#91/#93/#626 单轴时间线）：版本序列单条——commit hash + 主题（用户
 * 那句话）+ 版本号 vN + 成版时刻 + 当前/已确认标记。正本 = 容器内 git log（无
 * 便利表）。用户面版本（Platform-Version 提交）携版本号与 run 标识；旧回滚版本
 * versionNo 为 null。{@code current}＝序列首条（最新交付）；{@code confirmed}＝
 * 已确认用于签约（#626 过渡映射旧确认指针，商业面改接归 #629）。
 */
public record VersionResponse(
        String commitHash,
        String subject,
        Integer versionNo,
        String runId,
        String rollbackFrom,
        LocalDateTime committedAt,
        boolean current,
        boolean confirmed) {

    /** 域条目 → 读面（非时间线检索面：current/confirmed 缺省 false）。 */
    public static VersionResponse of(WorkspaceVersion version) {
        return of(version, false, false);
    }

    /** 域条目 + 时间线标位 → 读面（git epoch 秒转 LocalDateTime，与对话史 at 同 JVM 时区口径）。 */
    public static VersionResponse of(WorkspaceVersion version, boolean current, boolean confirmed) {
        return new VersionResponse(
                version.commitHash(),
                version.subject(),
                version.versionNo(),
                version.runId(),
                version.rollbackFrom(),
                LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(version.committedAtEpochSeconds()),
                        ZoneId.systemDefault()),
                current,
                confirmed);
    }
}
