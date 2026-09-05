package com.aieducenter.aiplatform.business.project.domain.model;

import java.util.Map;

/**
 * 计量 dims 单点装配（#24 接线）：终态口径 = {@code projectId + agentKind(main/executor)
 * + sessionId}——智能体调用的归属维度统一由此拼装，写侧（主智能体/生成/取名）与
 * 读侧（项目用量分智能体聚合）共一套键，不漂移。键值为底座透传协议
 * （{@code UsageEvent.dims}），底座不解释。
 */
public final class UsageDims {

    /** 维度键：项目标识（与 subject 同值——dims 自描述归属，分析面可独立取用）。 */
    public static final String KEY_PROJECT_ID = "projectId";

    /** 维度键：智能体种类（主链 main/executor = {@link #kindOf}；辅助调用见 AGENT_KIND_*）。 */
    public static final String KEY_AGENT_KIND = "agentKind";

    /** 维度键：智能体会话标识（main-{projectId} / coder-{projectId} / naming-{projectId}）。 */
    public static final String KEY_SESSION_ID = "sessionId";

    /** 取名调用的智能体种类（一次性辅助调用，非主链配置、无展示名）。 */
    public static final String AGENT_KIND_NAMING = "naming";

    /** 入口分类调用的智能体种类（#47 三分类轻量调用，一次性辅助面、无展示名）。 */
    public static final String AGENT_KIND_CLASSIFY = "classify";

    private UsageDims() {
    }

    /** 主链智能体（AgentProfile）的种类键：配置稳定键（main/executor，读侧 byKey 回解）。 */
    public static String kindOf(AgentProfile profile) {
        return profile.key();
    }

    /** 三键 dims 装配（写侧统一入口）。 */
    public static Map<String, String> of(Long projectId, String agentKind, String sessionId) {
        return Map.of(
                KEY_PROJECT_ID, projectId.toString(),
                KEY_AGENT_KIND, agentKind,
                KEY_SESSION_ID, sessionId);
    }
}
