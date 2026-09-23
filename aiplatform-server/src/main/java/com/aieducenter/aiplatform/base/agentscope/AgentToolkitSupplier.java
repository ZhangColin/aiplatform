package com.aieducenter.aiplatform.base.agentscope;

import io.agentscope.core.tool.Toolkit;

/**
 * 智能体工具集 SPI（平台四职责之「补 SPI」）：智能体资产（工具集）归业务侧——
 * 各智能体配置（{@code agentKey}）的差异只在资产与工具集，base/agentscope 只供
 * 内核不供工具。业务侧实现本接口，按<b>配置</b>发放工具集（同一工作区上主智能体
 * 与 run 执行体拿不同的面）；配置语境（业务侧配置的稳定键，底座不解释）为空或
 * 未知时通常空集（本地兜底工作区无项目语境，同空集）；工厂构建 agent 时取用
 * （同规格缓存内只取一次）。
 *
 * <p>{@code toolSpec} 是工具面规格串（#252 增强工具开关的装配腿）：业务侧命令
 * 构建处从运营配置解析编码、随命令传入，底座不解释——但两处消费：工厂实例缓存
 * 键（规格变＝实例变，开关变更下一轮命令构建即新装配）与本接口装配判据（实现
 * 按规格发放增强工具）。null/空＝无规格语境（一次性判定等），实现自行定缺省
 * 装配（平台实现按全开缺省）。</p>
 */
@FunctionalInterface
public interface AgentToolkitSupplier {

    /**
     * 给定配置键、工作区形态与工具面规格串的工具集（每次调用返回独立实例——
     * Toolkit 非线程安全，调用方不复用返回值）。
     */
    Toolkit toolkitFor(String agentKey, AgentWorkspace workspace, String toolSpec);
}
