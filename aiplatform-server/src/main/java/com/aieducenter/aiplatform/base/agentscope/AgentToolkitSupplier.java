package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.tool.Toolkit;

/**
 * 智能体工具集 SPI（平台四职责之「补 SPI」）：智能体资产（工具集）归业务侧——
 * BA 与编码智能体的差异只在资产与工具集，base/agentscope 只供内核不供工具。
 * 业务侧实现本接口，按工作区形态给出该会话可用的工具集（本地兜底工作区无项目
 * 语境，通常空集）；工厂构建 agent 时取用（同规格缓存内只取一次）。
 */
@FunctionalInterface
public interface AgentToolkitSupplier {

    /**
     * 给定工作区形态的工具集（每次调用返回独立实例——Toolkit 非线程安全，
     * 调用方不复用返回值）。
     */
    Toolkit toolkitFor(AgentWorkspace workspace);
}
