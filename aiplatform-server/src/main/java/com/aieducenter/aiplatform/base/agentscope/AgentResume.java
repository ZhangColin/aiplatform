package com.aieducenter.aiplatform.base.agentscope;

import java.util.List;

import io.agentscope.core.event.ConfirmResult;

/**
 * 挂起续跑请求（问答答复后的恢复）：以 ConfirmResult（用户答复/批准/拒绝）经同一
 * (userId, sessionId) 从 AgentStateStore 恢复上下文续跑。恢复入参均可由业务编排从
 * 项目侧事实重建（角色卡/owner/工作区），待确认工具清单来自挂起帧载荷
 * （question-raised 的 data.toolCalls）。
 *
 * @param runId            挂起轮的运行标识（续跑续在同一 run 上收口）
 * @param sessionId        会话标识（状态槽位寻址）
 * @param userId           会话用户（状态槽位寻址）
 * @param workspaceId      工作区标识（可空 → 本地兜底工作区）
 * @param modelString      模型串（可空 → 配置默认）
 * @param systemPrompt     系统提示词（可空 → 配置默认）
 * @param replyId          挂起的引擎侧请求 id（计量幂等键后缀）
 * @param confirmResults   待确认工具的批复清单（与挂起 toolCalls 一一对应）
 * @param resumeText       恢复消息文本（进 LLM 上下文）
 * @param usageContext     计量归属（可空 → 不上报）
 */
public record AgentResume(
        String runId,
        String sessionId,
        String userId,
        String workspaceId,
        String modelString,
        String systemPrompt,
        String replyId,
        List<ConfirmResult> confirmResults,
        String resumeText,
        UsageContext usageContext) {
}
