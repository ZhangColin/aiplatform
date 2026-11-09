package com.semgravio.aiplatform.business.project.domain.decisionhint;

/**
 * 决策时注入生效配置（#437 运营配置面的判定消费形）：总开关＋策略（阈值/上限/
 * 禁用集）。运营面 {@code decision_hints_enabled} 开关关＝本配置 disabled 形——
 * 中间件原样透传、零注入零观测。库值优先、缺省回落代码缺省（经
 * {@code AgentConfigAppService} 装配读腿解析）。
 *
 * @param enabled 总开关（false＝注入全停，缺省 true＝#437 落地态）
 * @param policy  注入策略（阈值/堆叠上限/禁用集；disabled 形回落缺省即可）
 */
public record DecisionHintConfig(boolean enabled, HintPolicy policy) {

    /** 总开关开＝给定策略生效。 */
    public static DecisionHintConfig enabled(HintPolicy policy) {
        return new DecisionHintConfig(true, policy);
    }

    /** 总开关关（策略回落缺省——不消费）。 */
    public static DecisionHintConfig disabled() {
        return new DecisionHintConfig(false, HintPolicy.defaults());
    }
}
