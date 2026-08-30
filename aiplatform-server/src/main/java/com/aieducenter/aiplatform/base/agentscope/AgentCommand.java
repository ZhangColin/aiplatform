package com.aieducenter.aiplatform.base.agentscope;

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
 * 可空——流关联字段（如 projectId，底座不解释，逐帧注入智能体流 payload）。</p>
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
        Map<String, Object> streamCorrelation) {

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
