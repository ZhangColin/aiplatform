package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

/**
 * PRD 确认版本响应（#330，含全文的详情/确认回执形）：确认动作回执与版本详情
 * 共用——确认成功即返回版本号（生成加门 #332 的挂点：确认后自动派 Demo run
 * 消费本面）。
 *
 * @param projectId  项目标识（TSID 十进制字符串）
 * @param versionNo  项目内递增整数版本号（1 起——v1/v2/v3…，草稿不编号）
 * @param content    确认时工作区 PRD 全文快照（markdown 原样，不可变）
 * @param confirmedAt 确认时间
 */
public record PrdVersionResponse(
        String projectId,
        int versionNo,
        String content,
        LocalDateTime confirmedAt
) {
}
