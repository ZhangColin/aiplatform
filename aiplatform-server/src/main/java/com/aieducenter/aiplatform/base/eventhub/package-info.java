/**
 * EventHub Context（base.eventhub）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>SSE 传输内核：emitter 管理 / 心跳 / 过滤订阅 / 信封与 id 分配 / fire-and-forget</li>
 *   <li>唯一 SSE 管道、双通道合一：平台通知（GET /api/events）+ 智能体流
 *       （GET /api/agent-events）——通道语义与事件词汇表（AgentEventTypes）在此，
 *       agentscope 基础设施的事件 mapper 翻译填充智能体流帧</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>纯技术广播组件，内存单实例起步；两通道共用传输内核，通道语义（路径、关联字段、
 * streamId 取值）归应用层。无表、无错误码前缀（事件名册见 docs/spec/SSE事件清单.md；
 * 信封与通道语义见 ADR-0001）。base 区不发通知——由业务编排层在副作用落定后调
 * publish 发射。将来遛熟后传输内核提取为 cartisan-boot 模块（拟名 cartisan-sse），
 * 应用侧只留通道语义与名册。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：信封值对象（EventEnvelope）、事件 id（SseEventId）、智能体流帧（AgentEvent/AgentEventTypes），零框架依赖</li>
 *   <li>application - 应用层：PlatformNotificationAppService（通知通道语义：projectId 关联）+ AgentStreamAppService（智能体流通道语义：runId 关联 + 近期帧重放 + runId 生成）</li>
 *   <li>infrastructure - 传输内核 SseChannelHub（按名泛化通道）</li>
 *   <li>endpoints - 北向接口：EventsController（GET /api/events）、AgentEventsController（GET /api/agent-events）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "EventHub", subDomain = SubDomain.GENERIC)
package com.aieducenter.aiplatform.base.eventhub;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
