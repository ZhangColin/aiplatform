# 盘点：现有访谈链与沉淀相关机制事实面（#448）

2026-11-11 · wayfinder:research（map #446）· 只摆事实，不做设计建议
代码基线：develop @ d6ee1bb（#445 收口后）

## 面 1：访谈编排——「每轮一问」硬契约与问答卡

### 契约的两层落点

**工作协议文本层**（对模型的约束）：

- 主智能体工作协议全文在 `aiplatform-server/src/main/java/com/semgravio/aiplatform/business/project/domain/model/AgentProfile.java:41-158`（`MAIN` 枚举的 systemPrompt，即身份缺省正本）。
- 「每轮一问」条款在 `AgentProfile.java:45-56`：第 1 条开场即调 ask_user；第 2 条「提问一律经 ask_user 工具（禁止把问题写在正文里）……每轮只问一个问题……options 给 2-4 个候选……multiple 传 true」；第 3 条「一次只解决一个缺口……不得替用户假设后收敛」；第 4 条判定明确标准（目标用户/核心场景/范围边界/关键约束四方面有用户确认信息）；第 5 条判定明确即调 savePrd（`AgentProfile.java:57-75`）；第 6 条催促收敛优先（`:76-81`）。
- 能力边界正本（第 18 条）把 ask_user 定义为「每轮一问澄清」：`AgentProfile.java:142-153`。

**平台代码 enforcement 层**（对轮收口的校验，模型不遵守时的结构性后果）：

- 硬契约的 enforcement 不在工具层而在**轮落定观测点**：`MainAgentAppService.dispatchOnTurnClose`（`aiplatform-server/src/main/java/com/semgravio/aiplatform/business/project/application/MainAgentAppService.java:771-824`）——需求梳理期（未出 PRD）用户轮合法收口只有两形：挂起问答在场（`agentClient.hasAskingToolCall`，`:778-783`）或 PRD 已产出（`project.getPrdProducedAt() != null`，`:804` 之后）；两形皆无即悬空（ADR-0032）。
- ask_user 是问答挂起的**唯一来源**（`AgentTool.java:28`：「每轮一问的澄清工具（问答挂起的唯一起源——编排链路）」）。

### 问答卡结构

- 工具 schema（`AskUserTool.java:41-57`）：入参四键——`question`（必填）、`header`（主题短标签）、`options`（字符串数组，「无可不填，用户可自由输入」）、`multiple`（boolean，缺省 false 单选）。工具自检恒 ASK（`:63-68`），调用即挂起。
- 前端契约投影（`aiplatform-server/src/main/java/com/semgravio/aiplatform/base/agentscope/AgentscopeEventMapper.java:127-179`）：question-raised 载荷 questions[] = `{header, question, multiple, custom, options[{label}]}`；`custom` 恒 true（无选项也保留自由输入）；`multiple` 从工具入参投影（多选勾选提交，缺省单选点即答）；`options` 字符串列表 → `{label}` 对象列表。
- 前端渲染件 `aiplatform-web/src/components/project-page/question-card.tsx:51-110`：`multiple` 决定勾选/点答形态，有 options 出选项、无 options 纯自由输入。
- **「推荐选项」标注：全仓无此能力**。`AskUserTool` schema、事件 mapper 投影、前端问答卡均无 recommended/推荐位字段（`recommended` 在后端主代码与前端 src 零命中）。当前唯一近似是协议文本「options 给 2-4 个候选」（`AgentProfile.java:50`），选项无结构化优先级/推荐语义。

## 面 2：悬空与收口纠偏链（ADR-0032 / ADR-0036）

权威文档：`docs/adr/0032-interview-turn-close-invariant-and-dangling-takeover.md`、`docs/adr/0036-interview-dangling-corrective-chain.md`。

### 触发条件

- 需求梳理期（未生成且 `prdProducedAt == null`）用户轮收口时无挂起问答且无 PRD 产出（`MainAgentAppService.java:804-822`）。触发面三类（CONTEXT.md「悬空」词条，`:67-69`）：关键一步失败只存在于智能体叙述（savePrd 失败）、轮被判死中断、模型把提问写正文未走 ask_user（#445 实测约占新会话 15%，会话级锁定）。
- 迭代期（PRD 已出）不适用（`MainAgentAppService.java:879-881`）；咨询轮豁免；平台轮（补产/切换通告）不入契约（`:807-808`）。

### 纠偏轮机制（ADR-0036，#445）

- 命中不立即播报——先走 `dispatchCorrectiveChain`（`MainAgentAppService.java:948-993`）：
  - 纠偏轮①：主智能体生效档（库值优先缺省回落，同对话轮）＋确定性微指令。提问形令用 `CORRECTIVE_REASK_PROMPT`（`:684-690`，教规则＋令当场经 ask_user 重问）；savePrd 失败形用 `CORRECTIVE_RESAVE_PRD_PROMPT`（`:692-696`）。
  - 纠偏轮②：升强档 `routingStrongModelId`（缺省 pro，运营可换不发版）＋同款微指令（`correctiveModelOf`，`:1017-1028`）。
  - `routingEnabled=关` 时第 2 轮返回 null——①失格即直接播报（`:1022-1024`）。
- **封顶两轮**：`CORRECTIVE_ROUND_CAP = 2`（`:937`）。每轮新 runId、会话执行器串行、回复照常落对话史、纠偏轮本身是平台轮不入契约不递归（ADR-0036 约束节）。
- 成功判据 `correctiveSettled`（`:996-1009`）＝ADR-0032 合法收口形两形（挂起问答在场或 PRD 已产出；项目归档/缺档即停链不播报）。

### 双失格与接管

- 双失格才 `markDanglingInterview`（`:1057-1059` 起）：置 `danglingInterviews` 标记（进程内 Set，`:137-141`）＋补如实状态消息（点名/泛形两形文案，恒带「继续」指路）。
- 标记在场时入口派发绕过三分类直达意见轮（DispatchAppService），至下一次合法收口解除（挂起或 PRD 产出即清，`:781`、`:826`）。
- **轮失败（异常收口/判死）不经纠偏链**：catch 侧 `failUserTurn → remedyFailedInterviewTurn` 即时点名播报（`:1038-1048`；ADR-0032 rationale：环境性失败重试照样失败）。
- 同批护栏：ask_user 空 input 畸形挂起——平台自动以错误结果答复该工具调用（metadata 通道），模型当场重问；呈现面兜底文案 `BLANK_QUESTION_FALLBACK`（`AgentscopeEventMapper.java:153-170`）。

### 与确认门的关系

- 两者串行不重叠：悬空契约只管「PRD 产出之前」的轮收口；PRD 产出（合法收口形二）即解除悬空，之后用户过确认门（面 3）。纠偏链救回的终点态就是「PRD 已产出」，此时确认门仍是下一个用户动作。
- 确认门被拦（PRJ_053 阻碍级待定项）不触发悬空——那是用户面动作不是轮收口。

## 面 3：PRD——产出处、版本语义、确认门、冻结锚、大小改分流

### 七章节产出处

- 产出动作＝主智能体调 `savePrd` 工具（`SavePrdTool.java:35-77`）：`content` 传完整 PRD markdown 覆盖写工作区 `docs/PRD.md`（docker exec，经 `PrdArtifactAdapter` 业务正本路径），`summary` 必传（修订事实观测口）；落盘回调置 `prdProducedAt` 状态位＋发 document-updated。
- 七章节模板由 **prd-writing 技能**承载：`aiplatform-server/src/main/resources/skills/prd-writing/SKILL.md:1-13`——固定七章节＝需求背景、目标用户、核心场景、范围边界、关键约束、清单章（系统形＝功能清单/设计形＝设计物清单）、验收与待确认（两级：阻碍核心流程/次要未知）。主智能体协议第 12 条要求产出前先 `load_skill_through_path` 加载本技能（`AgentProfile.java:117-119`）。
- PRD 始终只有一个最新版（工作区活草稿），用户不直接编辑；`savePrd` 每次覆盖旧版。

### 版本语义

- 表 `prj_prd_versions`（迁移 `V32__prd_confirm_versions.sql:18-30`）：`version_no`（项目内递增整数，1 起）、`content`（确认时全文快照 TEXT）、`confirmed_at`、`prd_produced_at`（谱系观测锚）；唯一约束 `(project_id, version_no)`；**只插不改**（无 UPDATE 路径，实体零变更方法）。
- 草稿不编号；重复确认（全文未变）幂等返回既有版——成版判定正本＝快照全文比对（`PrdVersionAppService.confirmSnapshot`，`PrdVersionAppService.java:199-226`）；并发撞唯一约束败者回读。

### 确认门的代码位置与冻结动作

- REST 端点：`POST /projects/{id}/prd/confirm`（`ProjectController.java:360-379`）→ `PrdVersionAppService.confirm`（`PrdVersionAppService.java:102-136`）。
- 门内动作序列：①两级门——解析「验收与待确认」章，阻碍核心流程缺项即拦 PRJ_053（`:110-116`，解析缺失 fail-open）；②冻结＝当时工作区 PRD 全文拷贝为不可变快照插 `prj_prd_versions` 行（版本号 vN）；③**成版后自动派首个 Demo run**（`generationAppService.dispatchFirstDemoOnConfirm`，`:122-128`）——确认是首个构建 run 的唯一触发（ADR-0008 改写；收口观测判据 `!prdVersions.existsByProjectId` 静默待确认，`MainAgentAppService.java:850-855`）；④消费停驻的范围级交接物（见下）。
- 「冻结」不止一张表：`prj_projects.confirmed_demo_version_no`（Demo 确认门指针，ProjectController.java:420-424）与订单/合同冻结集（见下）都引用已确认版本。

### 下单冻结集当前锚什么字段

- `ord_orders`（基线 `V1__baseline.sql:226-241` + `V37__order_version_refs.sql`）：`prd_snapshot` TEXT NOT NULL——V37 起语义＝**已确认 PRD 版本全文的冗余拷贝**（订单自含，不依赖版本表存亡）；V37 加 `prd_version_no`（下单时最新已确认 PRD 版本号软引用）、`demo_version_no`（下单时 Demo 确认版本号定格）；确认制前遗留单三引用如实 NULL。
- 合同同构自含（`V38__contract_domain.sql:42` 起）：合同冻结 `prd_snapshot`（PRD 确认版全文拷贝）＋版本号＋Demo 版本号引用（CONTEXT.md「合同」词条 `:146`）。知识沉淀取版也以合同冻结版为优先源（面 5）。

### 「小迭代基准＝当前已确认版本」的实现位置

- 漂移判定单点 `PrdDraftBaseline.driftedFrom`（`PrdDraftBaseline.java:50-69`）：最新已确认版全文 vs 工作区草稿全文比对（无已确认版＝恒未漂移；读失败 fail-closed 按已漂移返回基准）。
- 消费点 `IterationAppService`（`IterationAppService.java:297-315`）：修正 run 起跑时漂移在场 → `baselinePrd`＝已确认版全文内联进任务 prompt（`fixRunPrompt(handoff, baselinePrd)`，`:471-500`：「本轮需求基准＝当前已确认的 PRD 快照……工作区草稿本轮一律不读」）；无漂移/legacy 以工作区正本为基准（路径引用不注全文）。「在途场照跑完收口绑起跑时版本」（`:297-298` 注释）。

### 大改分流的「新草稿＋差异摘要＋停派待确认」

- 轮收口判定：`parkScopeDraftOrAwait`（`MainAgentAppService.java:1105-1128`）——本轮有 savePrd 修订事实（`prdRevisions` 摘要终值）**且** `PrdDraftBaseline` 漂移在场＝范围级新草稿 → 停派（不派更新/生成 run）。
- 停驻载体：`prj_projects.prd_draft_opinion` / `prd_draft_summary` 两可空列（`V36__prd_draft_change_parked.sql`；「不落库缺口最小方案」，写口 `PrdVersionAppService.parkDraftChange`，`:148-160`）；差异摘要＝savePrd `summary` 终值（markdown 变更说明）。
- 确认消费：`consumeParkedDraftChange`（`PrdVersionAppService.java:171-196`）——确认产生新版本号且项目已生成 → 携停驻意见＋差异摘要派修正 run（下一版 Demo）；未生成项目由首个 Demo 全新构建消化。同文重存（草稿==已确认版）不属新草稿，照旧受理即迭代。

## 面 4：对话史表结构

- 表 `prj_conversation_entries`（`V2__conversation_history.sql`）：append-only，`id BIGSERIAL`（写入序＝对话读序）、`project_id`（软引用无 FK）、`run_id`（对话轮锚）、`kind` INT、`text` TEXT、`question` JSONB（问答卡载荷原样）、`closing` JSONB（收尾卡权威事实）、`answered` BOOLEAN（问答卡唯一 UPDATE 面）；索引 `(project_id, id)`。增量：V13 报价卡、V48 失败留账。
- kind 枚举 `ConversationEntryKind.java`：1 USER 用户发言 / 2 AGENT 智能体回复 / 3 QUESTION 问答卡 / 4 ANSWER 问答作答 / 5 CLOSING 收尾卡 / 6 GUIDE 平台轻引导 / 7 QUOTE 报价卡 / 8 RUN_FAILURE 失败留账（平台观测面，用户面零呈现）。
- 已落内容：用户发言（提交守卫全过后同步落）、智能体回复（轮收口/挂起时按段落）、问答卡（挂起载荷原样——刷新后可重建可作答）、问答作答（同步置位 answered）、收尾卡（编码 run 收口）、平台轻引导（兜底文案/悬空补救同族）、报价卡、失败留账。**过程明细（解说段/动作卡流水）不落库**——收尾卡已是凝聚物（`MainAgentAppService.java:80-87` 类注；写口唯一＝主智能体收口处与收口扩载侧，TurnRecorder）。

## 面 5：知识库机制

### 表结构

- `knw_chunks`（`V1__baseline.sql:84-108`）：块表——`kind`（v1 仅 "PRD"）、`source_ref`（幂等键之二，v1＝projectId）、`project_id`/`project_name`（命中展示）、`title`、`seq`（块序）、`chunk` TEXT、`embedding vector(512)`（本机 fastembed，BAAI/bge-small-zh-v1.5）、`meta` JSONB 扩展位；HNSW 余弦索引；幂等＝`DELETE WHERE (kind, source_ref)` 后插。
- `knw_materials`（`V5__knowledge_material_registry.sql`）：素材登记表——身份 `(kind, source_ref)` 唯一，`status` 1 启用/2 停用（治理动作，可再启用），`operator_id/name`（后台管理操作者），项目删除级联清理。检索过滤＝`findSimilar JOIN 本表 WHERE status=1`。

### 沉淀入库路径（归档触发）

- **唯一沉淀触发点＝订单归档成功**（后台人工终局）：`OrderAppService.settleArchive`（`OrderAppService.java:364-375`）→ `projectKnowledgeAppService.sinkPrd(projectId, archivePrdContent(...))`。
- 取版裁决 `archivePrdContent`（`OrderAppService.java:376-385`）：最近已签署合同的冻结 PRD 版（合同自含冗余拷贝）优先 → 无合同回退项目最新已确认版 → 两源皆无（确认制前遗留）如实 null 跳过。
- 入库实现 `sinkPrd`（`ProjectKnowledgeAppService.java:163-182`）：段落级分块（`chunkByParagraph`，`:198-225`，目标 ~800 字符，空行切段、超长硬切）→ `KnowledgePort.index`；失败降级记日志不炸归档事务（尽力而为，丢失容忍）。

### 检索与注入（注入哪些智能体/什么时机）

- 检索：`KnowledgePort.retrieve(query, topK)`（默认 topK=5，`app.knowledge.top-k`，`ProjectKnowledgeAppService.java:56`）；查询超 2000 字截断；失败降级空注入。
- 注入时机两族（CONTEXT.md「知识命中」词条）：**一次切入一次注入**——
  1. **主智能体**：会话建立时（建项目开场 `startConversation` 调 `establishSessionInjection`，`MainAgentAppService.java:201`；query＝用户初始需求原文），命中块拼「【平台知识库·相似历史需求】……背景参考（非约束）」尾注接 system prompt（`mainCommandWith` 拼 `sessionTailOf`，`MainAgentAppService.java:712`）；落进程内缓存后续轮复用，重启退化为裸配置不重注（`ProjectKnowledgeAppService.java:42-83`）。
  2. **run 执行体**：生成/修正 run 下发前置注入（`CoderRunAttempts.java:363`，`dispatchInjection`——query＝任务 prompt 截 2000 字，命中块前置拼进 run 任务）。
- 设计执行体不注入（无此调用点）。检索失败/空命中＝空注入不阻断主流程。

## 面 6：技能自荐→草稿→审核→晋升链（沉淀机制先例）

权威文档：`docs/adr/0022-self-produced-skill-draft-store-and-promotion.md`。

### 表结构

- `skl_skill_drafts`（`V21__skill_drafts.sql`）：草稿表——`name`/`description`/`content`（正文，**a-only 无 scripts 列，结构性锁死**）；**血统三字段** `project_id`（软引用无 FK）、`run_id`（RuntimeContext 每调用提取）、`slot`（main/executor/subagent）；**扫描回执** `scan_verdict`（SAFE/CAUTION，DANGEROUS 拒写不落库）＋`scan_findings` JSONB；`status` 1 在途/2 已晋升/3 已拒绝；终态列 operator/reviewed_at/reject_reason；在途撞名部分唯一索引（终态释放名字）。项目删除清未终结草稿。
- `skl_skills`（`V15__skill_library.sql` + `V23__skill_source.sql`）：库表——`(source_package, name)` 唯一；来源列 `source` 2 INSTALLED/3 SELF_PRODUCED（DEFAULT 回填 INSTALLED；自产行 source_package/version 固定虚拟值 'self'）；内容面收 frontmatter JSONB＋正文（解析态）；status 启用/停用。

### 流程代码位置

1. **自荐工具**：`ProposeSkillTool.java:38-90`——入参仅 name/description/body；三职能槽位各构实例（构造态 slot 即血统）；权限恒放行（写入有扫描＋人审门）；软指引住三处工作协议（主智能体协议第 19 条，`AgentProfile.java:154-157`；执行体第 11 条 `:242-246`），不设硬步骤。
2. **写入域用例**：`SkillDraftAppService.propose`（`SkillDraftAppService.java:82-97`）——校验正本（name 正则/长度）→ `SkillSecurityScanner` 静态扫描（DANGEROUS 拒写）→ 撞名拒（库＋在途草稿）→ 插草稿行；回执给模型可读可修正重提。
3. **后台审核**：端点 `BackofficeSkillController`——`GET /drafts`（`:258`）、`GET /drafts/{id}`（`:274`）、`POST /drafts/{id}/promote`（`:291-307`）、`POST /drafts/{id}/reject`（`:311-324`）。
4. **晋升**：`SkillDraftAppService.promote`（`:158-186`）——在途复查→撞名复查→内容原样 insert `skl_skills`（source=SELF_PRODUCED、'self' 虚拟包）；**审核者不改稿、不自动指派**（入池与生效分离，生效走人工指派＋skillcheck 预检）。
5. **拒绝**：`reject`（`:198` 起）——理由必填，终态留档不再列活跃面，不改稿不复活，再提只能新草稿。

### 与知识沉淀线的对照事实

- 技能线有**人审质量闸**（草稿隔离表＋血统＋扫描回执＋终态审核）；知识线 v1 **无人审/标注闸**——`knw_materials` 只有启用/停用治理（停用在命中检索 JOIN 过滤），入库即全局可命中。
- 两条线同构处：草稿/素材都「与正货隔离、血统可溯、项目删除级联清理、操作者留痕」；技能草稿的先例（血统字段命名、扫描回执随档、终态语义）是「两层沉淀机制」设计可直接映射的最近先例。

## 附：六面关键文件索引

| 面 | 关键文件 |
|---|---|
| 访谈编排 | `AgentProfile.java:41-158`、`AskUserTool.java:31-79`、`AgentscopeEventMapper.java:127-179`、`MainAgentAppService.java:771-824`、`question-card.tsx:51-110` |
| 悬空/纠偏 | `docs/adr/0032`、`docs/adr/0036`、`MainAgentAppService.java:936-1028`、`:1038-1059` |
| PRD | `SavePrdTool.java:35-77`、`skills/prd-writing/SKILL.md`、`V32`、`PrdVersionAppService.java:102-226`、`PrdDraftBaseline.java`、`IterationAppService.java:471-500`、`MainAgentAppService.java:1105-1128`、`V36`、`V37`、`ProjectController.java:360-379` |
| 对话史 | `V2`、`ConversationEntryKind.java`、`MainAgentAppService.java:80-87` |
| 知识库 | `V1:84-108`、`V5`、`ProjectKnowledgeAppService.java:42-225`、`OrderAppService.java:364-385`、`CoderRunAttempts.java:363`、`MainAgentAppService.java:201/712` |
| 技能链 | `docs/adr/0022`、`V15`、`V21`、`V23`、`ProposeSkillTool.java`、`SkillDraftAppService.java:82-218`、`BackofficeSkillController.java:258-324` |
