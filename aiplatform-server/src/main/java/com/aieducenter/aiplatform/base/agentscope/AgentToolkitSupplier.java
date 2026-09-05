package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.tool.Toolkit;

/**
 * 智能体工具集 SPI（平台四职责之「补 SPI」）：智能体资产（工具集）归业务侧——
 * 各智能体配置（{@code agentKey}）的差异只在资产与工具集，base/agentscope 只供
 * 内核不供工具。业务侧实现本接口，按<b>配置</b>发放工具集（同一工作区上主智能体
 * 与 run 执行体拿不同的面）；配置语境（业务侧配置的稳定键，底座不解释）为空或
 * 未知时通常空集（本地兜底工作区无项目语境，同空集）；工厂构建 agent 时取用
 * （同规格缓存内只取一次）。
 */
@FunctionalInterface
public interface AgentToolkitSupplier {

    /**
     * 给定配置键与工作区形态的工具集（每次调用返回独立实例——Toolkit 非线程
     * 安全，调用方不复用返回值）。
     */
    Toolkit toolkitFor(String agentKey, AgentWorkspace workspace);
}
