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
 * 并产直播帧，见 {@link AgentscopeLiveMapper}；缺省 false，BA 对话不流式不留痕）。</p>
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
        boolean live) {

    /** 无逐轮超时的兼容形（取内核配置默认，不开直播）：短对话调用面（BA / 取名）不变。 */
    public AgentCommand(String runId, String prompt, String systemPrompt, String modelString,
            String sessionId, String userId, UsageContext usageContext,
            String workspaceId, Map<String, Object> streamCorrelation) {
        this(runId, prompt, systemPrompt, modelString, sessionId, userId,
                usageContext, workspaceId, streamCorrelation, null, false);
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
