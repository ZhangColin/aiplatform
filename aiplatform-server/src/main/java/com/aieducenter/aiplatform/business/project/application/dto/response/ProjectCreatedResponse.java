package com.aieducenter.aiplatform.business.project.application.dto.response;

/**
 * 建项目响应（项目详情 + 自动开场运行的标识——前端挂智能体事件 ?runId= 的锚）。
 *
 * @param project  项目详情
 * @param runId    自动开场运行标识（起跑异常时仍返回项目，runId=null）
 */
public record ProjectCreatedResponse(
        ProjectDetailResponse project,
        String runId
) {
}
