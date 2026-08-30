package com.aieducenter.aiplatform.base.agentscope;

import java.util.Map;

/**
 * 智能体调用的计量归属：subject 为不透明归属 id（业务层定，如 projectId），dims
 * 业务维度原样进 UsageEvent。为空则本轮调用不上报用量（底座不发明归属）。
 */
public record UsageContext(String subject, Map<String, String> dims) {

    public UsageContext {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("UsageContext.subject 不能为空（计量归属必填）");
        }
        dims = dims == null ? Map.of() : Map.copyOf(dims);
    }
}
