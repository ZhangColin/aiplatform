package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

/**
 * PRD 确认版本响应（#330，含全文的详情/确认回执形；#331 增收敛度读面）：确认
 * 动作回执与版本详情共用——确认成功即返回版本号（生成加门 #332 的挂点：确认后
 * 自动派 Demo run 消费本面）；readiness＝确认时对快照全文的分级解析投影
 * （#331：仅次要待定过门放行，回执带未解决事项供两段式摘要层消费）。
 *
 * @param projectId  项目标识（TSID 十进制字符串）
 * @param versionNo  项目内递增整数版本号（1 起——v1/v2/v3…，草稿不编号）
 * @param content    确认时工作区 PRD 全文快照（markdown 原样，不可变）
 * @param confirmedAt 确认时间
 * @param readiness  收敛度投影（对快照全文即时解析——不落库，与草稿读面同构）
 */
public record PrdVersionResponse(
        String projectId,
        int versionNo,
        String content,
        LocalDateTime confirmedAt,
        PrdReadinessResponse readiness
) {
}
