# 技能线机制与对标取证（2026-09-22）

> 动因：平台要做「智能体用技能＋后台安装配置指派」（grill 会话 2026-09-22）。四路调研合一存档：agentscope-java 内核机制、平台现状缝位、matt/superpowers 技能包形态、Replit/Lovable 分层做法。供技能线设计票、以及后续「分层瘦身」「自学习闭环」两张 grill 票引用。

## 1. agentscope-java skill 机制（内核 2.0.1，本地仓库 /Users/zhangcolin/workspace/agentscope-java）

- **skill 形态**：目录 + `SKILL.md`（YAML frontmatter：name/description 必填）+ 可选 `references/`、`scripts/`、`assets/`、`templates/`。延迟加载——平时只注入 name+description 目录，用到才 load 全文。
- **加载管道**：`ReActAgent.builder().skillRepository(repo)`（可多次，后者优先）→ `DynamicSkillMiddleware` 每次 call 从 repository 重建 `<available_skills>` 块注入 system prompt（内容哈希短路）→ 模型自主调 `load_skill_through_path(skillId, path)` 读正文/资源。无关键词匹配，纯模型决策。
- **HarnessAgent 四层合成**（低→高，同名后者覆盖）：`projectGlobalSkillsDir` → 用户传入市场 repo → `workspace/skills/` 共享 → per-user `WorkspaceSkillRepository`。
- **SkillFilter**：builder 级 `only()/except()` + RuntimeContext 级 `enable()/disable()` overlay——子智能体技能面收窄的现成原语。
- **工具绑定**：`SkillToolGroup.activateOnSkill(name)`——load 技能即激活整组工具。工具是宿主代码（Java），外部技能包装不来，只有平台自制技能能用。
- **脚本执行**：`skillCodeExecutionEnabled(true)` → 每个 skill 带 `<files-root>`，模型用 shell 直跑 `scripts/`；市场技能经 `MarketplaceStager` 物化到 `.skills-cache/`（SHA-256 去重）。
- **安装生态**（`AgentSkillRepository` 实现）：Git（持续同步：每次读检查远端 HEAD、变了才 pull——**订阅语义非快照**）、MySQL/PostgreSQL、Nacos、FileSystem、Classpath。
- **自学习闭环**（harness curator 包）：`skill_manage`/`propose_skill` 工具写草稿区 → `SkillPromotionGate`（RejectAll/LocalApproval/NotifyAndWait 三档）晋升 → `SkillCurator` 周期整理（30 天不用标 stale、90 天归档）＋ `SkillUsageMiddleware` 计数 ＋ `SkillSecurityScanner` 扫描。
- **校验规则**（SkillManageTool）：name ≤64 字符 `^[a-z0-9][a-z0-9._-]*$`、description ≤1024、正文 ≤100k 字符、单文件 ≤1MiB、子目录白名单 references/templates/scripts/assets。
- 1.x 的 `SkillBox`/`SkillHook` 已 deprecated。

## 2. 平台现状缝位（aiplatform develop@afc3e4f）

- **智能体配置不落库**（ADR-0006）：`AgentProfile` 纯枚举，仅 MAIN（deepseek-v4-flash）/EXECUTOR（deepseek-v4-pro）两座，字段 key/code/name/modelId/systemPrompt（长文本硬编码）。另有非登记一次性调用：classify（入口三分类）、naming（项目取名）。
- **技能缝已接线**：`AgentSkillRepositorySupplier` SPI → `ProfileSkillRepositorySupplier` 硬编码返回 classpath 内置仓库（`skills/prd-writing/SKILL.md`，仅 MAIN）；装配点 `AgentscopeHarnessAgentFactory.buildAgent()` 的 `builder.skillRepository(...)`。`disableDefaultWorkspaceSkills()` 关框架默认工作区层；`WorkspaceLayout.SKILLS_DIR`（`.platform/skills/`）为工作区自制预留未接线。
- **systemPrompt 组装**：MAIN = 枚举 prompt + 知识命中尾注（会话建立时一次）；EXECUTOR = 裸枚举 prompt（知识命中作任务 prompt 前置块）。
- **工厂缓存陷阱**：进程内 agent 实例复用键 = `name|modelString|sysPrompt|workspace.identity()|agentKey`——**技能集不进键**。技能集要动态须靠 repository 本身是动态视图（如查库），不能装配时固化快照。
- **工具装配**：`ProfileToolkitSupplier` 按 agentKey+工作区形态硬编码（MAIN+ReadOnly 8 工具；EXECUTOR+Dev 2 工具+harness 内建编码工具）。子智能体：`ProfileSubagentSupplier`（EXECUTOR 挂 self-test，只读隔离）。
- **后台模式**：`/api/backoffice/**` + `@RequireSignature` 机机签名；知识素材域（materials 列表/详情/启停/删除）是技能管理的现成参照。

## 3. matt / superpowers 技能包形态

- **matt**（mattpocock/skills，42 技能，本地已装于 `~/.agents/skills/` + `~/.claude/skills/` 软链）：松耦合技能族 + 散文路由器（ask-matt 画主流程 grill→to-spec→to-tickets→implement）；技能间正文点名 `/tdd` 互调；附属 md 渐进披露；仓库分 engineering/productivity/in-progress/deprecated 等 6 类（deprecated 5 个——**装仓库须可勾选排除**）。委派指导散点式（code-review 双轴并行子智能体、research 后台智能体、phase-boundaries 把派子智能体列为上下文管理选项之一）。脚本仅 4 处（护栏型：hitl-loop 模板、git 拦截 hook 等）。
- **superpowers**（obra/superpowers，15 技能 plugin，~40K stars）：**以子智能体为中心**——subagent-driven-development 32K 字控制器协议（每任务派新实现者、任务审查者、5 轮修复环、ledger 防丢、模型分档）；session-start hook 注入铁律「任何回应前先查技能」；实现者 no-subagents 契约、`<SUBAGENT-STOP>`（**被派发的子智能体忽略入口技能——子智能体技能面要收窄的实践佐证**）。脚本承担编排职责（task-brief/review-package 工件流转）——**不支持 scripts/ 则此类技能装上即残废**。
- **槽位落位**：编码方法论（implement/tdd/systematic-debugging/verification-before-completion）→ run 执行体；审查提示词（code-review 双轴、task-reviewer/re-reviewer）→ 自测/审查子智能体；需求侧技能（grilling/to-spec/brainstorming）与平台内建访谈编排重叠——双头风险区。
- **两包都无自动自学习闭环**（人触发、智能体执行）；superpowers 独有「技能 TDD」：RED=无技能跑压力场景记基线 → GREEN=写最小技能堵洞（NO SKILL WITHOUT A FAILING TEST FIRST）——技能质量验证方法论，可作晋升审核参考。

## 4. Replit / Lovable 分层做法

- **两模式分叉**：
  - **Replit/Lovable 模式**：内建 prompt 厚（Lovable 泄漏版 20.3KB、约 1/3 是设计方法论；Replit Agent 版 6.7KB 含 40% Policy），技能定位「特定任务知识」。Replit 官方铁律："Do not build generic skills for code cleanliness or security — those already ship with Agent"。Lovable 内建 skills（SEO review/accessibility 等）平台维护只读。
  - **Claude Code 模式**：宿主 prompt 薄（机制+路由，不教方法论），方法论全住技能层——matt 42 个随便装不双头。
- **平台要走 Claude Code 模式**（要装的就是全流程方法论包），Replit 铁律反用：「技能内容若是通用开发规范→该进内建；内建 prompt 若出现方法论→该迁出成技能」。
- **冲突处理**：两家都无「注入物 vs 内建」优先级仲裁机制；公开口径一致＝**冲突是 scope 写歪了**——description 写清 trigger/scope/否定边界（「不用于什么」）事前预防。
- **生态**：Replit/Lovable/Claude/vercel-labs CLI（matt 的安装器）/skills.sh 市场全用同一 SKILL.md 规范（agentskills.io）——git URL 安装天然吃到全生态。
- **安全**：Replit 官方警示恶意 skill 可 prompt injection 外泄数据；审计分档（官方目录审计过 / CLI+GitHub 导入未审计）。
- **Lovable 细节**：knowledge 两级（project>workspace 优先级）always-included；skills 靠 description 匹配加载；轮次状态动态拼 prompt（首轮专用段）；prompt 迭代＝人类主决+数据飞轮（A/B 看成功用户数/卡住率/任务轮数）。
- 来源：泄漏 prompt（github.com/x1xhlol/system-prompts-and-models-of-ai-tools、github.com/elder-plinius/CL4R1T4S）；官方文档 docs.replit.com（memories-custom-instructions-and-skills、learn/agent-skills、build/use-agent-skills）、docs.lovable.dev（features/skills、features/knowledge）；Strange Loop 播客 Anton Osika 访谈。未证实：Replit Agent 3/4 prompt（无泄漏）、两家注入物优先级机制（无公开）。

## 5. 对本平台的映射（2026-09-22 grill 会话决策引用）

1. 清单+模型自主加载＝框架原生机制（<available_skills> + load_skill_through_path），指派用 SkillFilter/仓库合成实现。
2. 安装内容面 a（知识/规范）+c（scripts/，开放面=有 shell 的槽位）；b（工具组绑定）属平台自制技能线。
3. 安装=快照进平台库（否决 GitSkillRepository 订阅式自动同步）；其 HEAD 检查逻辑复用为后台「更新检查」，更新显式点。
4. 粒度：装=仓库级导入（可勾选排除 deprecated）、库条目=单技能（标注来源包）、指派=单技能勾选。
5. 子智能体技能面独立配置（否决全量继承，SUBAGENT-STOP 佐证）。
6. 双头避免五条：EXECUTOR prompt 落库时方法论迁出为内置技能；CONTEXT.md 立分层纪律（内建 prompt 只载身份/编排/约束/薄路由）；审核面对照 prompt 查重叠；双向边界判断规范；description 质量要求（trigger/scope/否定边界）进审核清单。
7. **兼容约束（本次硬约束）**：技能线的设计不得堵死分层瘦身——技能表来源枚举含内置、prompt 落库后台可维护、指派机制通用，三者保证瘦身票落地时「改 prompt+建技能+指派」全走既有机制。
8. 待开票：①分层瘦身票（EXECUTOR prompt 方法论迁出，依据见 §4 两模式分叉）；②自学习闭环票（框架机械见 §1 curator 包，晋升门倾向后台人工审，superpowers 技能 TDD 作审核方法论参考）。
