# SSE 事件清单（正本）

> 平台 SSE 事件的名册与信封正本，[ADR-0001](../adr/0001-swagger-contract-and-sse-channels.md) 定稿、#82 起单端点单流。前端消费以本文 + swagger 端点描述为准。
> 事件只让 UI「活」，不承担正确性：断线丢失可接受，状态以 REST 查询为准（通知族口径；智能体事件族另有断线补发，对话史以 REST 水合收敛，[#89](https://github.com/ZhangColin/aiplatform/issues/89)）。
>
> **治理**：eventhub 是唯一 SSE 管道、单端点单流（平台通知 + 智能体事件共用一条流，合并通道不合并语义——词汇表归 eventhub，智能体事件由 base.agentscope 的 mapper 翻译填充）。新增顶层 type 必须先进本清单再上线（code review 检查）；代码侧只允许引用 `XxxEventTypes` 常量类，禁止字符串字面量散落。
>
> **改记（2026-11-24，设计线整条退役，#610／#613）**：本文中 `agent=designer`、设计会话/定稿收尾卡（`closing.drafts`、`triggers`）、`generate_image` 工具动作、`designLint` 收口扫描、设计轨逐尝试失败与稿序等相关描述均为历史记录；现行事件面不含设计座，事件名册与载荷以代码与 swagger 为准。
>
> **改记（2026-11-25，旧迭代受理链拆除，#625）**：`acceptance-start`（受理动作卡）从事件名册退役；生成后对话区发言走对话面（#622），说清的修改经 `submit_modification` 交平台、由对话面待办承接（#623），不再有旧意见轮收口派发、交接物、修正轨排队合并与「继续更新」恢复出口。`intercept-card` / `balance-reminder` 的试用拦截与余额留存代码作为商业面保留，未接新链路（生成后项目在分类前即分岔到对话面）——相关描述以代码与 swagger 为准。
>
> **改记（2026-11-25，版本与商业确认改接——#626／#628／#629／#630，本票 #631）**：用户面版本＝工作区 git 提交（`Platform-Version`＋`Run-Id` trailer，主题＝用户那句话），版本序列＝按标记过滤的 git log，不再有旧 PRD/Demo 版本表；旧双轴版本记录抽屉与「查看当时」快照容器/预览网关快照子域路由整条退场。商业面「确认此版本」改标记当前用户面版本，订单/合同下单冻结收敛为单个已确认用户面版本号。本清单无版本/快照专属事件——版本列表走 REST，回滚进度复用工作消息起止（见 `run-start` 下的「平台侧动作复用」注）。

## 信封（单端点单流）

```
event: event
id: {streamId}:{seq}          # 通知族 streamId=projectId；智能体事件族 streamId=runId
data: {"type":"...","payload":{...},"ts":"2026-08-19T02:15:33.123Z"}
```

- SSE name 恒为 `event`，前端每条连接一个 listener。
- `payload` 恒为对象，必带关联字段；**payload 内禁用 `type` 键名**。
- `ts` 为 ISO-8601 信封时间戳：前端唯一消费 = run 级「已运行」时钟锚（[#225](https://github.com/ZhangColin/aiplatform/issues/225)——run-start 起锚、run-finish / run-failed 定格，ADR-0010 窄修订），其余消费不依赖。
- **归属路由键（[ADR-0018](../adr/0018-sse-delivery-user-isolation.md)，#208；#326 扩访客键）**：payload 另携 `ownerAccountId`/`ownerGuestId` 双键（两族共有，字符串，与 projectId/runId 同 idiom；owner 双列互斥，事件恰带其一非空、未落侧空串）——发布侧从聚合注入，投递按「事件归属 == 订阅者」双键匹配隔离（账户/访客互不可见）。**路由键非内容，前端不消费**。
- 心跳：每 15s 发注释行 `:ping`（不进 listener，仅保活）。
- 订阅：`GET /api/events?projectId=xxx&runId=xxx`（过滤参数与 payload 关联字段同名，多参数 AND；缺省 = 只收通知族）。订阅握手绑定登录账号；过滤参数维持纯兴趣选择——他人 projectId 的过滤订阅 = 静默空流（连接不断）。
- **族投递规则**：智能体事件族只投递给带过滤（`projectId` 或 `runId`）的订阅——过程细节是项目内事实，无跨项目消费面；未过滤订阅（站点级常开连接）只收平台通知族。未过滤订阅语义 = **我的全部通知**（对他人 deny-by-default）。
- **Last-Event-ID 分野（#89 断线补发）**：有值（浏览器断线重连自动携带）= 断线补发——先收命中过滤的智能体缓冲事件中**锚事件之后**的窗口（见下）再进实时流；无值（缺席或空串）= 新连接（刷新/回访）= **不补发**——对话史经 REST 水合（`GET /api/projects/{id}/conversation`），平台状态以查询收敛。补发走与实时同一投递谓词（含归属匹配——只补本人）。

## 两族语义（合并通道不合并语义）

一条流承载两族事件，语义分家（[#82](https://github.com/ZhangColin/aiplatform/issues/82) 双通道合并）：

- **平台通知族**：**永不补发**——发射不进缓冲，只达实时订阅；「只作实时呈现，状态以查询为准」（CONTEXT.md「平台通知」）。断线由前端 REST 重查兜底。
- **智能体事件族**：**带近期事件缓冲的热流（断线补发）**（[#56](https://github.com/ZhangColin/aiplatform-server/issues/56)、[#53](https://github.com/ZhangColin/aiplatform-server/issues/53)，[#89](https://github.com/ZhangColin/aiplatform/issues/89) 起降级）——事件一经发射即进有界缓冲（零订阅时也进），默认最近 1000 条、配置 `app.agent-events.replay-depth`。断线重连（Last-Event-ID 在场）补发**锚事件之后**命中过滤的窗口（锚已被容量逐出或随重启丢失则整段缓冲——缓冲内事件必然晚于锚，不重复）；**新连接（刷新/回访）不补发**——对话史落库后水合重建归 REST（`GET /api/projects/{id}/conversation`），重放缓冲只承担断线窗口、不再承担刷新重建。缓冲为**单实例内存态**（重启即失），多实例化时需重估。

## 平台通知族

平台状态变化的广播；编排层在副作用真实落定后发射。

| type | payload 字段 | 示例 |
|---|---|---|
| `workspace-created` | `projectId` `projectName` `container` `projectType` | `{"projectId":"a1b2c3d4","projectName":"官网 demo","container":"aiplatform-dev-a1b2c3d4","projectType":"WEBSITE"}` |
| `preview-ready` | `projectId` `url` | `{"projectId":"a1b2c3d4","url":"http://localhost:30080"}`（预览 URL 就绪推送（[#105](https://github.com/ZhangColin/aiplatform/issues/105)）：由 **run 收口**（8081 探活通过后）与唤醒/应用拉起中继（PreviewReadyRelay）发射，REST `GET /preview` 成功后仍发射（双源、幂等）；前端 bridge 消费写预览查询缓存（`setQueryData` 写 URL），3s 轮询降级为 SSE 断线兜底——免轮询、事件驱动拿 URL） |
| `workspace-destroyed` | `projectId` | `{"projectId":"a1b2c3d4"}` |
| `document-updated` | `projectId` `documentType` | `{"projectId":"a1b2c3d4","documentType":"PRD"}`（工作区文档产物写出/修订落定后广播。写方两处：主智能体的 savePrd（[#49](https://github.com/ZhangColin/aiplatform-server/issues/49)，每次执行必发）；迭代 run 成功收口且内核按任务提示把本轮需求并入 PRD 对应章节、保持完整文档、工作区 PRD 与起跑快照不同（[#624](https://github.com/ZhangColin/aiplatform/issues/624)，无变化不发——平台不写 PRD、不追加原始意见）。[#334](https://github.com/ZhangColin/aiplatform/issues/334) 停驻落定的复用发射口已随确认门退役；前端按**失效为主**模式消费——invalidate 文档域 + 对话区提示胶囊，内容经 `GET /api/projects/{id}/prd` 重拉。[#41](https://github.com/ZhangColin/aiplatform/issues/41) 新增） |
| `project-renamed` | `projectId` `projectName` | `{"projectId":"a1b2c3d4","projectName":"品牌官网"}`（异步取名落库成功顶替占位名后发射，取名线程 save 提交后即发——ADR-0001 时序同款；前端失效 projects 域重拉，停留中的页面上名字静默浮现。守卫不覆写（用户已改名/取名已完成）与取名失败保占位**均不发**——失败静默是既有设计，改名端点兜底。[#52](https://github.com/ZhangColin/aiplatform-server/issues/52) 新增） |
| `order-status-changed` | `projectId` `orderId` `status` `statusName` | `{"projectId":"a1b2c3d4","orderId":"900123","status":2,"statusName":"已报价"}`（订单状态变化后发射：下单（待报价）/首次报价（已报价）/取消/归档（已归档）各发一次，改价不换状态、不发本事件（改价信号 = `order-repriced`）；发射方 = order 上下文 `OrderEventTypes`，副作用真实落定后（归档在重试归档事务提交后）。前端消费 = toast（点击直达项目页）+ 失效订单/项目域重查；状态 code 为 OrderStatus Integer：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消（已支付＝开工条件满足——#337 mock 支付链退役后无平台内发射口，判定接通归 #338；付款节点态变化无推送，付款面板经详情轮询）。[#30](https://github.com/ZhangColin/aiplatform/issues/30) 新增，[#39](https://github.com/ZhangColin/aiplatform-server/issues/39) 支付原子化修订，[#204](https://github.com/ZhangColin/aiplatform/issues/204) 改价拆出，[#337](https://github.com/ZhangColin/aiplatform/issues/337) mock 支付闭环退役） |
| `order-repriced` | `projectId` `orderId` `status` `statusName` | `{"projectId":"a1b2c3d4","orderId":"900123","status":2,"statusName":"已报价"}`（订单已改价：已报价态改价落定后发射——改价不换状态，本事件即改价信号（[#204](https://github.com/ZhangColin/aiplatform/issues/204) 改价入流，推翻「改价不换状态不发」的静默，ADR-0017）。发射时序 = 先落「报价已更新」对话卡后发（信号触发前端重查时卡已在库，#203 同款）；载荷与 `order-status-changed` 同字段同量级（`status` 恒 2=已报价），**不含金额与备注**。前端消费 = 失效订单/项目/对话史域重查——在场项目页「报价已更新」卡实时入流、历史报价卡经订单重查显当前价（视镜）；toast 分流（他页路标）归 #206。[#204](https://github.com/ZhangColin/aiplatform/issues/204) 新增） |

> **`workspace-created` 信号语义（[#61](https://github.com/ZhangColin/aiplatform-server/issues/61)）**：该信号表示「工作区记录已落库、容器后台置备中」，**不再是「容器就绪」**——创建即返回，docker 置备后台收敛到 ready/failed。前端以「记录存在」即可进对话；环境能力（跑代码/出 PRD）的就绪性以 REST 查询工作区 `status`（provisioning/ready/failed）为准，信号非权威（CONTEXT.md「平台通知」）。

## 智能体事件族

一次智能体运行的增量过程流；payload 必带 `runId`，`sessionId` 会话建立后携带，`projectId` 由业务编排桥接注入。订阅过滤：`?runId=`（「看某个运行才挂」的常规姿势）/ `?projectId=`，可叠用（AND）。

### 消息模型（parts 契约）

**消息 = 有序部件集合（parts）**（六源行业同构；词根基准 = agentscope 原生 part 族——工具调用请求/文件，mapper 保持薄翻译层，不强套某家词表）。run 进行中 = 对话区内一条**生长中的工作消息**（工具动作部件，带状态；部件按序竖排，无步骤分组），run 收口 = 消息定格。部件事件（`part-*`）由 base.agentscope 的部件映射表（`AgentscopePartsMapper`）从同一 AgentScope 事件流产出，全事件流恒挂（不限编码 run）。**模型文本不直播**（ADR-0057）：解说文本部件、自检播报部件、步骤清单部件随 #608 呈现面清场退役（`part-text` / `part-check` / `part-plan` 不再发射，存量事件按未知类型忽略）；叙事段守卫的机器语法吞段仍出 `part-signal` 脱轨信号。思考与代码补丁不进部件（思考与代码流不播——CONTEXT.md「智能体事件」）。`part-hint`（#437 决策时微指令部件）2026-11-12 退役——微指令是模型面指引、不进用户面；注入本体已随 #604 退役，存量事件按未知类型忽略。

下表「payload 字段」列的关联字段 = `runId`（必带）+ `projectId`（业务编排桥接注入）+ `sessionId`（会话建立后携带）。三类事件：

- **生命周期事件**（平台封闭集合，注册制）：run 级开场/挂起/收口；代码侧引用 `AgentEventTypes` 常量（base.eventhub）；
- **消息部件事件**（`part-*`，部件契约）：生长中的工作消息的部件增量；
- **引擎透传事件**（开放集合）：`data` 字段内为引擎 part 原样，下表列已知名型。

#### 生命周期事件

| type | payload 字段 | 说明 |
|---|---|---|
| `run-start` | `projectId` `runId` `prompt` `model` `engine` `agent`（可空） `slice`（可缺省） | 运行开始（runId 随 run 响应同值返回）。**引擎信息归一**：engine/model 之外携带智能体配置键 `agent`（业务侧 AgentProfile 的稳定键：`main` 主智能体对话轮 / `executor` 编码 run / `designer` 设计会话（[#289](https://github.com/ZhangColin/aiplatform/issues/289) 设计执行体——事件封闭集零新增、agent 扩值即达，ADR-0025）；无配置语境的一次性调用不携带）——前端呈现形态的登记锚：executor 起工作消息、main 进对话面（[#86](https://github.com/ZhangColin/aiplatform/issues/86) 单会话收敛后对话只有主智能体一座，无角色分支）、designer 同构直播卡（计划区＝设计物清单、agent 标识区分，呈现归 #290）。**工作消息头部标题扩载（[#118](https://github.com/ZhangColin/aiplatform/issues/118)）**：`slice` = `{ title }`——`title` 为用户语言标题（生成轨道 = 「系统初始化」、更新 run = 「系统更新」）；主智能体对话轮 / 一次性调用不携带（前端回落「正在做」）。切片序号与进度随切片机器退役（#602/#608），不再携带 `index`/`total`。**一场 run 恰一次**（#604 起编码 run 单发无重试——用户面 run 身份全程不变） |
| `error` | `projectId` `runId` `message` | 失败表达（非重试族：对话轮失败、挂起续跑失败、run 起跑前段失败、主智能体轮收口派发失败——锚定收口对话轮，如实呈现重提即兜底）。#604 起生成/更新 run 单发无重试（无「尝试环内中间失败」）——run 级唯一失败终态见 run-failed |
| `run-finish` | `projectId` `runId` `sessionId` `engine` `finish` `closing`（可缺省） | 运行结束（finish = 引擎结煞语 end / exceed_max_iters 等）；挂起轮不发（软终点，等答复续跑后收口）。编码 run 在收口判据落定后才发（#604 起生成/更新 run 单发：判据不过 = 该场 run 失败终态、不发 run-finish；设计座重试环尝试间不发、落定才发——run-finish 一场 run 至多一次、到达即真收口）。**收口扩载**（[#617](https://github.com/ZhangColin/aiplatform/issues/617)）：编码 run 的真收口携带 `closing` 对象（收尾卡的服务端权威事实，schema 见[下节](#收口扩载closing-schema617-五字段骨架)）——工作消息定格为收尾卡（五字段：用时/判据结果/变更清单/内核自述/预览入口）；**主智能体对话轮（咨询/纯追问）不携带**——无收尾卡。**定稿收尾卡（[#291](https://github.com/ZhangColin/aiplatform/issues/291)）经平台侧直接发射本事件**（无前置 run-start、无过程事件——定稿是用户显式动作收口、非智能体 run）：runId ＝ 定稿锚、sessionId ＝ 该件设计会话标识、closing 携 drafts 单条（定稿稿＋triggers 后续触发）；回访经对话史水合同载荷。收口载荷不含版本锚——成版走工作区 git 提交（#626，`Platform-Version`＋`Run-Id` trailer；旧 Run-Id trailer 成版已随 #605 退役） |
| `question-raised` | `projectId` `runId` `sessionId` `summary` `engineRef` `data` | 智能体挂起提问（ask_user，唯一挂起源——权限确认机制已随透明面化删除，[#219](https://github.com/ZhangColin/aiplatform/issues/219)；破坏性命令直通、过程经 `part-action` 动作卡透明可见）；`data.questions` 为前端问答卡投影，`data.toolCalls`（待确认工具最小面）为答复通道回传面；`data.questionOrdinal`＝「第 N 问」序号（#538/#549，平台转发前注入——缺口首次出现序 1..k，同缺口重派复用旧号，挂起卡也占号；水合读口顶层 `questionOrdinal` 重算为权威，前端仅试用访谈期渲染） |
| `run-failed` | `projectId` `runId` `message`（可缺省） `stopped`（可缺省） `failure`（可缺省） | 编码 run 终态失败收口（[#56](https://github.com/ZhangColin/aiplatform/issues/56)；#604 起生成/更新 run 单发无重试——失败/停止即终态，失败语义＝明确展示＋重跑即恢复）：轨道层在真终态落定点发射——生成轨道失败/停止即终态；迭代修改轨失败/停止即终态（不自动补派，用户重说即新一轮）。`runId` = 该场 run 的用户面标识。**run 失败为唯一失败终态**（不自动重跑：失败原因随本事件明确展示），前端失败档只认本事件。`message`（[#355](https://github.com/ZhangColin/aiplatform/issues/355) 机制检查·终态零原因补齐）＝一句用户面失败原因（末次错误现场经桥映射的人话定型，映射不中截首行定宽 120；原样留档归终态日志/失败留账）——前端失败档原因行消费，缺省不携带。`stopped`（[#359](https://github.com/ZhangColin/aiplatform/issues/359) 生成取消）＝`true` 时本终态由用户主动停止收口（失败账口径：计量如实落账；停止＝中断口径非判死——已落盘成果保留、不回滚，重跑入口照常可达）——前端失败档文案据此分岔「已停止」（不称中断/失败），`message` 携定型人话；缺省＝真失败（#604 单发：一场 run 失败即终态）。**失败面扩载**（[#418](https://github.com/ZhangColin/aiplatform/issues/418) 失败片落库面）：`failure` 对象＝逐尝试墙钟/模型配置/失败原因/本场累计（生成/更新 run 单发：attempts 至多一条；设计轨逐尝试各一条；schema 见[下节](#失败面-failure-schema418)），**与对话史失败留账行同载荷**（同收尾卡「SSE 扩载与落库同载荷」先例；先落库后发事件）——平台分析口径、前端不渲染（同 durationBreakdown），速度观测不再只看成功者 |
| `guide-reply` | `projectId` `runId` `prompt`（可缺省） `label` `text` | 平台侧定型文案直达对话面（带「平台」标签气泡，非智能体话语）。两类使用：兜底轻引导（[#47](https://github.com/ZhangColin/aiplatform/issues/47) 入口三分类的兜底分支——非意见非咨询输入的零产物路径，本事件即该次派发的全部）；悬空补救（ADR-0032·[#373](https://github.com/ZhangColin/aiplatform/issues/373)——需求梳理期轮悬空收口（无挂起问答且无 PRD，含判死/savePrd 失败形）的平台如实状态，`runId` 锚该轮、`text` 按可观测失败分形且恒带「继续」指路，同型复用零新增）。对话面交指令在途入待办时也复用本型发轻提示（#623，`runId` 锚对话面本轮）。`prompt` 为锚定的用户输入（事件到达重建对话面用；回访经对话史水合——#89）；在途轻提示与悬空补救不携带（用户气泡已由所属轮自落，不重复重建） |
| `intercept-card` | `projectId` `runId` `interceptId` `lane` `prompt` `sourceSelected` `deductionSource`（可缺省） `sourcePoolBalance`（可缺省） | 试用拦截卡（[#533](https://github.com/ZhangColin/aiplatform/issues/533) 计费②门②，ADR-0047「试用拦截面＝平台结构闸」）：试用项目（迭代期已生成）的**意见/兜底**输入在入口三分类后、受理前被拦下的事实呈现——平台侧卡片（显源池/随卡首次选源/确认扣减翻转/充值引导），**非智能体 run**（本事件即该次派发的全部，无 `run-start`）；咨询零产物短路天然放行、下单意图维持订单域旧轨（永不入拦）。`prompt`＝被拦输入原文（卡上引用呈现——**不重建用户气泡**：受理前的拦下不进对话面，恢复受理时由该轮 `run-start` / `guide-reply` 落气泡）；`lane` ∈ `OPINION` / `FALLBACK`（确认扣减后按车道恢复受理：意见回意见轮、兜底回定型轻引导——不重输）；`sourceSelected`＝扣减源是否已选定（`false`＝随卡首次选源「本项目余额／我的账户」，第一笔付费选择永远最先选；后台已充项目池＝入账即选择，`true` 免登录直接确认——销售演示动线）；`deductionSource`＝已选定源码位（1=账户余额 2=项目余额，未定为缺省不携带）；`sourcePoolBalance`＝源池余额快照（分；池未建不携带——直确认/充值引导的判定素材）。被拦输入原文随卡留存（`bil_intercepts` 待决行，**不丢失**）——确认扣减＝付费状态翻转（门②，`FlipGate=2` 留痕、不可逆）落定同事务；走充值引导/中途关闭后**卡可回**：读面 `GET /projects/{id}/intercept` 同形载荷（前端重载/回访重建；确认消费后读面归 null——卡随翻转熄灭）。确认与恢复受理的编排归 `POST /projects/{id}/intercept/confirm`（响应 `runId`＝受理运行标识） |

> **平台侧动作复用（#627 回到某版）**：回滚不是内核 run，但复用工作消息起止呈现——`run-start` 携 `agent=executor`、`slice.title`＝「回到版本 vN」、`engine`/`model`＝`platform`（无真实模型；前端据此起工作消息、登记编码 run 状态）；成功后 `run-finish` **不携 `closing`**（不出收尾卡）；失败 `run-failed` 携 `message`（失败原因＋两条恢复路：再点最新版「回到此版」，或提一句修改意见让内核按活库现状修齐）。不新增事件类型。

#### 消息部件事件（`part-*`）

| type | payload 字段 | 说明 |
|---|---|---|
| `part-attachment` | —— | **消息附件部件**（[#97](https://github.com/ZhangColin/aiplatform/issues/97) 圈注落地）：圈注锚随用户发言发送的载荷位——指认是对话输入的增强不是替代，<b>不随 run 过程流发射</b>：随 `POST /api/projects/{id}/messages` 的 `attachments` 进派发（渲染进主智能体 prompt 精确读取），并随用户发言落对话史 JSONB（`prj_conversation_entries.attachments`）、刷新回访经 `GET /api/projects/{id}/conversation` 水合回显圈注 chip（非截图）。schema 见[下节](#消息附件部件锚载荷-schema97) |
| `part-action` | `projectId` `runId` `sessionId` `engine` `source`（可缺省） `toolCallId` `toolName` `state` `label` `error`（可缺省） `path`（可缺省） | **工具动作部件**（动作卡）：开始／进行中／完成／失败全生命周期，同一 `toolCallId` 锚定跨状态原位更新；播报工具封闭表 = `write_file`／`edit_file`／`execute`（读类不播）。`state` ∈ `started`／`running`／`completed`／`failed`（动作层状态；run 层唯一失败终态仍是 run-failed）；`label` 无时态（时态由 state 表达）。`error` 仅 failed 携带（[#229](https://github.com/ZhangColin/aiplatform/issues/229) 错误/stderr 首行截断）。**`path`（[#618](https://github.com/ZhangColin/aiplatform/issues/618) 预览跟随）**：仅写文件类工具在参数落定／终态携带——`write_file`／`edit_file` 的 `path` 参数原形（相对/锚定两形自选，同 closing.files.path 口径）；命令类与读类不携带、参数在途（started）缺省。前端纯函数据此映射应用路由（剥路由组；组件／样式／库／布局／API／动态段／拦截段不切页），写到哪页预览切哪页 |

> **已退役**：`part-hint`（#437 决策时微指令部件，2026-11-12 摘除）——微指令是模型面指引、给用户看只添噪（生产实案：防重灌载体失效致同一提示每迭代重注刷屏），注入本体（DecisionHintMiddleware）已随 #604 run 尝试体旧机制退役，注入一并消失；用户面事件与前端渲染整体退役，存量事件按未知类型忽略。`part-text` / `part-check` / `part-plan`（#608 呈现面清场，2026-11-24 摘除）——ADR-0057 模型文本不直播、过程流＝工具动作流：解说直播、自检播报、步骤清单三件呈现退场，发射源同步删除，存量事件按未知类型忽略。

> **来源归属（#95 委派位）**：委派子智能体转发进父流的过程事件带 `source` 字段（值 = 子智能体声明名，引擎 source 路径的末段）；run 执行体自身产出的过程事件**不携带** `source`（缺省 = 执行体——用户面仍无角色标签，source 只用于过程呈现归属）。携带范围：引擎透传事件（`text`/`reasoning`/`tool`/`step-*`，payload 顶层）与消息部件事件（`part-signal`/`part-action`）。生命周期事件（`run-start`/`run-finish` 等）恒为执行体层级，不带 source。

#### 收口扩载 closing schema（#617 五字段骨架；#619 变更清单真实 diff）

run-finish 的 `closing` 对象——收尾卡（工作消息定格后的收尾部件，非另起的卡）的唯一权威事实源，**对话史落库（#89）复用同一载荷**。判据与清单以平台可观测事实为准（build＋探活、工作区 git diff），不由模型自报：

```
closing: {
  narrative:    "内核自述"           # 可缺省——内核自然终止时的末次文本（markdown 源）；
                                    # 空文本不携带（前端不出该块）。模型文本不直播，
                                    # 只在此落收尾卡（ADR-0057）。
  criteria: {                       # 判据逐件结果（收口硬判据的可视化）
    buildPassed:   true,            #   pnpm build exit 0 = 可构建
    previewPassed: true             #   8081 探活有响应 = 预览可用
  },                                #   双件全过才判完成（closing 在场 ⟺ 双件全过）
  files: [                          # 变更清单（#619：工作区真实 diff——跑前快照基线 +
                                    # 收口 numstat；命令行改的文件也计入。服务端不产出
                                    # patch 类事件，清单由本扩载权威承载）
    { path: "/src/App.jsx",         # path = 工作区锚定形（/ 前缀；git 仓库相对形归一）
      added: 12, removed: 3,        # 行数 = 对基线的真实 ± 行数（同路径按路径排序）
      patch: "diff --git ..." },    # 单文件只读 diff（迭代模式；首建不携带——目录汇总
                                    # 与首建不携 patch 归 #620）。patch 超限（300 行 /
                                    # 20KB 单文件、128KB 整卡）省略该键并标 patchOmitted
    { path: "/src/Huge.jsx", added: 500, removed: 0, patchOmitted: true }
  ],
  directories: [                    # 可缺省——首建（#620）目录汇总：先按目录呈现、
                                    # 点目录展开到 files 中该目录的文件；迭代不携带
                                    # （维持文件级清单＋单文件 patch）
    { path: "/src", fileCount: 1, added: 12, removed: 3 },
    { path: "/src/pages", fileCount: 1, added: 12, removed: 3 }
  ],                                #   path = 父目录锚定形（根目录文件归 "/"）；
                                    #   fileCount = 该目录文件数；± 行数为目录内合计。
                                    #   首建 files 元素不携 patch——前端不出现 diff 展开入口。
  filesUnavailable: true,           # 可缺省——快照或 diff 执行失败时 files 为空并标 true
                                    # （卡上呈现「变更清单不可用」，生成照常成功）
  durationMs: 183420               # 用时（起跑到收口；文件数/变更行数由 files 派生，卡上
                                   # 只在变更清单头部呈现一次）
  # 跑前快照提交（#619）：每场编码 run 起跑前自动 commit，提交信息只用平台标识
  # （snapshot <runId>）、不带 Rollback-From——不进用户面版本序列、无确认门、无新按钮。
  # 预览入口不入载荷：前端取既有预览查询（GET /projects/{id}/preview）的 URL——
  # 未就绪禁用、就绪走既有「新窗口打开」动作（#617）。
  # 旧平台句式摘要 summary 与「需求文档／系统」判定键（prdChanged/prdNote/
  # systemChanged/systemNote）自 #617 起不再写入；存量旧行由前端读侧回落呈现
  # （旧 summary 作内核自述文本、判据按收口不变式回落双 ✓——旧对话史不出坏卡）。
  # #619 前的 files 行数为工具参数活动量、无 patch/patchOmitted——前端同样按文件级
  # 呈现，只少 diff 展开（旧对话史不出坏卡）。
  # drafts / designLint 为设计线历史记录（设计线整条退役 #610/#613）。
  # modelTier 已随模型路由退役（#606，ADR-0055 #583）：路由判定留档不再携带；
  # 逐尝试生效配置仍见 durationBreakdown.attempts[].modelId（供给链归因）。

  durationBreakdown: {             # 阶段耗时分布（#111——平台分析口径，前端不渲染；
                                   # 四桶齐备 + 每迭代平台开销归因桶 + 逐尝试分布；
                                   # 计时源 = 引擎事件 createdAt 服务端真实口径；
                                   # #417 计量口径修真——verify 源账去重叠双计、委派族除名、
                                   # command 归组扩七族，桶计可信）：
    llmMs:      52000,             #   LLM 等待（执行体模型调用起止累计）
    toolsMs: {                     #   工具执行（参数落定→结果落定的纯执行窗，按工具名分桶）
      write_file: 3000,
      command: { install: 61000, build: 4500, dev: 800, test: 2400,
                 db: 300, http: 700, script: 1200, other: 500 }
                                   #   command 按命令归组（依赖安装 / 构建（build/tsc 等）/
                                   #   dev server / 测试 / 数据库客户端（psql 等）/ HTTP 探测
                                   #   （curl 等）/ 脚本运行（node/npx 一次性）兜底其他——
                                   #   #417 七族，other 不再吞掉全部命令耗时；只携带出现过的组）
    },
    closingMs:  300,               #   收口尾序（探活；落库自指不可测——载荷先于落库定型，量级极小忽略）
    overhead: {                    #   每迭代平台开销归因桶（#453——「窗与窗之间的平台耗时」
                                   #   逐桶可归因，残差饼图：attempts[].durationMs − llm −
                                   #   tools − overhead ≈ 未归因差值）：
      configMs: 120,               #     中间件每迭代动态查库（决策提示策略 + verify 自决开关；
                                   #     #453-2 起经 5s TTL 进程内缓存——命中窗口内近零）
      evalMs: 45,                  #     微指令判定体（轨迹快照提取 + 规则判定 + 判重回读扫描；
                                   #     #453-3 判定门跳过不重评估，健康段近零）
      eventPipelineMs: 900,        #     引擎事件平台处理链（事实观察 + 映射 + 部件 + 投影 +
                                   #     SSE 派发——含阻塞式 emitter.send 的真实墙钟）
      gateSkipped: 880,            #     判定门跳过迭代数（门断无信号可能即跳过重评估）
      gateEvaluated: 26,           #     判定门放行迭代数（可能产出信号、走全量评估）
      iterations: 906              #     闭合模型调用计数（每迭代恰一次模型调用）
    },
    attempts: [                    #   逐尝试分布——attempt / durationMs（#604 起单发无重试：
                                   #   成功收口恒一条；round 轮次键随段级外环退役不再携带）
      { attempt: 1, durationMs: 61000, modelId: "deepseek-v4-flash",
        llmMs: 52000, toolsMs: {...},
        overheadMs: { configMs: 120, evalMs: 45, eventPipelineMs: 900,
                      gateSkipped: 880, gateEvaluated: 26, iterations: 906 } }
    ]                              #   （尝试墙钟）+ 该尝试两桶 + 每迭代开销归因桶（#453，同
                                   #   run 级 overhead 口径）；
                                   #   modelId＝该尝试生效模型配置（键名＝持久化留档
                                   #   契约不改——#554 改名不改数据面）；
                                   #   中段崩的尝试带已闭合区间的
                                   #   部分观察（#323 失败留账——AgentTurnFailure 携带；前段失败/
                                   #   判据回调抛错无可携事实仍零桶）、墙钟照记
    # 一致性口径：桶计 + 未归因差值（平台管道 / 判据未过的核验 / 委派等待屏障等）
    # − 真并行重叠（父流与子智能体源事件的墙钟重叠段——并行收益双方各自入账）
    # ≈ durationMs——量级不符即埋点有洞（缝测守卫，合成事件流用例在
    # StageDurationFactsTest 闭合）
  }
}
```

- **到达即收尾卡**：`closing` 存在 ⟺ 编码 run 真收口 ⟺ 收口判据通过（build＋8081 探活双件）——前端过程明细（动作卡流水）收口后不常驻，收尾卡即凝聚物；`closing` 缺席的 run-finish（咨询/纯追问轮）无收尾卡。失败/停止不出现收尾卡（回归现状）。
- **五字段（#617；#619 变更清单改真实 diff；#620 首建目录汇总）**：用时（`durationMs`）/ 判据结果（`criteria` 逐件）/ 变更清单（`files`，跑前快照基线的真实 ± 行数；迭代携单文件 `patch`、超限标 `patchOmitted`；首建另携 `directories` 目录汇总——目录/文件数/± 行数、点展开到文件，且 files 不携 patch；快照或 diff 失败标 `filesUnavailable`）/ 内核自述（`narrative`，markdown）/ 预览入口（前端既有预览查询，不入载荷）。
- **阶段耗时分布（#111）**：`closing.durationBreakdown` 随收尾卡落库（对话史 JSONB），供平台事后分析时长归因（LLM 等待 / 工具执行 / 收口尾序 / 每迭代平台开销（#453 `overhead`——残差饼图）+ 逐尝试分布）；**用户面不呈现**——收尾卡「用时」行只认 `durationMs`，前端收窄读取、多余键不进 store。验证桶 verifyMs 随验证机器退役（#605）。
- **旧载荷读侧回落（#617）**：新写入不再携带 `summary` 与判定键；存量对话史中的旧 closing 由前端 `toWorkClosing` 回落（旧 summary 作内核自述文本、判据按收口不变式回落双 ✓），旧卡不空白不坏卡。

#### 失败面 failure schema（#418）

run-failed 的 `failure` 对象——run 失败终态（生成/更新 run #604 起单发无重试：本场失败即终态；用户停止同按失败账口径）的失败留账，**对话史落库（`prj_conversation_entries` kind=8 失败留账行，`failure` JSONB）与本载荷同 map**（同收尾卡「SSE 扩载与落库同载荷」先例；拼装单点在事件桥 `emitRunFailed`/`emitRunStopped`——先落库后发事件）。平台分析口径、前端不渲染（同 durationBreakdown）——失败作废可归因（#313 时代「33% 失败作废」从此可测），速度观测不再只看成功者：

```
failure: {
  stopped:  true,                  # 可缺省——用户停止口径（#359）：停止是用户动作、
                                   # 无失败原因可报（不携 error）；缺省＝真失败
  error:    "单条模型流总时长超上限…",  # 可缺省——失败原因（本场尝试错误现场原文，
                                   # 平台分析口径——用户面人话归 message 的桥映射）；
                                   # 轨道级失败（无尝试数据）只携本键
  durationMs: 183420,              # 可缺省——本场累计墙钟（起跑到终态收口）；轨道级
                                   # 失败面（只携 error）不携
  attempts: [                      # 可缺省——逐尝试分布（#323 失败留账口径；生成/更新
                                   # run #604 起单发：起跑后失败/停止恒一条——被中断
                                   # 尝试的部分观察、无可携事实则零桶一条；起跑前即被
                                   # 停止则空数组；设计轨逐尝试各一条）；轨道级失败面
                                   # 不携；条目形状同
                                   # durationBreakdown.attempts
    { attempt: 1,                  #
      durationMs: 61000,           # （attempt 尝试序号 / durationMs 尝试墙钟 /
      modelId: "deepseek-v4-flash",# modelId 该尝试生效模型配置（键名留档契约不改）/
      llmMs: 52000,                # llmMs·toolsMs 该尝试两桶；中段崩的尝试带
      toolsMs: {...} }             # 已闭合区间的部分观察、无可携事实仍零桶如实）
  ]
}
```

- **行为不变、仅增落库面**：既有失败路径（本场失败 / 用户停止）的收口与片状态落表口径零改——失败面是新增的观测面（落库 quietly：观测缺行不牵连终态收口）。
- **回访读面**：失败留账行经对话史读口（`GET /api/projects/{id}/conversation`）可读回（`failure` 载荷原样）；前端水合弃守未知 kind（#89 契约演进容错面），用户面零呈现。

#### 消息附件部件锚载荷 schema（#97；#286 扩 image 形态）

圈注（#73 决议，#133/#135 口径收口）——工具条四键「选择 / 改字 / 圈选 / 评论」：选择与圈选启用（点选锚定 / 拖框圈区域），改字与评论置灰待启用；条目收进发送框附件区，chip 形态为**序号 + 类型 + 摘要**（撤评语输入框，多条指代靠序号——描述统一写主输入框），随下一句自然语言发送；载荷只定要点：**结构化定位 + 标注类型**（不走截图识图——模糊锚定指错位置反噬信任）。锚为注入脚本回传的结构化 DOM 锚（选择器/文本引用），字段 schema 随本票定形：

```
attachmentType: "annotation" | "image"  # 附件种类：圈注（#97 首个）| 图片物料（#286 扩值）
annotation:                             # —— annotation 形态（结构化定位，非截图）
  kind: "select" | "circle" | "comment" # 标注类型：选择（点选锚定）/ 圈选（拖框圈区域）/ 评论（历史兼容，UI 不再产生）
  anchor:                               # 结构化定位（预览与前端跨源，postMessage 回传）
    selector: "CSS 选择器"               # 选择/评论：唯一指向目标元素
    text: "目标元素可见文本片段"          # 辅助定位与可读性（同文本多见时以选择器为准）
    region: { x, y, width, height }     # 圈选：页面级矩形区域（circle 专用，选择器可缺省）
  note: "可选评语"                       # 兼容历史只读回显（UI 不再产生评语）；多条圈注 = 多个附件部件可叠加
name: "logo.png"                        # —— image 形态（#286，ADR-0027）：原始文件名（chip 呈现用）
path: "materials/{tsid}-logo.png"       # 工作区路径引用（上传端点落物料目录后的 path 原样回传；载荷不带字节——
                                        #   存储正本＝工作区容器卷，取件走 files/raw inline 直出）
```

- 指认是对话输入的**增强不是替代**：附件部件与自然语言同句发送、被主智能体精确读取（结构化定位而非猜图）；
- **落地口径（#97，#138 注入口径收口）**：附件部件随 `POST /api/projects/{id}/messages` 的 `attachments` 进派发——渲染成主智能体 prompt 自然语言段精确读取（见 `AttachmentPrompt`），并随用户发言落对话史（`prj_conversation_entries.attachments` JSONB），刷新/回访经 `GET /api/projects/{id}/conversation` 水合回显附件 chip；注入脚本（`docker/workspace/annotation.js`）由预览网关 `sub_filter` 注入 HTML（ADR-0014 单源——serve.js 旧内联注入路径已删）、经 postMessage 回传结构化锚。
- **图片物料（#286，ADR-0027）**：上传走 `POST /api/projects/{id}/materials`（multipart 单文件、五格式、≤10MB 如实报错）落工作区物料目录，path 即随话发送的载荷引用；两形态各携带态字段（NON_NULL——圈注件无 name/path、物料件无 annotation）；物料不入版本流 git（输入面，ADR-0027 版本化规则）。

#### 引擎透传事件（开放集合）

| type | payload 字段 | 说明 |
|---|---|---|
| `text` | … + `source`（可缺省） + `data`（`delta` `blockId`） | 文本增量（前端按序拼接——对话面主智能体话语的消费源） |
| `reasoning` | … + `source`（可缺省） + `data`（`delta` `blockId`） | 思考增量 |
| `patch` | … + `data`（`path` `diff` `edits`） | 代码补丁（服务端现状不产出——名型占位） |
| `tool` | … + `source`（可缺省） + `data`（`toolCallId` `toolName` `phase: start|end`） | 工具调用（引擎原生粒度；用户面动作呈现归 part-action） |
| `step-start` / `step-finish` | … + `source`（可缺省） + `data`（`replyId`） | 步骤边界（模型调用边界；用户面不呈现——步骤分组已随 #115 退役，引擎透传名型保留） |

> **智能体事件桥**：AgentScope HarnessAgent 的事件经映射表翻译走同一通道同一信封——`engine=agentscope`，事件序 `run-start → 过程事件（透传 + 部件并行）→ run-finish/error`。透传映射表单点 = `AgentscopeEventMapper`；部件映射表单点 = `AgentscopePartsMapper`（解说切段与动作行的生产内核由 `NarrationSegments` / `ToolActionLines` 承载）。挂起（`RequireUserConfirmEvent`）唯一形态 = ask_user 提问 → `question-raised`（问答卡；`data.questions` 为前端投影：`[{header, question, multiple, custom(恒 true), options[{label}], recommended(可缺省)}]`（recommended＝推荐答案 #459：ask_user 推荐位，命中 options 才携带——问答卡据此标「推荐」chip 并预选缺省），`summary` 取问题文本；权限确认机制已随透明面化删除，[#219](https://github.com/ZhangColin/aiplatform/issues/219)——破坏性命令直通不挂起，过程经动作卡播报）；作答走问答作答通道（`POST /api/projects/{id}/questions/{qid}/answer`，答复文本）。挂起轮不发 `run-finish`；答复续跑归业务编排（从项目侧事实重建恢复私货 + 挂起事件 `data.toolCalls` 重建 ConfirmResult）；会话状态落 PostgreSQL（`cat_agent_state` 承载全部智能体会话），平台重启后按会话标识恢复续跑。

## 前端通用模块（约定）

站点布局级挂常开连接（未过滤订阅——只收通知族），项目页挂项目过滤连接（`?projectId=`——通知 + 智能体事件两族、断线补发面；新连接不补发，对话史经 REST 水合——#89）；模块统一管连接建立、心跳透明、自动重连、重连后 REST 重查钩子、按 type 分发回调——页面只声明关心的 type，不重复写连接逻辑。同一连接上的通知族由常开连接消费，项目页连接只分发智能体事件族（族内分工，防双连接双处理）。
