package com.aieducenter.aiplatform.base.agentscope;

import java.time.Duration;
import java.util.Map;

import com.aieducenter.aiplatform.base.eventhub.domain.model.EventEnvelope;

/**
 * 一轮智能体调用的全部入参：平台进程内 HarnessAgent 单轮对话。
 *
 * <p>{@code runId} 为本轮调用的平台标识（计量幂等键与事件锚定都基于它）；
 * {@code systemPrompt} / {@code modelString} 可空——为空时取配置默认；
 * {@code usageContext} 可空——为空则本轮不上报用量（底座不发明归属）；
 * {@code workspaceId} 可空——为空落配置的本地工作区，带值则解析为项目工作区
 * （智能体读写项目文件的基础）；{@code streamCorrelation} 可空——流关联字段
 * （如 projectId，底座不解释，逐事件注入智能体事件 payload）；{@code timeout}
 * 可空——本轮对话超时，为空取内核配置默认（对话轮 2 分钟量级，编码轮长任务
 * 另行指定）；{@code agentKey} 可空——该轮智能体的配置键（业务侧配置标识，
 * 底座不解释），按配置发放工具集的寻址腿（见 {@link AgentToolkitSupplier}；
 * 为空 = 无配置语境，空工具面），并随 run-start 载荷透出（前端登记锚）；
 * {@code workspaceReadOnly}——项目工作区解析为只读面（#86 主智能体姿态：
 * 不挂内核文件/shell 工具，写面结构性关闭；缺省 false = 读写面）；
 * {@code heading} 可空——工作消息头部标题（#118：用户语言标题 + 生成轨道切片
 * 进度），底座不解释，随 run-start 载荷透出（{@code slice} 字段，前端工作消息
 * 头部呈现源）。</p>
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
        String agentKey,
        boolean workspaceReadOnly,
        RunHeading heading) {

    /** 无逐轮超时的兼容形（取内核配置默认）：无配置语境的一次性本地会话调用面
     * （取名等）不变——空工具面。 */
    public AgentCommand(String runId, String prompt, String systemPrompt, String modelString,
            String sessionId, String userId, UsageContext usageContext,
            String workspaceId, Map<String, Object> streamCorrelation) {
        this(runId, prompt, systemPrompt, modelString, sessionId, userId,
                usageContext, workspaceId, streamCorrelation, null, null, false, null);
    }

    /** 无逐轮超时、带配置键的对话形（主智能体对话轮调用面：配置键穿透工具装配）。 */
    public AgentCommand(String runId, String prompt, String systemPrompt, String modelString,
            String sessionId, String userId, UsageContext usageContext,
            String workspaceId, Map<String, Object> streamCorrelation, String agentKey) {
        this(runId, prompt, systemPrompt, modelString, sessionId, userId,
                usageContext, workspaceId, streamCorrelation, null, agentKey, false, null);
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
