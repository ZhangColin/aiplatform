# SSE 事件清单（正本）

> 平台 SSE 事件的名册与信封正本，[ADR-0001](../adr/0001-swagger-contract-and-sse-channels.md) 定稿、#82 起单端点单流。前端消费以本文 + swagger 端点描述为准。
> 事件只让 UI「活」，不承担正确性：断线丢失可接受，状态以 REST 查询为准（通知族口径；智能体事件族另有断线补发，对话史以 REST 水合收敛，[#89](https://github.com/ZhangColin/aiplatform/issues/89)）。
>
> **治理**：eventhub 是唯一 SSE 管道、单端点单流（平台通知 + 智能体事件共用一条流，合并通道不合并语义——词汇表归 eventhub，智能体事件由 base.agentscope 的 mapper 翻译填充）。新增顶层 type 必须先进本清单再上线（code review 检查）；代码侧只允许引用 `XxxEventTypes` 常量类，禁止字符串字面量散落。

## 信封（单端点单流）

```
event: event
id: {streamId}:{seq}          # 通知族 streamId=projectId；智能体事件族 streamId=runId
data: {"type":"...","payload":{...},"ts":"2026-08-19T02:15:33.123Z"}
```

- SSE name 恒为 `event`，前端每条连接一个 listener。
- `payload` 恒为对象，必带关联字段；**payload 内禁用 `type` 键名**。
- 心跳：每 15s 发注释行 `:ping`（不进 listener，仅保活）。
- 订阅：`GET /api/events?projectId=xxx&runId=xxx`（过滤参数与 payload 关联字段同名，多参数 AND；缺省 = 只收通知族）。
- **族投递规则**：智能体事件族只投递给带过滤（`projectId` 或 `runId`）的订阅——过程细节是项目内事实，无跨项目消费面；未过滤订阅（站点级常开连接）只收平台通知族。
- **Last-Event-ID 分野（#89 断线补发）**：有值（浏览器断线重连自动携带）= 断线补发——先收命中过滤的智能体缓冲事件中**锚事件之后**的窗口（见下）再进实时流；无值（缺席或空串）= 新连接（刷新/回访）= **不补发**——对话史经 REST 水合（`GET /api/projects/{id}/conversation`），平台状态以查询收敛。

## 两族语义（合并通道不合并语义）

一条流承载两族事件，语义分家（[#82](https://github.com/ZhangColin/aiplatform/issues/82) 双通道合并）：

- **平台通知族**：**永不补发**——发射不进缓冲，只达实时订阅；「只作实时呈现，状态以查询为准」（CONTEXT.md「平台通知」）。断线由前端 REST 重查兜底。
- **智能体事件族**：**带近期事件缓冲的热流（断线补发）**（[#56](https://github.com/ZhangColin/aiplatform-server/issues/56)、[#53](https://github.com/ZhangColin/aiplatform-server/issues/53)，[#89](https://github.com/ZhangColin/aiplatform/issues/89) 起降级）——事件一经发射即进有界缓冲（零订阅时也进），默认最近 1000 条、配置 `app.agent-events.replay-depth`。断线重连（Last-Event-ID 在场）补发**锚事件之后**命中过滤的窗口（锚已被容量逐出或随重启丢失则整段缓冲——缓冲内事件必然晚于锚，不重复）；**新连接（刷新/回访）不补发**——对话史落库后水合重建归 REST（`GET /api/projects/{id}/conversation`），重放缓冲只承担断线窗口、不再承担刷新重建。缓冲为**单实例内存态**（重启即失），多实例化时需重估。

## 平台通知族

平台状态变化的广播；编排层在副作用真实落定后发射。

| type | payload 字段 | 示例 |
|---|---|---|
| `workspace-created` | `projectId` `projectName` `container` `projectType` | `{"projectId":"a1b2c3d4","projectName":"官网 demo","container":"aiplatform-dev-a1b2c3d4","projectType":"WEBSITE"}` |
| `preview-ready` | `projectId` `url` | `{"projectId":"a1b2c3d4","url":"http://localhost:30080"}`（预览 URL 就绪推送（[#105](https://github.com/ZhangColin/aiplatform/issues/105)）：由**切片收口**（生成轨道阶段 0 与逐片收口、8081 探活通过后）发射，REST `GET /preview` 成功后仍发射（双源、幂等）；前端 bridge 消费写预览查询缓存（`setQueryData` 写 URL），3s 轮询降级为 SSE 断线兜底——免轮询、事件驱动拿 URL） |
| `workspace-destroyed` | `projectId` | `{"projectId":"a1b2c3d4"}` |
| `document-updated` | `projectId` `documentType` | `{"projectId":"a1b2c3d4","documentType":"PRD"}`（工作区文档产物写出/修订落定后广播；v1 唯一写入方 = 主智能体的 savePrd（[#49](https://github.com/ZhangColin/aiplatform-server/issues/49)），每次执行必发；前端按**失效为主**模式消费——invalidate 文档域 + 对话区提示胶囊，内容经 `GET /api/projects/{id}/prd` 重拉。[#41](https://github.com/ZhangColin/aiplatform-server/issues/41) 新增） |
| `project-renamed` | `projectId` `projectName` | `{"projectId":"a1b2c3d4","projectName":"品牌官网"}`（异步取名落库成功顶替占位名后发射，取名线程 save 提交后即发——ADR-0001 时序同款；前端失效 projects 域重拉，停留中的页面上名字静默浮现。守卫不覆写（用户已改名/取名已完成）与取名失败保占位**均不发**——失败静默是既有设计，改名端点兜底。[#52](https://github.com/ZhangColin/aiplatform-server/issues/52) 新增） |
| `order-status-changed` | `projectId` `orderId` `status` `statusName` | `{"projectId":"a1b2c3d4","orderId":"900123","status":2,"statusName":"已报价"}`（订单状态变化后发射：下单（待报价）/首次报价（已报价）/取消/支付完成/归档（已归档）各发一次——支付与归档分两发，归档失败只发「已支付」，改价不换状态不发；发射方 = order 上下文 `OrderEventTypes`，副作用真实落定后（支付路径在支付/归档各自事务提交后）。前端消费 = toast（点击直达项目页）+ 失效订单/项目域重查；状态 code 为 OrderStatus Integer：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消（已支付为真实态，支付落定后发）。[#30](https://github.com/ZhangColin/aiplatform/issues/30) 新增，[#39](https://github.com/ZhangColin/aiplatform-server/issues/39) 支付原子化修订） |

> **`workspace-created` 信号语义（[#61](https://github.com/ZhangColin/aiplatform-server/issues/61)）**：该信号表示「工作区记录已落库、容器后台置备中」，**不再是「容器就绪」**——创建即返回，docker 置备后台收敛到 ready/failed。前端以「记录存在」即可进对话；环境能力（跑代码/出 PRD）的就绪性以 REST 查询工作区 `status`（provisioning/ready/failed）为准，信号非权威（CONTEXT.md「平台通知」）。

## 智能体事件族

一次智能体运行的增量过程流；payload 必带 `runId`，`sessionId` 会话建立后携带，`projectId` 由业务编排桥接注入。订阅过滤：`?runId=`（「看某个运行才挂」的常规姿势）/ `?projectId=`，可叠用（AND）。

### 消息模型（parts 契约）

**消息 = 有序部件集合（parts）**（六源行业同构；词根基准 = agentscope 原生 part 族——文本/推理/工具调用请求/文件，mapper 保持薄翻译层，不强套某家词表）。run 进行中 = 对话区内一条**生长中的工作消息**（解说文本部件 + 工具动作部件，带状态；部件按序竖排，无步骤分组），run 收口 = 消息定格。部件事件（`part-*`）由 base.agentscope 的部件映射表（`AgentscopePartsMapper`）从同一 AgentScope 事件流产出，全事件流恒挂（不限编码 run）——例外：`part-check` 由平台侧收口判据核验产出（自检是平台事实，不经引擎 mapper，[#85](https://github.com/ZhangColin/aiplatform/issues/85)）；思考与代码补丁不进部件（思考与代码流不播——CONTEXT.md「智能体事件」）。

下表「payload 字段」列的关联字段 = `runId`（必带）+ `projectId`（业务编排桥接注入）+ `sessionId`（会话建立后携带）。三类事件：

- **生命周期事件**（平台封闭集合，注册制）：run 级开场/挂起/收口/权限确认三件套；代码侧引用 `AgentEventTypes` 常量（base.eventhub）；
- **消息部件事件**（`part-*`，部件契约）：生长中的工作消息的部件增量；
- **引擎透传事件**（开放集合）：`data` 字段内为引擎 part 原样，下表列已知名型。

#### 生命周期事件

| type | payload 字段 | 说明 |
|---|---|---|
| `run-start` | `projectId` `runId` `prompt` `model` `engine` `agent`（可空） | 运行开始（runId 随 run 响应同值返回）。**引擎信息归一**：engine/model 之外携带智能体配置键 `agent`（业务侧 AgentProfile 的稳定键：`main` 主智能体对话轮 / `executor` 编码 run；无配置语境的一次性调用不携带）——前端呈现形态的登记锚：executor 起工作消息、main 进对话面（[#86](https://github.com/ZhangColin/aiplatform/issues/86) 单会话收敛后对话只有主智能体一座，无角色分支）。**一场 run 恰一次**（[#84](https://github.com/ZhangColin/aiplatform/issues/84) 静默重试：编码 run 重试不新发——用户面 run 身份 = 首试 runId 全程不变，重试尝试的内部 runId 不出用户面） |
| `error` | `projectId` `runId` `message` | 失败表达（非重试族：对话轮失败、挂起续跑失败、run 起跑前段失败、意见链收口后派发修正 run 失败——锚定收口对话轮，如实呈现重提即兜底）。编码 run 尝试环内中间失败**不出事件**（静默重试）——run 级唯一失败终态见 run-failed |
| `run-finish` | `projectId` `runId` `sessionId` `engine` `finish` `closing`（可缺省） | 运行结束（finish = 引擎结煞语 end / exceed_max_iters 等）；挂起轮不发（软终点，等答复续跑后收口）。编码 run 在收口判据落定后才发（[#84](https://github.com/ZhangColin/aiplatform/issues/84)：判据不过 = 该次尝试失败静默重试，中场无假收口——run-finish 一场 run 至多一次、到达即真收口）。**收口扩载**（[#88](https://github.com/ZhangColin/aiplatform/issues/88)）：编码 run 的真收口携带 `closing` 对象（收尾卡的服务端权威事实，schema 见[下节](#收口扩载closing-schema88)）——工作消息定格为收尾卡（四要素：摘要/判定行/变更清单/轮末统计）；**主智能体对话轮（咨询/纯追问）不携带**——无收尾卡 |
| `question-raised` | `projectId` `runId` `sessionId` `summary` `engineRef` `data` | 智能体挂起提问（[#83](https://github.com/ZhangColin/aiplatform/issues/83) 起纯 QUESTION——权限确认已拆独立事件）；`data.questions` 为前端问答卡投影，`data.toolCalls`（待确认工具最小面）为答复通道回传面 |
| `permission-required` | `projectId` `runId` `sessionId` `summary` `engineRef` `data` | 权限确认挂起（[#83](https://github.com/ZhangColin/aiplatform/issues/83) 事件拆分，词根 = 引擎权限确认原语 RequireUserConfirmEvent 的非提问面）：run 执行中需用户批准的工具操作（危险命令 → 确认卡长在工作消息流，批准/拒绝两个动作）。`summary` = 首工具的命令文本（截断保短，确认卡摘要行）；`data.toolCalls` = 待确认工具最小面（确认卡呈现待批准操作的依据）。**作答走权限作答通道**（`POST /api/projects/{id}/permissions/{ref}/answer`，ref=engineRef；与问答作答分家——互不串扰）；生产触发面 = 平台侧 `command` 工具的破坏性命令自检（封闭小表：递归强删/提权/格式化与裸写设备/关机族/fork 炸弹） |
| `permission-resolved` | `projectId` `runId` `engineRef` `approved` | 权限确认落定（[#83](https://github.com/ZhangColin/aiplatform/issues/83)）：作答被受理（批准或拒绝）即发射——确认卡转已批/已拒终态的呈现源（呈现事实双通道：断线补发窗口内事件可达；刷新经对话史水合——#89，确认卡不回退成待答）。续跑结果另行经 run 过程事件到达（批准的动作卡完成 / 拒绝的动作卡失败 + 后续模型行为）；run 终态仍归 `run-finish`/`run-failed` |
| `permission-timed-out` | `projectId` `runId` `engineRef` | 权限确认超时落定（[#112](https://github.com/ZhangColin/aiplatform/issues/112) 权限确认 10 分钟超时默认拒绝）：轨道驻留等作答越 10 分钟上限即发射——确认卡转「已超时」终态（不可作答、按钮退场），随后轨道直接 `run-failed` 收口（**不复用静默重试**——重试同上下文同命令必然再挂）。与 `permission-resolved` 同族不同语义：本事件**非作答**（无 `approved` 位），超时即拒绝——破坏性命令永不默认放行 |
| `run-failed` | `projectId` `runId` | 编码 run 重试超限·终态收口（[#56](https://github.com/ZhangColin/aiplatform/issues/56)）：轨道层在真终态落定点发射——修正轨道与终态账（恢复出口 `restartFixRun` 的重派依据）同事实点，排队合并续派的中途超限不是终态、不发；生成轨道超限即终态。`runId` = 该场 run 的用户面标识（首试 runId——[#84](https://github.com/ZhangColin/aiplatform/issues/84) 重试不换新锚）。**run 失败为唯一失败终态**——重试全程静默（中间错误与重试信号不出用户面：无逐次 `error`、无重试 `run-start`），前端恢复出口只认本事件。权限确认超时（[#112](https://github.com/ZhangColin/aiplatform/issues/112)）同为失败终态（不发 `permission-resolved`、不续跑——如实原因经 `permission-timed-out` 确认卡「已超时」表达） |
| `guide-reply` | `projectId` `runId` `prompt` `label` `text` | 兜底轻引导回复（[#47](https://github.com/ZhangColin/aiplatform/issues/47) 入口三分类的兜底分支）：非意见非咨询输入的平台侧定型引导文案——零产物路径（不起任何智能体 run，本事件即该次派发的全部）。`runId` 为派发锚；`prompt` 为锚定的用户输入（事件到达重建对话面用；回访经对话史水合——#89）；`label` 为呈现标签（「平台」）；`text` 为引导文案 |
| `acceptance-start` | `projectId` `runId` | 受理开始（[#87](https://github.com/ZhangColin/aiplatform/issues/87) 受理动作卡）：受理轮（迭代期意见轮——项目已生成后的意见链轮）开场的受理事实，**对话区受理动作卡的呈现源**——意见已接住、主智能体正在受理（需求不清则追问；需求变更则改 PRD），衔接轮收口自动派的更新 run 工作消息（原派发阶段「更新 PRD 中」呈现位的归位，不设其余阶段事件依赖）。守卫全过后、受理动作前发射，先于该轮 `run-start` 到达（动作卡先出、解说随后，对话区连续可见）；受理落定**不出新事件**——由该轮 `run-finish` / `error` 收口事件推导（挂起-续跑是同一受理轮，不重发）。场景矩阵收口：咨询轮与纯追问轮（访谈期意见轮）不发 |

#### 消息部件事件（`part-*`）

| type | payload 字段 | 说明 |
|---|---|---|
| `part-text` | `projectId` `runId` `sessionId` `engine` `source`（可缺省） `text` | 解说文本部件：`text` 为**完整段非增量**——服务端逐段成型（句读 / 文本块变 / 动作边界 / 来源切换 / 长度上限切段），run 收口事件前出尾段 |
| `part-action` | `projectId` `runId` `sessionId` `engine` `source`（可缺省） `toolCallId` `toolName` `state` `label` | 工具动作部件（动作卡）：**开始/进行中/完成/失败全生命周期**——动作一开始即出事件，同一动作以 `toolCallId` 锚定跨状态更新。`state` ∈ `started`（模型发起工具调用，参数在途）/ `running`（参数落定、工具执行中）/ `completed`（结果成功）/ `failed`（结果出错/被拒/中断——动作层状态，run 层唯一失败终态仍是 run-failed）。`label` = 动作对象短语（人话行，无时态——时态由 state 表达；started 时参数在途为通用对象如「编写【代码文件】」，running 起解析参数为具体对象如「编写【订单管理】」，终态复述不闪换）。播报工具为封闭表：write_file / edit_file / command——读类工具不进部件（对客户是噪音） |
| `part-check` | `projectId` `runId` `sessionId` `state` | 自检播报部件（[#85](https://github.com/ZhangColin/aiplatform/issues/85)：「正在检查系统 → ✅/❌」）：run 收口判据核验（自检）的呈现——**平台侧产出**（不经引擎部件映射表，收口判据是平台事实：生成 = 8081 探活、更新 = finish_edit 收口事实），核验开始发 `checking`、落定发 `passed`/`failed`。静默重试同构口径（#84）：尝试间核验未过**不发 `failed`**——部件停在 `checking`（重试信号不外泄，重复 `checking` 幂等）；`failed` 仅在末次尝试未过（超限转终态）时发，与 `run-failed` 同窗口到达。状态终值（passed/failed）= 探活结果，可被收尾统计消费（#88 轮末统计行） |
| `part-attachment` | —— | **消息附件部件**（[#97](https://github.com/ZhangColin/aiplatform/issues/97) 圈注落地）：圈注锚随用户发言发送的载荷位——指认是对话输入的增强不是替代，<b>不随 run 过程流发射</b>：随 `POST /api/projects/{id}/messages` 的 `attachments` 进派发（渲染进主智能体 prompt 精确读取），并随用户发言落对话史 JSONB（`prj_conversation_entries.attachments`）、刷新回访经 `GET /api/projects/{id}/conversation` 水合回显圈注 chip（非截图）。schema 见[下节](#消息附件部件锚载荷-schema97) |

> **来源归属（#95 委派位）**：委派子智能体（如自测）转发进父流的过程事件带 `source` 字段（值 = 子智能体声明名，引擎 source 路径的末段）；run 执行体自身产出的过程事件**不携带** `source`（缺省 = 执行体——用户面仍无角色标签，source 只用于过程呈现归属）。携带范围：引擎透传事件（`text`/`reasoning`/`tool`/`step-*`，payload 顶层）与消息部件事件（`part-text`/`part-action`）。生命周期事件（`run-start`/`run-finish` 等）恒为执行体层级，不带 source；`part-check` 为平台侧产出，也不带。

#### 收口扩载 closing schema（#88）

run-finish 的 `closing` 对象——收尾卡（工作消息定格后的收尾部件，非另起的卡）的唯一权威事实源，**对话史落库（#89）与版本锚定（#91）复用同一载荷**。判定与清单以平台可观测事实为准（工具调用/探活），不由模型自报：

```
closing: {
  summary:      "本轮做了什么（判定事实的合并叙事，文档与系统不分侧）"  # 收口各一句：
                #   生成轨道（#104）= 本段叙事（「起服了系统骨架」/「完成切片：用户能 X」）
                #   更新轮 = 修订了需求文档，并更新了系统 / 修订了需求文档，系统无需改动
                #   / 更新了系统 / 本轮系统无需改动
  prdChanged:   true | false        # 判定行·PRD 改没改（更新轮 = 交接物修订说明事实；生成轮恒 false）
  prdNote:      "修订说明"          # 可缺省——未修订/无说明（多轮排队合并以「；」连缀）
  systemChanged: true | false       # 判定行·系统改没改（更新轮 = finish_edit 工具事实；生成轮 = 探活收口产出）
  systemNote:   "改了什么/为什么无需改"  # 可缺省（更新轮 = finish_edit 必带说明；生成轨道 = 本段叙事，与 summary 同值）
  files: [                         # 变更清单（文件清单级——服务端不产出 patch 类事件，清单由本扩载权威承载）
    { path: "/src/App.jsx", added: 40, removed: 0 }   # path = 工作区锚定形；行数为
    ...                                              # write_file（新文件行数）/ edit_file（新旧串行数）
  ],                               # 的活动量口径：同路径跨尝试合并、按路径排序；
                                   # edit 的 replace_all 多命中按一次计、命令行改造的文件不进清单
                                   # （真 diff 归版本层 #91 容器 git）——已知取舍，非精确 diff
  durationMs: 183420               # 轮末统计·时长（首试起跑到收口；文件数/变更行数由 files 派生）
  selfTest:   { total: 3 }         # 可缺省——自测统计（#96 自测子智能体清单式播报的收尾统计）：
                                   # 自测子智能体（source=self-test）的 command 动作去重计数（一项 =
                                   # 一条测试命令）；无自测命令动作（子智能体未跑）不携带——收尾卡
                                   # 缺省不出自测统计行。判定以平台可观测的命令动作事实为准；逐项
                                   # ✅/❌ 的通过/未过明细在过程播报（解说段）里，收尾卡只带「自测
                                   # 几项」聚合——命令工具的成败态不反映测试成败（非零退出码仍是
                                   # completed），平台不据此伪报通过/未过（不粉饰、不瞎判）
  version:     "a1b2c3d4e5f6..."   # 可缺省——版本锚定（#91）：收口自动成版的 commit hash
                                   # （容器内 git，主题 = 摘要、Run-Id trailer 锚定收尾卡）；
                                   # 成版失败（git 不可用等）本轮缺 version 键——run 收口不受影响
  durationBreakdown: {             # 阶段耗时分布（#111——平台分析口径，前端不渲染；
                                   # 四桶齐备 + 逐尝试分布；计时源 = 引擎事件 createdAt 服务端真实口径）：
    llmMs:      52000,             #   LLM 等待（执行体模型调用起止累计）
    toolsMs: {                     #   工具执行（参数落定→结果落定的纯执行窗，按工具名分桶；
                                   #   agent_spawn 委派调用除名——跨距 ≈ 自测窗，入桶即双计）
      write_file: 3000,
      command: { install: 61000, dev: 800, test: 2400, other: 500 }
                                   #   command 按命令归组（依赖安装 / dev server / 测试 / 其他，
                                   #   粗分组首版，只携带出现过的组）
    },
    selfTestMs: 9000,              #   自测子智能体委派窗（首末 source=self-test 事件跨距；无自测 = 0）
    closingMs:  300,               #   收口尾序（探活 + 成版；落库自指不可测——载荷先于落库定型，量级极小忽略）
    attempts: [                    #   逐尝试分布（静默重试代价可归因）——attempt / durationMs
      { attempt: 1, durationMs: 61000, llmMs: 52000, toolsMs: {...}, selfTestMs: 9000 }
    ]                              #   （尝试墙钟）+ 该尝试三桶；中段崩的尝试桶为零
                                   #   （阶段耗时事实随异常弃置）、墙钟照记
    # 一致性口径：桶计 + 未归因差值（平台管道 / 权限作答等待 / 判据未过的核验）≈ durationMs——
    # 量级不符即埋点有洞（缝测守卫）；closingMs 含成版而 durationMs 窗口不含（小正偏差）
  }
}
```

- **到达即收尾卡**：`closing` 存在 ⟺ 编码 run 真收口 ⟺ 自检通过（part-check passed 先行）——前端过程明细（解说段/动作卡流水）收口后不常驻，收尾卡即凝聚物；`closing` 缺席的 run-finish（咨询/纯追问轮）无收尾卡。
- **版本锚定（#91）**：`closing.version`（可缺省）= 收口自动成版的 commit hash——版本正本 = 容器内 git log（无便利表），Run-Id trailer 联接收尾卡与版本；版本详情 API 复用 `closing` 载荷锚定收尾卡。
- **阶段耗时分布（#111）**：`closing.durationBreakdown` 随收尾卡落库（对话史 JSONB），供平台事后分析时长归因（LLM 等待 / 工具执行 / 自测 / 收口尾序 + 逐尝试分布）；**用户面不呈现**——收尾卡「用时」行只认 `durationMs`，前端收窄读取、多余键不进 store。
- **判定行权威化**：旧「编辑无变化」前端推导过渡口径移除（`fix-unchanged` 事件已随 #82 退役）——判定行只认本载荷。

#### 消息附件部件锚载荷 schema（#97）

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
- **落地口径（#97）**：附件部件随 `POST /api/projects/{id}/messages` 的 `attachments` 进派发——渲染成主智能体 prompt 自然语言段精确读取（见 `AnnotationPrompt`），并随用户发言落对话史（`prj_conversation_entries.attachments` JSONB），刷新/回访经 `GET /api/projects/{id}/conversation` 水合回显圈注 chip；注入脚本（`docker/workspace/annotation.js`）由 `serve.js` 对 HTML 响应内联注入、经 postMessage 回传结构化锚。

#### 引擎透传事件（开放集合）

| type | payload 字段 | 说明 |
|---|---|---|
| `text` | … + `source`（可缺省） + `data`（`delta` `blockId`） | 文本增量（前端按序拼接——对话面主智能体话语的消费源） |
| `reasoning` | … + `source`（可缺省） + `data`（`delta` `blockId`） | 思考增量 |
| `patch` | … + `data`（`path` `diff` `edits`） | 代码补丁（服务端现状不产出——名型占位） |
| `tool` | … + `source`（可缺省） + `data`（`toolCallId` `toolName` `phase: start|end`） | 工具调用（引擎原生粒度；用户面动作呈现归 part-action） |
| `step-start` / `step-finish` | … + `source`（可缺省） + `data`（`replyId`） | 步骤边界（模型调用边界；用户面不呈现——步骤分组已随 #115 退役，引擎透传名型保留） |

> **智能体事件桥**：AgentScope HarnessAgent 的事件经映射表翻译走同一通道同一信封——`engine=agentscope`，事件序 `run-start → 过程事件（透传 + 部件并行）→ run-finish/error`。透传映射表单点 = `AgentscopeEventMapper`；部件映射表单点 = `AgentscopePartsMapper`（解说切段与动作行的生产内核由 `NarrationSegments` / `ToolActionLines` 承载）。挂起（`RequireUserConfirmEvent`）按分诊拆两事件（[#83](https://github.com/ZhangColin/aiplatform/issues/83)）：ask_user 提问 → `question-raised`（问答卡；`data.questions` 为前端投影：`[{header, question, multiple, custom(恒 true), options[{label}]}]`，`summary` 取问题文本），需批准的工具操作 → `permission-required`（确认卡；`summary` 取命令文本）；作答通道分家——问答作答（`POST /api/projects/{id}/questions/{qid}/answer`，答复文本）与权限作答（`POST /api/projects/{id}/permissions/{ref}/answer`，批准/拒绝布尔位）互不串扰。权限作答受理即发 `permission-resolved`（确认卡转已批/已拒）；续跑批准即放行执行、拒绝即引擎写「用户已拒绝」工具结果回模型（改道或如实收口，可能仍收口成功）。挂起轮不发 `run-finish`；答复续跑归业务编排（从项目侧事实重建恢复私货 + 挂起事件 `data.toolCalls` 重建 ConfirmResult）；会话状态落 PostgreSQL（`cat_agent_state` 承载全部智能体会话），平台重启后按会话标识恢复续跑。

## 前端通用模块（约定）

站点布局级挂常开连接（未过滤订阅——只收通知族），项目页挂项目过滤连接（`?projectId=`——通知 + 智能体事件两族、断线补发面；新连接不补发，对话史经 REST 水合——#89）；模块统一管连接建立、心跳透明、自动重连、重连后 REST 重查钩子、按 type 分发回调——页面只声明关心的 type，不重复写连接逻辑。同一连接上的通知族由常开连接消费，项目页连接只分发智能体事件族（族内分工，防双连接双处理）。
