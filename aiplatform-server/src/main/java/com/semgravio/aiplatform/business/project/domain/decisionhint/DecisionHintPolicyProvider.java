package com.semgravio.aiplatform.business.project.domain.decisionhint;

/**
 * 决策时注入策略供应腿（#437，SPI 在 domain、实现在 application/infrastructure）：
 * 中间件每迭代调用（动态查库——运营面改档下一次迭代即生效、不发版、进行中 run
 * 不定格）。返回 null／抛异常由中间件 fail-open 降级（透传不注入）。
 */
@FunctionalInterface
public interface DecisionHintPolicyProvider {

    /**
     * 给定配置键的生效注入配置（enabled=false＝总开关关）。
     */
    DecisionHintConfig configFor(String agentKey);
}
