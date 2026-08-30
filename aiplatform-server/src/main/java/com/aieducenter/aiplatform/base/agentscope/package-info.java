/**
 * AgentScope 薄 infra 包（base.agentscope）——非 BC、无 domain model：平台唯一的
 * 智能体内核接线（平台不建任何智能体层，BA 与编码智能体差异只在资产与工具集）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>{@link com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient 链路}：
 *       HarnessAgent 单轮对话（converse）与挂起续跑（resume，问答答复通道的 mechanics）；
 *       模型串解析（provider:modelId 白名单，暂仅 deepseek）、工作区分型
 *       （本地 / 项目 dev 容器 docker exec 文件面）</li>
 *   <li>事件桥：AgentScope 类型化事件 → 平台智能体流帧（词汇表在 eventhub 的
 *       AgentEventTypes，映射表单点 AgentscopeEventMapper）</li>
 *   <li>会话恢复：AgentState 落 PostgreSQL（cat_agent_state 承载全部智能体会话，
 *       (userId, sessionId) 槽位）——平台重启后同一会话标识恢复续跑</li>
 *   <li>对话级用量埋点（模型调用事件 → UsageEvent，run 结束上报恰一条）</li>
 *   <li>会话级任务执行器（同会话一次一轮串行、跨会话并行）</li>
 *   <li>工具集 SPI（AgentToolkitSupplier）：智能体资产（ask_user / savePrd 等业务
 *       工具）归业务侧注入，本包不供工具</li>
 * </ul>
 *
 * <h3>边界</h3>
 * <p>纯基础设施：无表（cat_agent_state 归 AgentScope 状态存续）、无 REST 面、无错误码
 * 前缀（失败以 error 帧表达，异常上抛归调用方吞或转）。业务编排（business.project）
 * 直调本包——编排缝极薄，无中间端口层。</p>
 */
package com.aieducenter.aiplatform.base.agentscope;
