# SSE 事件清单（正本）

> 平台 SSE 事件的名册与信封正本，[ADR-0001](../adr/0001-swagger-contract-and-sse-channels.md) 定稿；通道二自 #77 起以**消息部件（parts）契约**为正本。前端消费以本文 + swagger 端点描述为准。
> 事件只让 UI「活」，不承担正确性：断线丢失可接受，状态以 REST 查询为准。
>
> **治理**：eventhub 是唯一 SSE 管道、双通道合一（平台通知 + 智能体事件共用传输内核，通道语义与词汇表归 eventhub，智能体事件由 base.agentscope 的 mapper 翻译填充）。新增顶层 type 必须先进本清单再上线（code review 检查）；代码侧只允许引用 `XxxEventTypes` 常量类，禁止字符串字面量散落。
>
> **双发射过渡期（#77–#82）**：通道二正处于新旧并行——parts 部件事件族（正本）与旧族事件（[过渡期旧族](#过渡期并行发射的旧族)）同时发射、语义各自不变；前端迁移（#81）完成后由收缩票（#82）全量退役旧族。两侧契约均以本文为唯一正本。

## 信封（两通道统一）

```
event: event
id: {streamId}:{seq}          # 通知通道 streamId=projectId；智能体事件通道 streamId=runId
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
| `preview-updated` | `projectId` | `{"projectId":"a1b2c3d4"}`（预览内容前移一步的刷新通知（[#49](https://github.com/ZhangColin/aiplatform/issues/49) 新增，渐进预览·逐修改刷新）：编码 run 每完成一次完整修改（刷新单元 = 直播步骤边界 `live-step`，`step≥2` 才算——第 1 帧是起跑边界尚无完整修改；最后一步完成由 `run-finish` 收口重挂兜底）→ 平台侧探活工作区应用端口（8081，与生成收口核验同判据）——**探活通过才发射**，未通过不发射（前端保最后好状态）。前端收帧节流重载预览（秒级最小间隔，连续通知不闪烁）；不携带 `url`（预览地址经 REST 探活取得且不变）。生成与修正同一口径；通知通道不补发——漏帧由下一步或收口刷新自然兜底） |
| `document-updated` | `projectId` `documentType` | `{"projectId":"a1b2c3d4","documentType":"PRD"}`（工作区文档产物写出/修订落定后广播；v1 唯一写入方 = BA 的 savePrd（[#49](https://github.com/ZhangColin/aiplatform-server/issues/49)），每次执行必发；前端按**失效为主**模式消费——invalidate 文档域 + 对话区提示胶囊，内容经 `GET /api/projects/{id}/prd` 重拉。[#41](https://github.com/ZhangColin/aiplatform-server/issues/41) 新增） |
| `project-renamed` | `projectId` `projectName` | `{"projectId":"a1b2c3d4","projectName":"品牌官网"}`（异步取名落库成功顶替占位名后发射，取名线程 save 提交后即发——ADR-0001 时序同款；前端失效 projects 域重拉，停留中的页面上名字静默浮现。守卫不覆写（用户已改名/取名已完成）与取名失败保占位**均不发**——失败静默是既有设计，改名端点兜底。[#52](https://github.com/ZhangColin/aiplatform-server/issues/52) 新增） |
| `order-status-changed` | `projectId` `orderId` `status` `statusName` | `{"projectId":"a1b2c3d4","orderId":"900123","status":2,"statusName":"已报价"}`（订单状态变化后发射：下单（待报价）/首次报价（已报价）/取消/支付完成（已支付）/归档（已归档）各发一次——支付与归档分两发，归档失败只发「已支付」，改价不换状态不发；发射方 = order 上下文 `OrderEventTypes`，副作用真实落定后（支付路径在支付/归档各自事务提交后）。前端消费 = toast（点击直达项目页）+ 失效订单/项目域重查；状态 code 为 OrderStatus Integer：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消（已支付为真实态，支付落定后发）。[#30](https://github.com/ZhangColin/aiplatform/issues/30) 新增，[#39](https://github.com/ZhangColin/aiplatform/issues/39) 支付原子化修订） |

> **`workspace-created` 信号语义（[#61](https://github.com/ZhangColin/aiplatform-server/issues/61)）**：该信号表示「工作区记录已落库、容器后台置备中」，**不再是「容器就绪」**——创建即返回，docker 置备后台收敛到 ready/failed。前端以「记录存在」即可进对话；环境能力（跑代码/出 PRD）的就绪性以 REST 查询工作区 `status`（provisioning/ready/failed）为准，信号非权威（CONTEXT.md「平台通知」）。

## 通道二：智能体事件（`GET /api/agent-events`）

一次智能体运行的增量过程流；payload 必带 `runId`，`sessionId` 会话建立后携带，`projectId` 由业务编排桥接注入。订阅过滤：`?runId=`（「看某个运行才挂」的常规姿势）/ `?projectId=`，可叠用（AND）。

通道是**带近期事件缓冲的热流**（[#56](https://github.com/ZhangColin/aiplatform-server/issues/56)，[#53](https://github.com/ZhangColin/aiplatform-server/issues/53)）：事件一经发射即进 per-channel 有界缓冲（零订阅时也进），默认最近 1000 条、配置 `app.agent-stream.replay-depth`。**新连接**（无 `Last-Event-ID` 值——请求头缺席或空串）先收命中订阅过滤的最近缓冲事件（原事件 id，与实时事件同一 id 口径）、再无缝进实时流——起跑即死的 error 事件晚到订阅也可见，刷新后最近过程历史不消失；**断线重连**（浏览器自动携带非空 `Last-Event-ID`）不补发、不做 seq 续传，前端对齐维持 REST 重查兜底。缓冲为**单实例内存态**（重启即失），多实例化时需重估。

### 消息模型（parts 契约）

**消息 = 有序部件集合（parts）**（六源行业同构；词根基准 = agentscope 原生 part 族——文本/推理/工具调用请求/文件，mapper 保持薄翻译层，不强套某家词表）。run 进行中 = 对话区内一条**生长中的工作消息**（解说文本部件 + 工具动作部件，带状态；步骤分组分段），run 收口 = 消息定格。部件事件（`part-*`）由 base.agentscope 的部件映射表（`AgentscopePartsMapper`）从同一 AgentScope 事件流产出，全事件流恒挂（不限编码 run）；思考与代码补丁不进部件（思考与代码流不播——CONTEXT.md「智能体事件」）。

下表「payload 字段」列的关联字段 = `runId`（必带）+ `projectId`（业务编排桥接注入）+ `sessionId`（会话建立后携带）。三类事件：

- **生命周期事件**（平台封闭集合，注册制）：run 级开场/挂起/收口；代码侧引用 `AgentEventTypes` 常量（base.eventhub）；
- **消息部件事件**（`part-*`，部件契约）：生长中的工作消息的部件增量；
- **引擎透传事件**（开放集合）：`data` 字段内为引擎 part 原样，下表列已知名型。

#### 生命周期事件

| type | payload 字段 | 说明 |
|---|---|---|
| `run-start` | `projectId` `runId` `prompt` `model` `engine` `role`（可空） | 运行开始（runId 随 run 响应同值返回）。**引擎信息归一**（#77）：engine/model 之外并入角色键 `role`（业务侧角色卡的枚举名，如 CODER；无角色语境的一次性调用不携带）——为 role-assigned 退役做准备（过渡期旧族照发，见下） |
| `error` | `projectId` `runId` `message` | 运行失败（逐次尝试的过程事实；run 级终态见 run-failed） |
| `run-finish` | `projectId` `runId` `sessionId` `engine` `finish` | 运行结束（finish = 引擎结煞语 end / exceed_max_iters 等）；挂起轮不发（软终点，等答复续跑后收口） |
| `question-raised` | `projectId` `runId` `sessionId` `kind` `summary` `engineRef` `data` | 智能体挂起（kind=QUESTION=向用户提问 / PERMISSION=工具确认）；`data.questions` 为前端问答卡投影，`data.toolCalls`（待确认工具最小面）为答复通道回传面。PERMISSION 拆独立事件（`permission-required`）与作答通道分家归后续票 |
| `run-failed` | `projectId` `runId` | 编码 run 重试超限·终态收口（[#56](https://github.com/ZhangColin/aiplatform/issues/56)）：轨道层在真终态落定点发射——修正轨道与终态账（恢复出口 `restartFixRun` 的重派依据）同事实点，排队合并续派的中途超限不是终态、不发；生成轨道超限即终态。`runId` 锚定末次失败的尝试（事件序 `error(末次) → run-failed`）。前端恢复出口只认本事件——run 失败为唯一失败终态 |
| `guide-reply` | `projectId` `runId` `prompt` `label` `text` | 兜底轻引导回复（[#47](https://github.com/ZhangColin/aiplatform/issues/47) 入口三分类的兜底分支）：非意见非咨询输入的平台侧定型引导文案——零产物路径（不起任何智能体 run，本事件即该次派发的全部）。`runId` 为派发锚；`prompt` 为锚定的用户输入（重放重建对话面用）；`label` 为呈现标签（「平台」）；`text` 为引导文案 |

#### 消息部件事件（`part-*`）

| type | payload 字段 | 说明 |
|---|---|---|
| `part-text` | `projectId` `runId` `sessionId` `engine` `text` | 解说文本部件：`text` 为**完整段非增量**——服务端逐段成型（句读 / 文本块变 / 步骤与动作边界 / 长度上限切段），run 收口事件前出尾段；段切分与直播口径同一内核 |
| `part-action` | `projectId` `runId` `sessionId` `engine` `toolCallId` `toolName` `state` `label` | 工具动作部件（动作卡）：**开始/进行中/完成/失败全生命周期**——动作一开始即出事件（#77 补动作开始：现状 live-action 仅调用落定才出），同一动作以 `toolCallId` 锚定跨状态更新。`state` ∈ `started`（模型发起工具调用，参数在途）/ `running`（参数落定、工具执行中）/ `completed`（结果成功）/ `failed`（结果出错/被拒/中断——动作层状态，run 层唯一失败终态仍是 run-failed）。`label` = 动作对象短语（人话行，无时态——时态由 state 表达；started 时参数在途为通用对象如「编写【代码文件】」，running 起解析参数为具体对象如「编写【订单管理】」，终态复述不闪换）。播报工具为封闭表：write_file / edit_file / command——读类工具不进部件（对客户是噪音） |
| `part-step` | `projectId` `runId` `sessionId` `engine` `step` | 步骤分组部件：`step` 为流段内序号（1 起，模型调用边界），呈现为「第 N 步」分组头；问答续跑为新流段重新起算 |
| `part-attachment` | —— | **契约预留**（无生产方，[#97](https://github.com/ZhangColin/aiplatform/issues/97) 圈注落地时启用）：消息附件部件——圈注锚随消息发送的载荷位，schema 见[下节](#消息附件部件锚载荷-schema预留97) |

#### 消息附件部件锚载荷 schema（预留，#97）

圈注（#73 决议）三能力——点选锚定 / 画笔圈选 / 区块评论——的条目收进发送框附件区，随下一句自然语言发送；载荷只定要点：**结构化定位 + 标注类型 + 可选评语**（不走截图识图——模糊锚定指错位置反噬信任）。锚为注入脚本回传的结构化 DOM 锚（选择器/文本引用），字段 schema 随本票定形：

```
attachmentType: "annotation"            # 附件种类（预留多类：圈注为首个；后续上传物料等另立）
annotation:
  kind: "select" | "circle" | "comment" # 标注类型：点选锚定 / 画笔圈选 / 区块评论
  anchor:                               # 结构化定位（预览与前端跨源，postMessage 回传）
    selector: "CSS 选择器"               # 点选/评论：唯一指向目标元素
    text: "目标元素可见文本片段"          # 辅助定位与可读性（同文本多见时以选择器为准）
    region: { x, y, width, height }     # 圈选：页面级矩形区域（circle 专用，选择器可缺省）
  note: "可选评语"                       # 发送前可删改；多条圈注 = 多个附件部件可叠加
```

- 指认是对话输入的**增强不是替代**：附件部件与自然语言同句发送、被主智能体精确读取（结构化定位而非猜图）；
- 生产方与消费面（消息回显/落库随对话史）随 #97 落地，本节只锁载荷 schema。

#### 引擎透传事件（开放集合）

| type | payload 字段 | 说明 |
|---|---|---|
| `text` | … + `data`（`delta` `blockId`） | 文本增量（前端按序拼接） |
| `reasoning` | … + `data`（`delta` `blockId`） | 思考增量 |
| `patch` | … + `data`（`path` `diff` `edits`） | 代码补丁（服务端现状不产出——名型占位） |
| `tool` | … + `data`（`toolCallId` `toolName` `phase: start|end`） | 工具调用（引擎原生粒度；用户面动作呈现归 part-action） |
| `step-start` / `step-finish` | … + `data`（`replyId`） | 步骤边界（模型调用边界；用户面分组呈现归 part-step） |

> **智能体事件桥**：AgentScope HarnessAgent 的事件经映射表翻译走同一通道同一信封——`engine=agentscope`，事件序 `run-start → run-created（旧族·会话首见）→ 过程事件 → run-finish/error`。透传映射表单点 = `AgentscopeEventMapper`；部件映射表单点 = `AgentscopePartsMapper`（解说切段与动作行的生产内核由 `NarrationSegments` / `ToolActionLines` 共用）。挂起（`RequireUserConfirmEvent`，含 ask_user 提问）→ `question-raised`（`kind` 按待确认工具判：`ask_user` = QUESTION，其余 = PERMISSION；QUESTION 的 `data.questions` 为前端问答卡投影：`[{header, question, multiple, custom(恒 true), options[{label}]}]`，`summary` 取问题文本）；挂起轮不发 `run-finish`，答复续跑归业务编排（问答作答通道，需求环落位 REST 面）——从项目侧事实重建恢复私货 + 挂起事件 `data.toolCalls` 重建 ConfirmResult；会话状态落 PostgreSQL（`cat_agent_state` 承载全部智能体会话），平台重启后按会话标识恢复续跑。

### 过渡期并行发射的旧族

双发射过渡期（#77 起）继续发射的旧族事件——**语义与 #77 前完全不变，前端既有行为零变化**；前端迁移（#81 生长中的工作消息）完成后由 #82 全量退役（一次动完，不设替代阶段事件）：

| type | 退役归宿（#82） | 过渡期语义（不变） |
|---|---|---|
| `role-assigned` | 退役（引擎信息已并入 run-start） | `projectId` `runId` `role` `roleLabel` `engine`——角色卡分配（业务编排层发射；role ∈ {BA, CODER, ASSISTANT}，`roleLabel` 为用户侧呈现标签，前端指令区角色标签随本事件呈现）。事件序 `role-assigned → run-start` |
| `run-created` | 退役（内部会话信号不外泄，sessionId 随后续事件携带） | `projectId` `runId` `sessionId` `engine`——运行创建（cat_agent_state 会话槽位首见发，跨重启不重发） |
| `run-retrying` | 退役 + 静默重试（run-failed 为唯一失败终态） | `projectId` `runId` `attempt` `message`——编码 run 自动重试（生成 [#22](https://github.com/ZhangColin/aiplatform/issues/22) / 修正 [#26](https://github.com/ZhangColin/aiplatform/issues/26)）：一次尝试失败后、下一尝试下发前；`message` 为用户侧话术「遇到问题，正在重试」 |
| `fix-unchanged` | 退役（判定结果由收口事件统一扩载，#88） | `projectId` `runId` `reason`——修正 run 收口·系统未动（[#46](https://github.com/ZhangColin/aiplatform/issues/46)）：编码智能体以 finish_edit(changed=false) 判定无需改动时的如实呈现；`reason` 为未动原因（finish_edit 的 text 原文）；事件序 `run-finish → fix-unchanged`；changed=true 不发 |
| `dispatch-stage` | 九值全量退役（归位生命周期事件，不设替代） | `projectId` `runId` `stage` `changed`（仅 done）——派发阶段事件（[#50](https://github.com/ZhangColin/aiplatform/issues/50) 阶段状态条的唯一数据源）：`stage` 值集（发射方 `DispatchStage`）：analyzing / clarifying / updating-prd / dispatching / queued / fixing / done / answered / dispatch-failed（详口径见 [#50](https://github.com/ZhangColin/aiplatform/issues/50)——意见链跨 run 推进、帧序即阶段序、BA 零动作意见阶段照走、dispatch-failed 为派发失败终态锚 BA 轮 runId） |
| `live-text` / `live-action` / `live-step` | 退役（part-* 同内核承接） | `projectId` `runId` `sessionId` `engine` + `text`/`action`/`step`——直播三型（[#23](https://github.com/ZhangColin/aiplatform/issues/23)，编码 run 专属，`AgentCommand.live` opt-in）：解说段（完整段，与 part-text 同切段内核）/ 动作摘要行（工具动作 → 人话模板「正在编写【文件名】」，仅调用落定出——与 part-action 的全生命周期差异即 #77 补动作开始的动因）/ 步骤段（1 起序号）。前端直播侧栏只消费本组 + run 生命周期事件；直播随 run 生命周期呈现，收起即逝、无历史回看 |

> 字段表随各 SSE 对接 issue 细化；信封与名册的任何变更即改本文。

## 前端通用模块（约定）

站点布局级挂通知通道实例（常开），项目页挂智能体事件实例（看运行才挂）；模块统一管连接建立、心跳透明、自动重连、重连后 REST 重查钩子、按 type 分发回调——页面只声明关心的 type，不重复写连接逻辑。
