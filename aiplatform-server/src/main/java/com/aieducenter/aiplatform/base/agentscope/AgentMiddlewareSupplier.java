package com.aieducenter.aiplatform.base.agentscope;

import java.util.List;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 平台智能体中间件 SPI（镜像 {@link AgentToolkitSupplier}／{@link
 * AgentSkillRepositorySupplier}——中间件是横切装配资产，与工具集、技能仓库同为
 * 业务侧决定挂什么）：base/agentscope 只供内核不供观测/装配策略，业务侧实现本
 * 接口发放中间件。返回空集＝该配置不挂任何平台中间件（框架自有中间件不受
 * 影响——它们由 harness 构建内部装配）。
 *
 * <p>挂载语义＝随 {@code HarnessAgent.Builder.middleware} 进中间件链，主构建
 * 与子智能体工厂两处同挂（框架对 {@code HarnessRuntimeMiddleware} 不随实例
 * 拷贝传播，子级须显式再挂——平台子智能体工厂同源接线）。中间件应无状态或
 * 长生命周期安全（单实例被多 agent 共享）。</p>
 */
@FunctionalInterface
public interface AgentMiddlewareSupplier {

    /**
     * 给定配置键与工作区形态的平台中间件清单（返回空集＝不挂载）。实现通常
     * 只看配置键；{@code workspace} 供工作区形态判据的实现使用（可忽略）。
     */
    List<MiddlewareBase> middlewaresFor(String agentKey, AgentWorkspace workspace);
}
