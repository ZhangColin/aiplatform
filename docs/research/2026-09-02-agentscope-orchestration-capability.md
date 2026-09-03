# 调研快照：agentscope-java 编排能力 × SAA graph × subagent（2026-09-02）

> 结论关联：ADR 0004（编排代码化·判定下放）、[派发机制设计 v1](../design/dispatch-orchestration-v1.md)。
> 当时版本：平台依赖 `io.agentscope:agentscope-harness:2.0.1`；本地源码仓 `/Users/zhangcolin/workspace/agentscope-java` 在 v2.0.2+73 commits。

## 结论速览

1. **agentscope-java 2.0.1 无图编排原语**，缺整层图引擎（节点/条件边/图状态/checkpointer/确定性中断）。1.x 曾有 `io.agentscope.core.pipeline.{SequentialPipeline, FanoutPipeline, MsgHub}`，2.0 重构（commit `cc3b2eec`，2026-06-01）整体删除——方向是做减法，官方对图编排的历来答案是外接（v1 文档即指向 Spring AI Alibaba StateGraph，v2 干脆删了 Multi-Agent 章节）。
2. **subagent 是父 LLM 工具调用式派发**（`agent_spawn` 等），不适用于平台级派发骨架（ADR 0004 否决的形态）；适用于**运行内委托**（见 §4）。
3. **SAA graph 能力对口但不引入 v1**：拖 spring-ai 全家（双 LLM 栈）、双状态语义、Boot 3.4 vs 3.5 兼容验证；v1 三节点线性拓扑 Java 代码直接可表达。触发信号见 ADR 0004。
4. **平台现状即是正确分层**：外层 Java 编排（IterationAppService 一族）+ HarnessAgent 单智能体执行器；会话检查点自落库（`PostgresAgentStateStore` → `cat_agent_state`），问答卡挂起/续跑是平台自己拼的（`RequireUserConfirmEvent` + `ConfirmResult` resume）。

## 1. 本地事实（jar 解包 + 源码仓双确认）

- 坐标：`aiplatform-server/pom.xml:24,138-148`（agentscope 2.0.1 + openai 扩展）。HarnessAgent 进程内嵌入，非独立服务。
- **agentscope-core-2.0.1 包清单**：agent/message/event/model/state/hook/skill/tool/permission/middleware/memory/interruption/rag/tracing/shutdown——无 pipeline/graph/workflow/dag/router/branch 类。关键词命中仅 `ReActAgent`、`AgentState(AgentStateStore)`、`InterruptControl`——全是单智能体循环原语。
- **agentscope-harness-2.0.1**：HarnessAgent + middleware/subagent/sandbox/skill/gateway/workspace；唯一 Router 是 `gateway/channel/ChannelRouter`（渠道路由，与图无关）。
- v2 源码仓 HEAD 多出 `team/`（Agent Teams：TeamClient 任务板+邮箱）与 `coordination/`（commit `3e386357`，2026-08-04），**未进任何 release**——升级 2.0.2 也拿不到；是 Leader/团队编排的将来评估对象。
- 平台侧编排证据：`AgentscopeAgentClient.converse()/runTurn()/resume()`（:108/:237-267/:137-161）、`IterationAppService` 排队合并 while 状态机（:128-163）、`CoderRunAttempts` 重试环（:59-96）、`AgentSessionExecutor` sessionId 哈希 stripe 串行化、`AgentscopeHarnessAgentFactory:100-107` 对项目工作区 `disableSubagents()/disableMemoryHooks()`（交付物防脏写）。

## 2. subagent 机制深挖

- **派发形态**：父 LLM 在推理循环里调 `agent_spawn / agent_send / task_output / wait_async_results / task_cancel`（`SubagentsMiddleware.java:55-75` 挂工具并把用法写进系统提示 :92-172）。编程面只有声明（`builder.subagent(...)` / `workspace/subagents/*.md` / DynamicSubagentsMiddleware）与底层调用器 `DefaultAgentManager`——后者 javadoc 自述「agent-internal 层，无会话注册、无 run 追踪」（:37-45），**不承诺的内部 API**。受支持的编排路径只有 LLM 派发。
- **状态/交接**：prompt 进 → 单条结果 Msg 出（文本协议 `agent_key:/agent_id:/session_id:` 前缀行，`AgentSpawnTool.java:293-297`）；无共享状态对象；工作区 ISOLATED（默认，状态桶 `{name}[@{parentSessionId}][#{userId}]`）或 SHARED（`WorkspaceMode.java:21-31`）。
- **无机器可读判定通道**：spawn 结果回父是自由文本；要结构化得靠 agent 级 structured output（`ReActAgent.call(msgs, Class, ctx)`）或自己解析。
- **时限与生命周期**：同步 spawn 默认 30s、上限 600s，超时自动转后台不丢失；后台任务完成在父下一次推理前以 system-reminder 回推；同步 fan-out/fan-in（Toolkit parallel=true）；**无重试**；排队仅内存/工作区 TaskRepository。
- **事件流（最贴平台需求的部分）**：`SubagentEventBus`（core/agent/）把子代理事件实时转发进父事件流并带 `EventSource`（agentId/sessionId 路径）标签；`expose_to_user=true` + `sendToSubagentStream` 可让客户端直连子代理流（示例 `SubagentSendDirectlyExample`）——SSE 桥理论上能按 source 区分帧归属，直播可分角色播。

## 3. SAA graph 对照（网上，附 URL）

- 坐标 `com.alibaba.cloud.ai:spring-ai-alibaba-graph-core`，GA 1.1.2.3；JDK17/Boot 3.5.x/Spring AI 1.1.2；约 10.8k stars、维护活跃。API 与 LangGraph 一一对应（本机 jar 1.1.2.2 实测）：`addNode`（NodeAction/子图/Command 多路跳转）、`addConditionalEdges`、`OverAllState`+`KeyStrategy`（Replace/Append/Merge）、checkpointer 10 种（Memory/JDBC/Postgres/Redis/…）、`interruptsBefore/After`+`HumanFeedbackDispatcher`+time-travel、`CompiledGraph.stream` 返回 Reactor Flux、`maxIterations` 防环。
- 官方分层背书：定位博客「以 Agentic 为核心用 AgentScope-Java，基于 Workflow 构建用 SAA」（https://java2ai.com/blog/saa-agentscope-announcement ）；官方 starter `spring-ai-alibaba-starter-agentscope`（依赖 agentscope-core 而非 harness）；混合示例 `examples/agentscope/handoffs`（ReActAgent 包成 AgentScopeAgent 挂 StateGraph 节点）。
- 代价实测：graph-core 拖 spring-ai-client-chat/deepseek/zhipuai/rag/retry + MCP SDK + redisson + 多数据库驱动 + otel（双 LLM 栈）；平台 Boot 3.4.0 vs 官方线 3.5.8 需兼容验证；`AgentScopeAgent` 适配器绑 core 1.x 形态，接 harness 要自写 NodeAction 适配；图检查点与平台 `cat_agent_state` 双状态语义并行。
- 自称「LangGraph 的 Java 实现版本」（https://java2ai.com/blog/spring-ai-alibaba-graph-preview ）；langgraph4j 维护者称 SAA「embeds LangGraph4j as its underlying core」（https://github.com/langgraph4j/langgraph4j/issues/363 ）——API 同构但别假设兼容超集。
- 文档：https://java2ai.com/docs/frameworks/graph-core/quick-start 、…/core/persistence 、…/examples/human-in-the-loop 、…/examples/parallel-branch 、…/examples/time-travel

## 4. subagent 适用场景分析（我们平台视角）

**它解决的问题：一个智能体干活时"人手不够"——运行内委托，不是平台级派发。**

适合的特征（全部满足才考虑）：
1. 派发判断需要语义理解、且**在任务进行中动态发生**（不是流程开始就能定的路由）；
2. 子任务自包含、结果以文本被父消化（父负责汇总对用户负责）；
3. 短任务（分钟级；600s 同步上限，长了转后台）；
4. 可并行 fan-out（同轮多 spawn）；
5. 不承载用户侧链路的确定性（无重试、无机器可读契约、排队内存级）。

我们平台的具体适配点（将来）：
- **CODER 运行内委托**：spawn reviewer 审刚写的代码、spawn test-runner——事件经 SubagentEventBus 带源标签入现有 SSE 桥，直播可分角色播（expose_to_user 直连子代理流）。
- **助理答复杂咨询**时可 spawn 检索子代理。
- 障碍：项目工作区 `disableSubagents()`（Factory:105，交付物防脏写）——CODER 用 subagent 需先解决子代理写哪（ISOLATED 桶 vs 交付工作区）；无重试，长任务要外层兜底。

一句话判据：**subagent 管"一个智能体内部雇专家"，派发管"平台让哪个智能体上场"**——两者正交，我们 v1 只需要后者（ADR 0004），前者的扩展点已备。
