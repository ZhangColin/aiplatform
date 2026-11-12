# Demo 阶段快模型档（运营口径）

Demo 阶段的模型档位初档与改档 SOP。机制正本＝[ADR-0021](../adr/0021-agent-operational-config-persisted-skill-snapshot-install.md)（智能体运营配置落库、身份与配置分治）；本指引是其缺省档与切换动作上的执行面（#341）。

## 一、Demo 阶段识别

交付生命周期四阶段（需求分析 → Demo 体验 → 签约付款 → 生产承接）中，**Demo 阶段**承接「首 Demo ＋ 迭代」两类任务：

- **首 Demo** ＝ 用户确认 PRD 后平台自动起的首个生成 run；
- **迭代** ＝ 首 Demo 收口后，用户在对话区提意见经主智能体受理判定、平台据此自动起的更新 run。

两类任务由同一个 run 执行体（`AgentProfile.EXECUTOR`，寻址键 `executor`）承载——Demo 与正式生产同一条全栈生成轨、同一副协议（Demo 是系统的售前真实形态，不是降配假货）。因此 **Demo 阶段模型档 ＝ run 执行体的模型档**。

正式生产档为未来独立槽位（平台内生产机制未做，能力到位前不设），与本档互不牵连——首 Demo/迭代任务的模型档可独立于正式生产档配置（三座智能体各持自身缺省档位、运营配置面按 `agentKey` 各自覆盖）。

## 二、初档配置（缺省）

Demo 阶段激进用快模型（质量靠验证兜底——verify 验证子智能体（#352 三面验证）、收口判据核验承担质量面）。缺省档正本＝`AgentProfile.EXECUTOR` 枚举默认：

- **缺省档 ＝ 快模型档**（档位键 `flash`）；清空覆盖即回落此值。
- 设计执行体（`designer`）与主智能体（`main`）不在本档范围——设计线独立、档位另配；主智能体为对话/需求侧，档位另配。

## 三、改档 SOP（不发版）

档位经智能体运营配置机制切换，**不发版**——改档下一次命令构建即生效（每次命令构建动态查库取生效值，无缓存）。模型档位值为**语义键**（ADR-0031：`flash`/`pro`/`doubao-flagship` 这类运营短名，指向实际模型名与供应商通道——模型版本升级改档位行指向即可，逐处绑定不动）：

1. **改档**：`PUT /api/backoffice/agent-configs/executor`（机机五头 HMAC 签名；`X-User-Id`/`X-User-Name` 必带——写操作留痕）。请求体全量语义：`{"modelId": "pro"}` 换强模型档，`{"modelId": "flash"}` 回快模型档，`{"modelId": null}`（或纯空白/缺省）清空覆盖、回落枚举缺省（快模型档）。档位键即时校验（#367）：非空须在模型档位面（`/api/backoffice/model-tiers`）有行且启用——错值 400 当场拒（PRJ_074 缺行 / PRJ_075 停用），不再靠留痕回滚兜底；null 清空不校验。

**模型版本升级（方舟名带日期后缀、季度级更新）**：改档位行指向即全部引用自动跟上——`PUT /api/backoffice/model-tiers/{tierKey}` 改 `modelName`（同面可换通道、改显示名、停启用、开/关深度思考〔`thinkingEnabled`，缺省保持现值；缺省关＝显式关——不配置不默认拖慢首 token，#370〕）；建新档走 POST、删行有绑定守卫（被引用 409）。**换 key/换通道**（不发版不重启）：`/api/backoffice/model-providers` 面——apiKey 写收全量值、读面与留痕一律掩码（尾 4 位），`PUT` 不携 apiKey＝保持现值、携全量新值＝换 key（改完下一轮命令构建即生效）。curl 执行面：`aiplatform-server/scripts/backoffice-model-channel.sh`（`demo` 全流程演示〔可弃键自动清场〕＋单动词运维操作）。
2. **查生效值**：`GET /api/backoffice/agent-configs/executor`——生效 `modelId` ＋覆盖标记 `modelIdOverridden` ＋缺省预览 `defaultModelId`。
3. **回滚**：`GET /api/backoffice/agent-configs/executor/traces` 取某痕旧值快照，把旧值 `PUT` 回写（即一次新变更、留新痕——不做版本树）。

**边缘调用点同面改档（#366）**：项目取名（`naming`）、上下文压缩（`compaction`）、入口分类（`classification`）、技能预检（`precheck`）四件一次性轻调用的模型档位收编同一配置面——端点 `/api/backoffice/agent-configs/edge/{edgeKey}`（GET/PUT/traces 同制，值面仅模型档位：`{"modelId": "pro"}`；缺省一律 `flash`）。改档下一轮调用即生效（装配动态查库）。

**verify 验证子智能体档位与轮次预算（#424 片轻验常开）**：verify 子智能体（片轻验的执行侧）的档位与单场轮次预算收编在 **executor 行**（同一 PUT 通道）——

1. **缺省＝随执行体档**：`verifyModelId` 缺省 null＝随执行体**生效档**（含路由升档——重片升强档时 verify 同档跟随，不降；#406 裁决「验证判错代价高于省的秒数」）。
2. **改档**：`PUT /api/backoffice/agent-configs/executor` 带 `{"verifyModelId": "pro"}`（即时校验同 `modelId`：PRJ_074/075）；null 清空回落随执行体档。改完下一轮命令构建即生效（子智能体档位进智能体实例缓存键指纹）。
3. **轮次预算**：`{"verifyRoundBudget": 5}`（1-20 整数，越界 PRJ_078；null＝回落代码缺省 3＝首轮验证＋至多两轮修复复验）。预算＝单场防无限复验的护栏：verify 经 report_verification 登记达预算仍有未过项时，登记回执告知「停止修复—复验」，收尾卡验证统计带 `budgetExhausted` 如实标注。动态逐次读——改完下一轮登记即生效。
4. **查/回滚**：GET 同面（`verifyModelId`/`verifyModelIdOverridden`/`verifyRoundBudget`/`defaultVerifyRoundBudget`）；traces 前后快照含验证面两件。

**收口三硬门开关（#433 开关承载面；#435 翻转缺省——#432 Step 1 砍验证税落地态）**：验证在场闸（#424）、空转产出证明闸（#420）、整轨终验拦「生成完成」（#427）三件收口硬门各持独立开关，收编 **executor 行**（同一 PUT 通道）——

1. **缺省＝闸关（#435 起）**：三开关缺省 false＝#432 Step 1 正式落地——秒级确定性探活（8081 探活＋入口文件完整＋git 成版）是唯一收口要件；#433 的开关机制原样保留（闸代码全留仓）。
2. **切闸**：`PUT /api/backoffice/agent-configs/executor` 带 `{"verificationPresenceGateEnabled": true, "proofOfOutputGateEnabled": true, "finalVerificationGateEnabled": true}`（三件各自独立，可组合）＝一键切回旧行为（#424/#420/#427 现行为）；false/null＝闸关。留痕前后快照含三闸（回滚腿完整）。
3. **生效时机＝下一次闸判定**：闸判定处动态查库——在场闸/整轨终验在收口/完成判定时读，产出证明闸在 run 起跑（`forRun`）时读本 run 闸态；改完不发版即生效，进行中 run 不定格（产出证明闸按「起跑时读档」语义——本 run 恒定，对偶模型档）。
4. **闸开语义**：在场闸开＝本 run 无验证登记不收口；产出证明闸开＝零 diff 且无确认的空转片被拒（记拒绝史、段起点探针跑）；整轨终验开＝轨道完成前以核心旅程为轴终验、fail 拦「生成完成」。秒级确定性探活（8081 探活＋入口文件完整＋git 成版）不受三开关影响，常开。
5. **查生效值**：GET 同面（`verificationPresenceGateEnabled`/`proofOfOutputGateEnabled`/`finalVerificationGateEnabled` 生效态）。

**生成会话形态两开关（#436——#432 Step 2 单长会话落地＋切片机器退役）**：Demo 首建的生成会话形态由两开关决定（编排缝动态读档），收编 **executor 行**（同一 PUT 通道）——

1. **缺省＝单会话开＋并行机器关（#436 落地态）**：`singleSessionGenerationEnabled` 缺省 true＝分段任务在同一执行会话槽位（`coder-{projectId}-single`）内连续追加（每段新对话＋交接传递，#434 T2 排练定稿形态）；`parallelSliceMachineEnabled` 缺省 false＝fork 隔离工作区/波调度/并行执行/合并机器整体关。切片计划保留但降级为进度/预览叙事，不再驱动新 session；依赖图产出保留用于进度展示；断点续跑跨进程重启语义保留。
2. **切回切片机器态**：`PUT /api/backoffice/agent-configs/executor` 带 `{"singleSessionGenerationEnabled": false, "parallelSliceMachineEnabled": true}`——每片新会话（`coder-{projectId}-slice-n`）＋宽波 fork 并行（#351 现行为）；半开（单会话关＋并行关）＝每片新会话但全轨主工作区串行、零 fork。两开关独立可组合；null 归一缺省（单会话 null→true、并行 null→false）。留痕前后快照含两开关（回滚腿完整）。
3. **单会话形态失败语义**：段级自愈＝同会话返工提示（原地修重试环），重试耗尽转终态失败、**不传段级回滚钩**（工作区不回滚、无重跑轮）；续跑现状盘点按脏续口径（未回滚、保留已对的工作——与中断段同口径）。被失败片阻塞的后续片跳过不派（#429 不变）。
4. **生效时机＝轨道起跑时读档**：改完不发版即生效，进行中 run 不定格（同模型档语义）。
5. **查生效值**：GET 同面（`singleSessionGenerationEnabled`/`parallelSliceMachineEnabled` 生效态）；编排行为锚定见 `GenerationAppServiceTest` given_single_session_ 系用例。

**verify 自决开关（#442，#441-1——#439 终裁 verify 窗 20.9min〔真实独占估 12-15min〕的最大单一可收窄面）**：执行体生成过程中自决调用 verify 子智能体（#435 自决化）的提示层收窄开关，收编 **executor 行**（同一 PUT 通道）——

1. **缺省＝自决开（#435 现状维持）**：`verifySelfDecisionEnabled` 缺省 true＝执行体按工作协议第 9 条自决委派 verify 自测（验证不是收口要件）。
2. **翻转关闭**：`PUT /api/backoffice/agent-configs/executor` 带 `{"verifySelfDecisionEnabled": false}`＝生成过程中不自发委派 verify、不发起修复—复验循环——执行体 ReAct 循环中间件（`VerifySelfDecisionMiddleware`）每迭代动态查库，关闭态注入一条提示层指令（每轮〔派段〕一次——判重载体 ctx 属性袋＋消息列回读兼容源，#481）；**平台驱动验证腿不受影响**（三闸回执／整轨终验编排任务说明要求的验证照常执行），收口唯一硬判据仍是 8081 探活三件套。false/null 归一缺省（null＝开）；留痕前后快照含本开关（回滚腿完整）。
3. **生效时机＝下一次迭代**：中间件每迭代动态查库、装配不进 agent 实例缓存键——改完不发版即生效；防重灌口径（#481 载体换位）：注入消息不落 state、不随消息列留存（仅注入那一迭代的模型调用可见），同派段后续迭代不重注、新派段首迭代再注入一次。
4. **查生效值**：GET 同面（`verifySelfDecisionEnabled` 生效态）；中间件行为锚定见 `VerifySelfDecisionMiddlewareTest`。

## 四、进行中 run 不定格

配置读取时机＝**起跑时读档**：模型档位在每次命令构建（下发 run/对话轮）时取一次生效值，随命令交给智能体工厂；工厂缓存键含模型串，新值即新实例。因此**改档不影响在途 run**——在跑的那一段沿用起跑时读到的档位跑完，改档只影响**下一轮**命令构建。修订中的工作协议（systemPrompt）同理。verify 档位同律（随命令构建解析）；轮次预算更细——逐次登记时动态读，改预算对在跑的场也即刻生效（预算是防烧护栏，越早生效越好）。

## 五、边界与模型路由协同（#354）

- **模型路由（#354 已落地；信号面 #426 修订——规模标签替换枚举密度、文件面排除基座基数重校；结构性信号开关 #454——ADR-0037 用户裁决）**：本档是**配置态初档**——路由判定机制（ADR-0030）在人工定档上叠自动选档：按任务特征可观测信号（历史失败〔重试/回滚重跑〕、文件面大小〔应用文件面＝已跟踪文件数扣基座基数，基数随镜像 /opt/baseline 现场计数单源〕、规模标签映射〔小/中/大封闭集，计划自报；集内标签升强档〕）任一命中即升强档，否则回落人工档。**结构性信号默认关（#454 起新缺省）**：文件面/规模标签两信号各持信号级开关——缺省 false＝不参与升档判定（观测值照留进判定快照、标未参与——对照实验数据不断档），true＝恢复对应判据（阈值/映射旋钮照旧生效）；**失败重试信号不受两开关影响、常开**。理由：文件面只增不减（过阈值后永不回落），「越大越强模」激励错位；规模标签同理是静态自报。生命周期口径：先都关掉观察，效果不好随时开回来；确认没这两个信号也没问题，再拆功能（文件访问面收敛见 #454 backlog 段）。**协同口径＝路由判定优先、人工覆盖兜底**：
  - 路由开（缺省）＝信号升强档优先，无信号回落人工档兜底——第三节的人工改档（`PUT` 带 `modelId`）即换**兜底基础档**（快档位可经覆盖配置、不关路由），信号升档可越过覆盖；
  - 路由关（`{"routingEnabled": false}`）＝**钉死位**——人工档恒生效（覆盖 → 缺省，行为与 #354 前完全一致；对账/对比场景的定档通道）。
  - 强模型档、文件面阈值与升档标签映射可配（`routingStrongModelId`／`routingFileCountThreshold`／`routingEscalatingSizeLabels`，缺省 `pro`／30／`["大"]`，null 清空回落代码缺省；空数组＝空映射＝规模标签信号整体关停；token 越出封闭集 PRJ_079），同第三节改档通道、不发版。
  - **结构性信号两开关可配（#454）**：`routingFileCountSignalEnabled`／`routingSizeLabelSignalEnabled`（缺省 false/null＝关；true＝恢复对应判据）——`PUT /api/backoffice/agent-configs/executor` 同通道，留痕前后快照含两开关（回滚腿完整）；GET 同面读生效态。观测兼容：收尾卡 `closing.modelTier.signals` 照旧全量留档（关了的信号留观测值＋`fileCountSignalEnabled`/`sizeLabelSignalEnabled` 参与标记），对照实验数据不断档。
- **路由决策可查**：每段收尾卡 `closing.modelTier`（哪档、来源 routed/pinned/default、升档理由、判定依据快照——随对话史落库）+ `durationBreakdown.attempts[].modelId`（逐尝试档位）+ 结构化日志；失败段以日志为留档面。
- **正式生产档**：平台内生产机制设计未做，正式生产档不设；能力到位再立独立槽位。

## 六、活体对比留档（速度 / 质量）

速度目标（与 Replit 相当，首版 40 分钟量级带）的推算依赖 Demo 阶段快模型档与模型路由的实测对比。做法（真运行是唯一准绳）：

1. 起 dev 栈（`scripts/dev.sh`），建项目 → 改一条需求触发首 Demo（或对已有项目发一条迭代意见）跑一轮；
2. 记录本轮**时长**（run 级已运行时钟 / 收尾卡轮末统计）与**质量**（预览可用性、验证子智能体 ✅/❌ 统计（收尾卡验证统计）、收口是否需重试）；
3. 对照轮次按同一任务各跑一轮，逐轮记录时长、质量、项目账 token 与**路由决策留档**（收尾卡 `closing.modelTier`——哪段用了哪档、为什么）：
   - **路由开（缺省态）**：`GET /api/backoffice/agent-configs/executor` 确认 `routingEnabled=true` 且 `modelIdOverridden=false`；
   - **路由关（对照基线）**：`PUT /api/backoffice/agent-configs/executor` 写 `{"routingEnabled": false}`；
   - **钉死强档（质量基线）**：`PUT` 写 `{"routingEnabled": false, "modelId": "pro"}`（钉死位——人工档恒生效）；
4. 对比结果留档（时长量级、质量差异、项目账 token、路由判定分布）——路由开轮供速度目标推算，钉死强档轮为质量基线，路由关轮为对照基线。

**enableThinking 开关对比（#369 留下、#370 开关后台化后可做）**：方舟 DeepSeek V4 系均标深度思考，装配显式表态不留给模型默认；开关跟档位走（`prj_model_tiers.thinking_enabled`，缺省 false＝显式关——#369 活体结论「维持 false」）。对比法：档位维护面 `PUT /api/backoffice/model-tiers/{tierKey}` 带 `"thinkingEnabled": true` 开→同一任务跑一轮，与关态轮对比首轮延迟/质量/token——改完下一轮命令构建即生效（通道指纹含 thinking 腿，进行中 run 不定格），不发版不重启；结论按档回写（若开态显著拖慢首 token 则维持关，用户故事 12 承诺不破），留痕可回滚。成本面联动：跑完经成本观测面核 volcengine 模型行有单价匹配（单价表已开三档位实际模型行，#369 种子）、无 unpriced 警示。
