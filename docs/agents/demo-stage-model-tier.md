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

- **缺省档 ＝ 快模型档**（`deepseek-v4-flash`）；清空覆盖即回落此值。
- 设计执行体（`designer`）与主智能体（`main`）不在本档范围——设计线独立、档位另配；主智能体为对话/需求侧，档位另配。

## 三、改档 SOP（不发版）

档位经智能体运营配置机制切换，**不发版**——改档下一次命令构建即生效（每次命令构建动态查库取生效值，无缓存）：

1. **改档**：`PUT /api/backoffice/agent-configs/executor`（机机五头 HMAC 签名；`X-User-Id`/`X-User-Name` 必带——写操作留痕）。请求体全量语义：`{"modelId": "deepseek-v4-pro"}` 换强模型档，`{"modelId": "deepseek-v4-flash"}` 回快模型档，`{"modelId": null}`（或纯空白/缺省）清空覆盖、回落枚举缺省（快模型档）。档位名不做白名单校验——错值致新会话失败，经留痕写回可回滚。
2. **查生效值**：`GET /api/backoffice/agent-configs/executor`——生效 `modelId` ＋覆盖标记 `modelIdOverridden` ＋缺省预览 `defaultModelId`。
3. **回滚**：`GET /api/backoffice/agent-configs/executor/traces` 取某痕旧值快照，把旧值 `PUT` 回写（即一次新变更、留新痕——不做版本树）。

## 四、进行中 run 不定格

配置读取时机＝**起跑时读档**：模型档位在每次命令构建（下发 run/对话轮）时取一次生效值，随命令交给智能体工厂；工厂缓存键含模型串，新值即新实例。因此**改档不影响在途 run**——在跑的那一段沿用起跑时读到的档位跑完，改档只影响**下一轮**命令构建。修订中的工作协议（systemPrompt）同理。

## 五、边界与模型路由协同（#354）

- **模型路由（#354 已落地，片复杂度信号 #361 重定）**：本档是**配置态初档**——路由判定机制（ADR-0030）在人工定档上叠自动选档：按任务特征可观测信号（历史失败〔重试/回滚重跑〕、文件面大小〔工作区已跟踪文件数〕、片复杂度〔切片功能条目数——切片描述的枚举段数〕）任一越阈即升强档，否则回落人工档。**协同口径＝路由判定优先、人工覆盖兜底**：
  - 路由开（缺省）＝信号升强档优先，无信号回落人工档兜底——第三节的人工改档（`PUT` 带 `modelId`）即换**兜底基础档**（快档位可经覆盖配置、不关路由），信号升档可越过覆盖；
  - 路由关（`{"routingEnabled": false}`）＝**钉死位**——人工档恒生效（覆盖 → 缺省，行为与 #354 前完全一致；对账/对比场景的定档通道）。
  - 强模型档与两阈值可配（`routingStrongModelId`／`routingFileCountThreshold`／`routingSliceFeatureThreshold`，缺省 `deepseek-v4-pro`／60／4，null 清空回落代码缺省），同第三节改档通道、不发版。
- **路由决策可查**：每段收尾卡 `closing.modelTier`（哪档、来源 routed/pinned/default、升档理由、判定依据快照——随对话史落库）+ `durationBreakdown.attempts[].modelId`（逐尝试档位）+ 结构化日志；失败段以日志为留档面。
- **正式生产档**：平台内生产机制设计未做，正式生产档不设；能力到位再立独立槽位。

## 六、活体对比留档（速度 / 质量）

速度目标（与 Replit 相当，首版 40 分钟量级带）的推算依赖 Demo 阶段快模型档与模型路由的实测对比。做法（真运行是唯一准绳）：

1. 起 dev 栈（`scripts/dev.sh`），建项目 → 改一条需求触发首 Demo（或对已有项目发一条迭代意见）跑一轮；
2. 记录本轮**时长**（run 级已运行时钟 / 收尾卡轮末统计）与**质量**（预览可用性、验证子智能体 ✅/❌ 统计（收尾卡验证统计）、收口是否需重试）；
3. 对照轮次按同一任务各跑一轮，逐轮记录时长、质量、项目账 token 与**路由决策留档**（收尾卡 `closing.modelTier`——哪段用了哪档、为什么）：
   - **路由开（缺省态）**：`GET /api/backoffice/agent-configs/executor` 确认 `routingEnabled=true` 且 `modelIdOverridden=false`；
   - **路由关（对照基线）**：`PUT /api/backoffice/agent-configs/executor` 写 `{"routingEnabled": false}`；
   - **钉死强档（质量基线）**：`PUT` 写 `{"routingEnabled": false, "modelId": "deepseek-v4-pro"}`（钉死位——人工档恒生效）；
4. 对比结果留档（时长量级、质量差异、项目账 token、路由判定分布）——路由开轮供速度目标推算，钉死强档轮为质量基线，路由关轮为对照基线。
