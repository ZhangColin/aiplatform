package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

/**
 * PRD 确认版本清单条目（#330，轻量形不含全文——全文走单版详情端点）。
 *
 * @param projectId   项目标识（TSID 十进制字符串）
 * @param versionNo   项目内递增整数版本号（1 起——v1/v2/v3…）
 * @param confirmedAt 确认时间
 */
public record PrdVersionSummaryResponse(
        String projectId,
        int versionNo,
        LocalDateTime confirmedAt
) {
}
