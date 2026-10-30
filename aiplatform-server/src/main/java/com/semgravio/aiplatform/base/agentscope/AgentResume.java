package com.semgravio.aiplatform.base.agentscope;

import java.util.List;

import io.agentscope.core.event.ConfirmResult;

/**
 * 挂起续跑请求（问答答复后的恢复）：以 ConfirmResult（用户答复）经同一
 * (userId, sessionId) 从 AgentStateStore 恢复上下文续跑。恢复入参均可由业务编排从
 * 项目侧事实重建（配置/owner/工作区），待确认工具清单来自挂起事件载荷
 * （question-raised 的 data.toolCalls）。
 *
 * @param runId             挂起轮的运行标识（续跑续在同一 run 上收口）
 * @param sessionId         会话标识（状态槽位寻址）
 * @param userId            会话用户（状态槽位寻址）
 * @param workspaceId       工作区标识（可空 → 本地兜底工作区）
 * @param modelString       模型串（必填——档位解析产物「前缀:实际模型名」，语义同
 *                         {@link AgentCommand#modelString()}；业务侧从运营配置重新
 *                         解析重建，不信挂起载荷）
 * @param modelChannel      模型通道注入件（主链非空——续跑与首轮同通道，业务侧
 *                          从运营配置重新解析，不信挂起载荷；null＝测试形，语义同
 *                         {@link AgentCommand#modelChannel()}）
 * @param systemPrompt      系统提示词（可空 → 配置默认）
 * @param replyId           挂起的引擎侧请求 id（计量幂等键后缀）
 * @param confirmResults    待确认工具的批复清单（与挂起 toolCalls 一一对应）
 * @param resumeText        恢复消息文本（进 LLM 上下文）
 * @param usageContext      计量归属（可空 → 不上报）
 * @param agentKey          该轮智能体的配置键（可空，业务侧配置标识，底座不解释）——
 *                         续跑构建 agent 时按配置发放工具集，与首轮命令同键
 * @param workspaceReadOnly 项目工作区是否解析为只读面（#86 起续跑与首轮同面——
 *                         主智能体挂起续跑不漂移成读写面）
 * @param workspaceNoShell 项目工作区是否解析为设计面（#289——续跑与首轮同面：
 *                         designer 会话若有挂起续跑，不漂移成带 shell 的读写面）
 * @param toolSpec          工具面规格串（#252，可空——语义同 {@link AgentCommand#toolSpec()}）：
 *                         续跑与首轮同规格（业务侧从同一运营配置解析重建，不信挂起载荷）
 */
public record AgentResume(
        String runId,
        String sessionId,
        String userId,
        String workspaceId,
        String modelString,
        ModelChannel modelChannel,
        String systemPrompt,
        String replyId,
        List<ConfirmResult> confirmResults,
        String resumeText,
        UsageContext usageContext,
        String agentKey,
        boolean workspaceReadOnly,
        boolean workspaceNoShell,
        String toolSpec) {

    public AgentResume {
        if (runId == null || runId.isBlank() || sessionId == null || sessionId.isBlank()
                || modelString == null || modelString.isBlank()) {
            throw new IllegalArgumentException(
                    "AgentResume 字段不完整：runId/sessionId/modelString 必填"
                            + "（modelString＝档位解析产物，与 AgentCommand 同契约）");
        }
    }
}
