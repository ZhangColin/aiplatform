# #432 Step 4 后台接口盘点：admin 配置面变更清单（#438）

> 票 [#438](https://github.com/ZhangColin/aiplatform/issues/438)（#432 Step 4），2026-11-10。
> 范围：#432 四步全部落地后的 backoffice（机机签名后台）接口。
> 关联：spec #432、实施票 #433 / #435 / #436 / #437、admin 侧配合票
> [semgravio-admin-web#98](https://github.com/ZhangColin/semgravio-admin-web/issues/98)（互链）。

## 口径

- 接口正本＝swagger 注解（ADR-0001：swagger 唯一契约），本文是**变更清单**（增量），
  不重复既有契约全文。
- 涉及端点（路径全部不变，均为既有端点扩字段）：
  - `GET /api/backoffice/agent-configs/{agentKey}`（main/executor/designer）
  - `PUT /api/backoffice/agent-configs/{agentKey}`（全量写）
  - `GET /api/backoffice/agent-configs/{agentKey}/traces`
  - `GET/PUT /api/backoffice/agent-configs/edge/{edgeKey}`（naming/compaction/
    classification/precheck——本批次**零改动**）
  - `PUT /api/backoffice/agent-tools/{toolName}`——本批次**零改动**（写路径透传新列，
    值面无动不落痕，语义同前）
- 生效语义不变口径：所有开关/参数判定处动态查库不缓存——运营面改完下一次判定即
  生效，不发版、进行中 run 不定格（admin 页面可提示「即时生效」）。

## 一、语义变更（调整项——同名字段行为或缺省变了）

| # | 字段 | 变更 | 语义说明 |
|---|------|------|----------|
| A1 | `verificationPresenceGateEnabled` / `proofOfOutputGateEnabled` / `finalVerificationGateEnabled`（收口三硬门） | #433 新增时缺省 **true＝开（当时现行为）**；#435 V54 翻转缺省 **FALSE＝闸关** 且 **存量行一并 UPDATE 为 FALSE** | 闸关（缺省）＝收口唯一要件是秒级确定性探活三件套（8081 探活＋入口文件完整＋git 成版）；闸开＝恢复旧行为（无验证登记不收口／零 diff 无确认不收口／终验 fail 拦生成完成）。**仅 executor（run 执行体）行消费**，main/designer 行同列恒无消费。**PUT null 归一＝闸关**——与工具/路由开关「null＝开」方向相反，页面占位缺省须按字段区分 |
| A2 | `verifyModelId` / `verifyRoundBudget`（验证面两件，#424 既有） | 字段与校验不变，**消费语义降级**（#435）：verify 子智能体从「收口必查」降为执行体**自决自用的自测工具** | `report_verification` 登记不再是收口要件；登记数据仍进工作消息与收尾卡（观测面不动）。admin 页面展示文案应从「收口要件」改述为「自测工具参数」；`verifyRoundBudget` 仍约束单场修复—复验轮次（缺省 3，1-20，PRJ_078） |
| A3 | 空命令/缺省字段归一（PUT 全量语义） | 空请求体现等价于：覆盖两件清空＋工具/路由开关开＋**三闸关＋单会话开＋并行关＋注入开** | admin 前端保存时必须携带全部开关字段，否则按上列缺省落库（见「迁移陷阱」） |

## 二、新增项（本批次新出现的配置字段）

| # | 字段（类型） | 缺省值 | 校验/错误码 | 语义说明 | 消费面 |
|---|--------------|--------|--------------|----------|--------|
| N1 | `singleSessionGenerationEnabled`（bool，V55，#436） | **true＝单会话开**（PUT null 同） | — | 阶段 0＋全部切片在同一执行会话槽位连续追加（每段新对话＋交接传递，段级自愈＝同会话返工提示）；false＝旧「每片新会话」形态 | 仅 executor 行 |
| N2 | `parallelSliceMachineEnabled`（bool，V55，#436） | **false＝并行机器关**（PUT null 同） | — | fork 隔离工作区/波调度/并行执行/合并全关、全轨主工作区串行；true＝宽波 fork 并行旧行为。仅单会话关闭后有消费面 | 仅 executor 行 |
| N3 | `decisionHintsEnabled`（bool，V56，#437） | **true＝注入开**（PUT null 同） | — | 执行体 ReAct 循环每迭代由分类器判定注入一条微指令；false＝全停原样透传 | 仅 executor 行 |
| N4 | `decisionHintErrorStreak`（int?，V56） | 代码缺省 **3**（null＝回落） | 2-10，越界 PRJ_081 | 失败止损阈值：同类错误连续失败 ≥ 此值注入「换策略」微指令 | 仅 executor 行 |
| N5 | `decisionHintStackCap`（int?，V56） | 代码缺省 **3**（null＝回落） | 1-3，越界 PRJ_082（研究口径：堆叠 >3 收益递减转负，运营面不放宽） | 单轮（段）最多注入几条不同微指令 | 仅 executor 行 |
| N6 | `decisionHintDisabled`（string[]?，V56） | null/空数组＝全启用 | token 须属封闭集 `failure_stop_loss` / `anti_mock` / `context_economy` / `parallel_tools`，非法 PRJ_083 | 微指令禁用集（库条目键）。**库正文在代码正本，运营面只有开关与参数——无库 CRUD 接口** | 仅 executor 行 |
| N7 | 响应面配套字段 | — | — | GET/PUT 回执与 traces 快照同步新增：`decisionHintErrorStreakOverridden` / `defaultDecisionHintErrorStreak` / `decisionHintStackCapOverridden` / `defaultDecisionHintStackCap` / `decisionHintDisabledOverridden`，及三闸/两开关/注入四件的**前后快照列**（留痕全量值快照口径不变） | — |

注：三闸字段本身是 #433（V53）新增列、#435（V54）翻转缺省——对 admin 前端而言同属
「本批次要接的新字段」，列入 A1 一并处理。

## 三、计划偏差声明（#432 Step 2/3 原计划 vs 落地，admin 页面不须做的）

| 原计划配置面 | 落地事实 | admin 影响 |
|--------------|----------|------------|
| 决策时注入**分类器档**（flash 档模型，可配置） | 落地为**确定性规则分类器**：判定纯函数化、零模型调用、零成本（依据 #434 排练 + 成本口径，见 docs/research/2026-11-09-single-session-60min-rehearsal.md 定夺段） | **无分类器档位配置项**，页面不做分类器档下拉；微指令调优面＝N3-N6 四件 |
| **压缩策略**重新校准（#432 Step 2 担心 #379 offload 关闭的隐患） | #436 按 T2 排练数据定夺＝**维持现状**：框架压缩在长会话下工作正常；压缩触发阈值在 yml（`compaction-trigger-tokens=400_000`），压缩档位走既有边缘点 `compaction`（#366，库值优先缺省 flash） | **无新增后台配置项**；页面如需展示压缩档位，走既有 `/edge/compaction` 端点（无变化） |

## 四、给 admin 前端的迁移陷阱（重要）

1. **PUT 全量替换语义 + 新字段缺省**：若旧版配置页不带新字段保存，三闸会被刷成关、
   单会话刷成开、并行刷成关、注入刷成开。与当前缺省一致所以「看似无害」，但
   **运营若曾把闸打开，旧页面一次保存即静默刷回关**——配置页改版上线前，提醒运营
   不要用旧页面保存 executor 配置。
2. **缺省方向不一致**：`webSearchEnabled`/`fetchUrlEnabled`/`routingEnabled`/
   `singleSessionGenerationEnabled`/`decisionHintsEnabled` 缺省 **开**；三闸与
   `parallelSliceMachineEnabled` 缺省 **关**。表单占位/重置按钮须按字段区分，不能
   统一「缺省开」。
3. **消费面标注**：三闸/单会话/并行/注入四件**仅 executor 行消费**——main/designer
   两页可隐藏或置灰这些区块（GET 回执有值但无消费面），避免运营在错误位置调开关。
4. **新错误码**：PRJ_081（止损阈值越界）/ PRJ_082（堆叠上限越界）/ PRJ_083（禁用集
   非法 token）——表单前端校验可直接锚定 2-10 / 1-3 / 封闭集四键。

## 五、互链

- spec：#432（Step 4 即本盘点）· 实施：#433（三闸开关化）/ #435（缺省翻关＋verify
  降级）/ #436（单会话＋并行开关）/ #437（微指令四件）
- admin 侧配合票：ZhangColin/semgravio-admin-web#98（后台配置页改版——接本文
  一/二/四节，声明偏差见三节）
