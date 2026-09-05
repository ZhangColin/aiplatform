package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import com.aieducenter.aiplatform.business.project.domain.model.WorkspaceVersion;

/**
 * 版本详情读面（#91）：版本元数据 + 锚定的收尾卡载荷（Run-Id 联接对话史
 * closing 条目——收尾卡落库失败等缺口下 closing 可空，版本元数据仍如实返回）。
 */
public record VersionDetailResponse(
        String commitHash,
        String subject,
        String runId,
        LocalDateTime committedAt,
        Map<String, Object> closing) {

    /** 域条目 + 收尾卡载荷 → 读面。 */
    public static VersionDetailResponse of(WorkspaceVersion version, Map<String, Object> closing) {
        return new VersionDetailResponse(
                version.commitHash(),
                version.subject(),
                version.runId(),
                LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(version.committedAtEpochSeconds()),
                        ZoneId.systemDefault()),
                closing);
    }
}
