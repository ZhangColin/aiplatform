package com.aieducenter.aiplatform.base.skills.application.dto.response;

import java.util.List;

/**
 * 指派双头预检回执（#255，#254 spec：非阻断提示不是门，判定权留后台管理员）：
 * {@code executed} 与 {@code hints} 两件同读——executed=true 判定已执行
 * （hints 空＝确认无重叠）；false＝未执行（判定失败/超时/输出不可解析，hints
 * 恒空）。两态必须可区分：降级如实标注，不伪装成「确认无风险」（防降级伪装
 * 成安全）；预检不拦指派，指派端点语义不动。
 */
public record BackofficeSkillPrecheckResponse(boolean executed, List<PrecheckHint> hints) {

    public BackofficeSkillPrecheckResponse {
        hints = hints == null ? List.of() : List.copyOf(hints);
    }

    /** 未执行态（降级形——零提示，与「无重叠的成功态」靠 executed 区分）。 */
    public static BackofficeSkillPrecheckResponse notExecuted() {
        return new BackofficeSkillPrecheckResponse(false, List.of());
    }

    /**
     * 单条方法论重叠提示：涉及哪些技能（skills——候选侧技能名）、与什么重叠
     * （counterpart——「工作协议」或对侧技能名）、重叠内容（overlap）、消解
     * 方向（resolution——如二选一、收窄 description、可不指派）。
     */
    public record PrecheckHint(List<String> skills, String counterpart, String overlap,
            String resolution) {

        public PrecheckHint {
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }
}
