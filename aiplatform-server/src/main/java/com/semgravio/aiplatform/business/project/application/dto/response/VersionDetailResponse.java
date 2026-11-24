package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersion;

/**
 * 版本详情读面（#91/#93）：版本元数据 + 收尾卡载荷。Run-Id 联接随收口成版提交
 * 退役（#605）——closing 恒空、runId 恒空；字段契约留至阶段四版本重建（该期
 * 重建成版/收尾卡联动后重新定义）。回滚版本 rollbackFrom 锚定源版本。
 */
public record VersionDetailResponse(
        String commitHash,
        String subject,
        String runId,
        String rollbackFrom,
        LocalDateTime committedAt,
        Map<String, Object> closing) {

    /** 域条目 + 收尾卡载荷 → 读面。 */
    public static VersionDetailResponse of(WorkspaceVersion version, Map<String, Object> closing) {
        return new VersionDetailResponse(
                version.commitHash(),
                version.subject(),
                version.runId(),
                version.rollbackFrom(),
                LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(version.committedAtEpochSeconds()),
                        ZoneId.systemDefault()),
                closing);
    }
}
