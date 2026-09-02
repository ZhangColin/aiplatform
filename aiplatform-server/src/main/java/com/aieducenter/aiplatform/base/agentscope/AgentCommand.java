package com.aieducenter.aiplatform.base.agentscope;

import java.time.Duration;
import java.util.Map;

import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;

/**
 * 一轮智能体调用的全部入参：平台进程内 HarnessAgent 单轮对话。
 *
 * <p>{@code runId} 为本轮调用的平台标识（计量幂等键与流帧锚定都基于它）；
 * {@code systemPrompt} / {@code modelString} 可空——为空时取配置默认；
 * {@code usageContext} 可空——为空则本轮不上报用量（底座不发明归属）；
 * {@code workspaceId} 可空——为空落配置的本地工作区，带值则解析为项目 dev 工作区
 * （智能体读写项目文件，BA 写 docs/PRD.md 的基础）；{@code streamCorrelation}
 * 可空——流关联字段（如 projectId，底座不解释，逐帧注入智能体流 payload）；
 * {@code timeout} 可空——本轮对话超时，为空取内核配置默认（对话轮 2 分钟量级，
 * 编码轮长任务另行指定）；{@code live}——该 run 开直播（编码 run 姿态：过程帧外
 * 并产直播帧，见 {@link AgentscopeLiveMapper}；缺省 false，BA 对话不流式不留痕）；
 * {@code agentRole} 可空——该轮智能体的角色键（业务侧角色名，底座不解释），
 * 按角色发放工具集的寻址腿（见 {@link AgentToolkitSupplier}；为空 = 无角色语境，
 * 空工具面）；{@code workspaceReadOnly}——项目工作区解析为只读面（#47 助理咨询
 * 姿态：不挂内核文件/shell 工具，写面结构性关闭；缺省 false = 读写面）。</p>
 */
public record AgentCommand(
        String runId,
        String prompt,
        String systemPrompt,
        String modelString,
        String sessionId,
        String userId,
        UsageContext usageContext,
        String workspaceId,
        Map<String, Object> streamCorrelation,
        Duration timeout,
        boolean live,
        String agentRole,
        boolean workspaceReadOnly) {

    /** 无逐轮超时的兼容形（取内核配置默认，不开直播）：无角色语境的一次性本地
     * 会话调用面（取名等）不变——空工具面。 */
    public AgentCommand(String runId, String prompt, String systemPrompt, String modelString,
            String sessionId, String userId, UsageContext usageContext,
            String workspaceId, Map<String, Object> streamCorrelation) {
        this(runId, prompt, systemPrompt, modelString, sessionId, userId,
                usageContext, workspaceId, streamCorrelation, null, false, null, false);
    }

    /** 无逐轮超时、带角色的对话形（BA 访谈调用面：不开直播，角色键穿透工具装配）。 */
    public AgentCommand(String runId, String prompt, String systemPrompt, String modelString,
            String sessionId, String userId, UsageContext usageContext,
            String workspaceId, Map<String, Object> streamCorrelation, String agentRole) {
        this(runId, prompt, systemPrompt, modelString, sessionId, userId,
                usageContext, workspaceId, streamCorrelation, null, false, agentRole, false);
    }

    public AgentCommand {
        if (runId == null || runId.isBlank() || prompt == null || prompt.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("AgentCommand 字段不完整：runId/prompt/sessionId 必填");
        }
        streamCorrelation = streamCorrelation == null ? Map.of() : Map.copyOf(streamCorrelation);
        if (streamCorrelation.containsKey(EventEnvelope.TYPE_KEY)) {
            throw new IllegalArgumentException(
                    "streamCorrelation 禁含 " + EventEnvelope.TYPE_KEY + " 键（信封契约）");
        }
    }
}
