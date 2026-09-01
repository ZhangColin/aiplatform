# SSE 事件清单（正本）

> 平台 SSE 事件的名册与信封正本，[ADR-0001](../adr/0001-swagger-contract-and-sse-channels.md) 定稿。前端消费以本文 + swagger 端点描述为准。
> 事件只让 UI「活」，不承担正确性：断线丢失可接受，状态以 REST 查询为准。
>
> **治理**：eventhub 是唯一 SSE 管道、双通道合一（平台通知 + 智能体流共用传输内核，通道语义与词汇表归 eventhub，智能体流帧由 base.agentscope 的 mapper 翻译填充）。新增顶层 type 必须先进本清单再上线（code review 检查）；代码侧只允许引用 `XxxEventTypes` 常量类，禁止字符串字面量散落。

## 信封（两通道统一）

```
event: event
id: {streamId}:{seq}          # 通知通道 streamId=projectId；agent 流通道 streamId=runId
data: {"type":"...","payload":{...},"ts":"2026-08-19T02:15:33.123Z"}
```

- SSE name 恒为 `event`，前端每通道一个 listener。
- `payload` 恒为对象，必带关联字段；**payload 内禁用 `type` 键名**。
- 心跳：每 15s 发注释行 `:ping`（不进 listener，仅保活）。
- 订阅：`GET /api/events?projectId=xxx` / `GET /api/agent-events?projectId=xxx&runId=xxx`；缺省 = 全量。过滤参数与 payload 关联字段同名，多参数 AND。

## 通道一：平台通知（`GET /api/events`）

平台状态变化的广播；编排层在副作用真实落定后发射；**永不补发**，重连后 REST 重查。

| type | payload 字段 | 示例 |
|---|---|---|
| `workspace-created` | `projectId` `projectName` `container` `projectType` | `{"projectId":"a1b2c3d4","projectName":"官网 demo","container":"aiplatform-dev-a1b2c3d4","projectType":"WEBSITE"}` |
| `preview-ready` | `projectId` `url` | `{"projectId":"a1b2c3d4","url":"http://localhost:30080"}` |
| `workspace-destroyed` | `projectId` | `{"projectId":"a1b2c3d4"}` |
| `document-updated` | `projectId` `documentType` | `{"projectId":"a1b2c3d4","documentType":"PRD"}`（工作区文档产物写出/修订落定后广播；v1 唯一写入方 = BA 的 savePrd（[#49](https://github.com/ZhangColin/aiplatform-server/issues/49)），每次执行必发；前端按**失效为主**模式消费——invalidate 文档域 + 对话区提示胶囊，内容经 `GET /api/projects/{id}/prd` 重拉。[#41](https://github.com/ZhangColin/aiplatform-server/issues/41) 新增） |
| `project-renamed` | `projectId` `projectName` | `{"projectId":"a1b2c3d4","projectName":"品牌官网"}`（异步取名落库成功顶替占位名后发射，取名线程 save 提交后即发——ADR-0001 时序同款；前端失效 projects 域重拉，停留中的页面上名字静默浮现。守卫不覆写（用户已改名/取名已完成）与取名失败保占位**均不发**——失败静默是既有设计，改名端点兜底。[#52](https://github.com/ZhangColin/aiplatform-server/issues/52) 新增） |
| `order-status-changed` | `projectId` `orderId` `status` `statusName` | `{"projectId":"a1b2c3d4","orderId":"900123","status":2,"statusName":"已报价"}`（订单状态变化后发射：下单（待报价）/首次报价（已报价）/取消/支付完成归档各发一次，改价不换状态不发；发射方 = order 上下文 `OrderEventTypes`，副作用真实落定后（支付路径在归档事务提交后）。前端消费 = toast（点击直达项目页）+ 失效订单/项目域重查；状态 code 为 OrderStatus Integer：1=待报价 2=已报价 4=已归档 5=已取消（3=已支付为事务内瞬态不发）。[#30](https://github.com/ZhangColin/aiplatform/issues/30) 新增） |

> **`workspace-created` 信号语义（[#61](https://github.com/ZhangColin/aiplatform-server/issues/61)）**：该信号表示「工作区记录已落库、容器后台置备中」，**不再是「容器就绪」**——创建即返回，docker 置备后台收敛到 ready/failed。前端以「记录存在」即可进对话；环境能力（跑代码/出 PRD）的就绪性以 REST 查询工作区 `status`（provisioning/ready/failed）为准，信号非权威（CONTEXT.md「平台通知」）。

## 通道二：agent 流（`GET /api/agent-events`）

一次智能体运行的增量过程流（LLM 交互过程流的细化）；payload 必带 `runId`，`sessionId` 会话建立后携带，`projectId` 由业务编排桥接注入。订阅过滤：`?runId=`（「看某个运行才挂」的常规姿势）/ `?projectId=`，可叠用（AND）。

通道是**带近期帧缓冲的热流**（[#56](https://github.com/ZhangColin/aiplatform-server/issues/56)，[#53](https://github.com/ZhangColin/aiplatform-server/issues/53)）：帧一经发射即进 per-channel 有界缓冲（零订阅时也进），默认最近 1000 帧、配置 `app.agent-stream.replay-depth`。**新连接**（无 `Last-Event-ID` 值——请求头缺席或空串）先收命中订阅过滤的最近缓冲帧（原事件 id，与实时帧同一 id 口径）、再无缝进实时流——起跑即死的 error 帧晚到订阅也可见，刷新后最近过程历史不消失；**断线重连**（浏览器自动携带非空 `Last-Event-ID`）不补发、不做 seq 续传，前端对齐维持 REST 重查兜底。缓冲为**单实例内存态**（重启即失），多实例化时需重估。

下表「payload 字段」列的关联字段 = `runId`（必带）+ `projectId`（业务编排桥接注入）。

两类事件：

- **平台事件**（封闭集合，注册制）：字段扁平，下表为准；代码侧引用 `AgentEventTypes` 常量（base.eventhub）；
- **引擎透传事件**（开放集合）：`data` 字段内为引擎 part 原样，下表列已知名型。

| type | 类别 | payload 字段 | 说明 |
|---|---|---|---|
| `task-start` | 平台 | `projectId` `runId` `prompt` `model` `engine` | 运行开始（runId 随任务响应同值返回） |
| `role-assigned` | 平台 | `projectId` `runId` `role` `roleLabel` `engine` | 角色卡分配（业务编排层发射） |
| `session-created` | 平台 | `projectId` `runId` `sessionId` `engine` | 会话建立（cat_agent_state 槽位首见发，跨重启不重发） |
| `error` | 平台 | `projectId` `runId` `message` | 运行失败 |
| `task-finish` | 平台 | `projectId` `runId` `sessionId` `engine` `finish` | 运行结束（finish = 引擎结煞语 end / exceed_max_iters 等） |
| `wait-raised` | 平台 | `projectId` `runId` `sessionId` `kind` `summary` `engineRef` `data` | 智能体挂起（kind=QUESTION=向用户提问 / PERMISSION=工具确认）；`data.questions` 为前端问答卡投影，`data.toolCalls`（待确认工具最小面）为答复通道回传面 |
| `task-retrying` | 平台 | `projectId` `runId` `attempt` `message` | 编码 run 自动重试（生成编排层发射，[#22](https://github.com/ZhangColin/aiplatform/issues/22) 新增）：一次尝试失败后、下一尝试下发前——`runId` 锚定失败的那次尝试（帧序 `error → task-retrying → 下一尝试 task-start`）；`message` 为用户侧话术「遇到问题，正在重试」；超限后不再发，末次 `error` 即终态（用户重新发起兜底） |
| `live-text` | 平台 | `projectId` `runId` `sessionId` `engine` `text` | 直播·智能体自述解说段（[#23](https://github.com/ZhangColin/aiplatform/issues/23) 新增，编码 run 专属）：`text` 为**完整段非增量**——服务端逐段成型（句读 / 文本块变 / 步骤与动作边界 / 长度上限切段），run 收口帧前出尾段 |
| `live-action` | 平台 | `projectId` `runId` `sessionId` `engine` `action` | 直播·动作摘要行：工具动作 → 人话模板（`write_file`/`edit_file` → 「正在编写【文件名】」、`command` → 「正在运行命令」）；读类工具与思考、diff 不播 |
| `live-step` | 平台 | `projectId` `runId` `sessionId` `engine` `step` | 直播·步骤段：`step` 为 run 内序号（1 起，模型调用边界），前端呈现为段间「第 N 步」分隔 |
| `text` | 引擎透传 | … + `data` | 最终文本 |
| `reasoning` | 引擎透传 | … + `data` | 思考增量 |
| `patch` | 引擎透传 | … + `data`（`path` `diff` `edits`） | 代码补丁 |
| `tool` | 引擎透传 | … + `data` | 工具调用 |
| `step-start` / `step-finish` | 引擎透传 | … + `data` | 步骤边界 |

> **智能体事件桥**：AgentScope HarnessAgent 的事件经单点映射表（base.agentscope 的 `AgentscopeEventMapper`）转本表帧型，走同一通道同一信封——`engine=agentscope`，帧序 `task-start → session-created（会话首见）→ 过程帧 → task-finish/error`。`text`/`reasoning` 帧为增量（`data.delta`，前端按序拼接）；`tool` 帧 `data` 为 `{toolCallId, toolName, phase: start|end}`，`step-*` 对应模型调用边界。挂起（`RequireUserConfirmEvent`，含 ask_user 提问）→ `wait-raised`（`kind` 按待确认工具判：`ask_user` = QUESTION，其余 = PERMISSION；QUESTION 的 `data.questions` 为前端问答卡投影：`[{header, question, multiple(ask_user 入参投影，缺省 false), custom(恒 true), options[{label}]}]`，`summary` 取问题文本）；挂起轮不发 `task-finish`（软终点，等答复续跑后收口），答复续跑归业务编排（问答作答通道，需求环落位 REST 面）——从项目侧事实重建恢复私货 + 挂起帧 `data.toolCalls` 重建 ConfirmResult；会话状态落 PostgreSQL（`cat_agent_state` 承载全部智能体会话），平台重启后按会话标识恢复续跑。

> **直播帧生产与消费口径**（[#23](https://github.com/ZhangColin/aiplatform/issues/23)）：`live-*` 三型是平台直播词汇表（成果区右侧栏面向客户的解说广播），由 base.agentscope 的 `AgentscopeLiveMapper`（直播映射表单点，`AgentCommand.live` 开关——仅编码 run 生成/修正打开）从同一 AgentScope 事件流逐段产出；前端直播侧栏**只消费 `live-*` + run 生命周期平台事件**，不耦合引擎透传事件格式。解说生产 = 智能体自述为主（CODER 角色卡过程解说条款）+ 工具动作人话模板兜底；思考与 diff 不播。直播随 run 生命周期呈现：run 开始侧栏自动展开、结束自动收起，收起即逝、无历史回看；刷新页面按 `?projectId=` 新连接补缓冲帧续看进行中 run（replay 只补当前 run 断线期间的帧——直播帧与过程帧同一缓冲同一口径）。

> 字段表为初版，随片 2 / 片 5 spec 细化；信封与名册的任何变更即改本文。

## 前端通用模块（约定）

门户布局级挂通知通道实例（常开），项目页挂智能体流实例（看运行才挂）；模块统一管连接建立、心跳透明、自动重连、重连后 REST 重查钩子、按 type 分发回调——页面只声明关心的 type，不重复写连接逻辑。
