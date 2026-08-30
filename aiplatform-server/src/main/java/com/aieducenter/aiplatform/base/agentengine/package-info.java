/**
 * AgentEngine Context（base.agentengine）：对话智能体（agentscope）的会话登记、
 * 等待点（问答/权限挂起，agt_pending_waits）与 agent 流通道
 * （GET /api/agent-events，runId 关联的过程事件）。
 *
 * <h3>限界上下文</h3>
 * <p>底座智能体接入的等待/会话/流设施，不含角色概念（角色卡在 business.project）。
 * 表前缀 {@code agt_}，错误码前缀 {@code AGT_}。多引擎适配层（适配器端口/注册表/
 * 引擎配置）已随平台单栈化删除；问答卡的作答通道随需求环（#19）落位。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：等待点答复端口（WaitResponder）、等待点/会话聚合与事件模型</li>
 *   <li>application - 应用层：等待点登记/答复、会话登记、agent 流通道语义（AgentStreamAppService）</li>
 *   <li>infrastructure - 基础设施层：工作区句柄取用</li>
 *   <li>endpoints - 北向接口层：agent 流 SSE 通道</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "AgentEngine", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.agentengine;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
