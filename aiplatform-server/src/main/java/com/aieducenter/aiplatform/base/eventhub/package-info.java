/**
 * EventHub Context（base.eventhub）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>SSE 传输内核：emitter 管理 / 心跳 / 过滤订阅 / 信封与 id 分配 / fire-and-forget</li>
 *   <li>唯一 SSE 管道、单端点单流（GET /api/events）：平台通知族 + 智能体事件族
 *       同流承载——通道语义与事件词汇表（AgentEventTypes）在此，agentscope
 *       基础设施的事件 mapper 翻译填充智能体事件</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>纯技术广播组件，内存单实例起步；合并通道不合并语义（智能体事件族带近期事件
 * 重放缓冲、通知族不进缓冲不补发），通道语义（路径、关联字段、streamId 取值）
 * 归应用层。无表、无错误码前缀（事件名册见 docs/spec/SSE事件清单.md；信封与通道
 * 语义见 ADR-0001）。base 区不发通知——由业务编排层在副作用落定后调 publish
 * 发射。将来遛熟后传输内核提取为 cartisan-boot 模块（拟名 cartisan-sse），
 * 应用侧只留通道语义与名册。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：信封值对象（EventEnvelope）、事件 id（SseEventId）、智能体事件（AgentEvent/AgentEventTypes），零框架依赖</li>
 *   <li>application - 应用层：EventsAppService（单通道两族语义：通知族 projectId 关联不补发 + 智能体事件族 runId 关联近期事件重放 + runId 生成）</li>
 *   <li>infrastructure - 传输内核 SseChannelHub（按名泛化通道）</li>
 *   <li>endpoints - 北向接口：EventsController（GET /api/events，单端点单流）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "EventHub", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.eventhub;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
