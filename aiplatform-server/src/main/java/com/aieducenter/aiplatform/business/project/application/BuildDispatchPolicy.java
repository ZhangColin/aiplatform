package com.aieducenter.aiplatform.business.project.application;

/**
 * 构建派发规则缝（#332 额度/收费通用缝留位）：每次构建任务（首 Demo / 迭代 /
 * 重试）派发前的两个挂点——「规则说明位」（对本次构建的规则/计费说明）与
 * 「执行前确认位」（是否需用户执行前再次确认才派发）。
 *
 * <p>v1 缺省免费不限次：说明位空（不写「无限免费」承诺文案）、执行前确认位不激活
 * （直接派发，行为无感）——缝在结构在、行为无感；老板侧参数定了零结构改动（换
 * 实现策略即生效，{@link DefaultBuildDispatchPolicy} 是缺省空实现）。</p>
 */
public interface BuildDispatchPolicy {

    /**
     * 规则说明位：派发前对本次构建的规则/计费说明（拼入起跑上下文或呈现面）；
     * null/空 = 无说明（v1 缺省——不写「无限免费」承诺）。
     */
    default String ruleNotice(Long projectId) {
        return null;
    }

    /**
     * 执行前确认位：true = 需用户执行前再次确认才派发（v1 缺省 false = 不激活，
     * 直接派发）。激活后由派发侧据此拦下待用户确认，不改本接口形态。
     */
    default boolean requiresPreExecutionConfirmation(Long projectId) {
        return false;
    }
}
