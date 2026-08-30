package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 建项目响应（项目详情 + 前缀段自动 BA 的运行标识——前端挂 agent 流 ?runId= 的锚）。
 *
 * @param project  项目详情
 * @param runId    自动 BA 运行标识（起跑异常时仍返回项目，runId=null）
 * @param accepted BA 运行是否被接受
 */
public record ProjectCreatedResponse(
        ProjectDetailResponse project,
        String runId,
        boolean accepted
) {
}
