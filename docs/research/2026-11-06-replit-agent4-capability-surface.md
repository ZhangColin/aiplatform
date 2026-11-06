# 竞品深调研：Replit Agent 4 全能力面（2026-11-06）

> 票 [#412](https://github.com/ZhangColin/aiplatform/issues/412)（wayfinder #404 research 子票）。承接 #313（2026-10-15，Agent 3 证据基线）刷新至 Agent 4 现状。
> 方法：官方一手为主——docs.replit.com（Mintlify `.md` 端点 curl 直取，站点地图核对存在性）、官方博客（curl 直取原文核字面）、工程博客 frontier.replit.com（两篇全文直取）、厂商联合案例（Restate/LangChain，标注口径）。社区证据由子代理经 Arctic Shift 存档 API 逐帖取回 Reddit 原文并核日期、HN 经 algolia API 全量检索，一手/二手逐条标注。数字给量级不给假精确；取证截至 2026-11-06。

## 结论速览

1. **版本事实**：Agent 4 于 2026-03-11 发布（同日宣布 $400M 融资 @$9B 估值）。至 2026-11-06 **无 4.x 子版本号**（官方与社区均无 4.1/4.5 证据），发布后 8 个月的演进形态是**模式分级换血与功能增删**（Economy 撤档由 Power 同价顶替、Lite→Free Mode、Turbo 档出现在旧文档页、thinking trace 移除、老模板停用风波），不是版本号递进。官方速度口径＝**「Agent 4 — 10x faster than Agent 3」**（融资博客标题原文）；#313 旧调研引的「parallel agents… up to 3x」在今日官方页面**不可复现**（今日发布博客正文只说 "ship production-ready software 10x faster" 未给基线）——本报告以 10x 为现行官方口径，且 10x 无任何方法论披露。
2. **架构杠杆四件套全部坐实**（Q1）：①并行任务系统＝主线程＋后台任务跑在**项目隔离副本**里（Starter 0/Core 1/Pro 10/Enterprise 64 并发、依赖自动检测排队、**merge 冲突 90% AI 自动解、10% 上抛用户**）；②「一个 agent」实为**专用模型流水线**（exploration/testing/parallelism 各一个，官方原话）；③**decision-time guidance**＝轻量分类器每迭代判定注入微指令（并行工具调用 +15%/loop 的出处，静态系统提示词放同款指引无此增益）；④**checkpoint 全状态快照**（代码＋数据库内容与 schema＋AI 会话上下文＋agent memory，双向回滚，计费按「一请求一 checkpoint」）。
3. **验证策略＝「智能触发」不是「终验门」**（Q2）：Agent 3 打的底是 REPL 式自测（Playwright 代码级 code-use＋notebook 持久化＋测试子代理隔离上下文，中位 **$0.20/场**、支撑 200 分钟自主跑）；Agent 4 的 App Testing 继承为真浏览器自测（输 mock 数据、自动分析修复、视频回放），但 docs 明说「**doesn't test after 100% of user messages, but intelligently determines when testing would be most valuable**」——没有保证性终验。安全面（Semgrep＋HoundDog 静扫＋威胁建模）是用户触发件。首版默认**不带鉴权/限流/密钥管理**是三个独立来源一致确认的系统性缺陷。首版做对率口碑双峰：demo 级「可用但有小瑕疵」为主流正评（一手数据：错误首试自愈率 ~75%），复杂项目「缺大块功能/结构混乱/并行 merge 互毁/循环卡死」高频。
4. **时长分布是双峰不是单点**（Q3/Q5）：一手实测带——简单（landing/小工具）**3–15 分钟**、中等（auth+DB+Stripe）**30–50 分钟**、SaaS 级**2.5–4 小时含显著人工调试**；**迭代段更长**：小改动 10–30 分钟起步、极端样本 95 分钟–2 小时（Agent 3 时代同款改动 52 秒的一手对照）。「≈40–60 分钟」作为**中等复杂度首版**的均值带成立，作为普适口径不成立，且完全不适配迭代段。10x 宣称仅在「零→首版 demo」窄场景被第三方复现、且复现者对照的是 Agent 2。
5. **口碑头号抱怨是 credits 成本失控**（样本约半数帖子因账单而起）：极端一手样本 $140/13 分钟、$5,419/7 周、$8k/月（GILD 汇总带 $8–$8,000/月）；第二是可靠性（循环卡死/endless thinking），第三是代码质量（安全默认缺失、dev→prod 数据迁移 gap）。成本与可靠性互为放大器（loop 既是可靠性也是计费事故）。
6. **基础设施是全托管＋开放标准挂生态**（Q4）：环境全托管（标准 Agent「pre-configured environments」，General Agent 才自配 Nix 环境）、持久执行底座从 Temporal 切到 **Restate**（数千 durable steps、guardrails 落账、并行子代理的 durable cancellation）、模型层多供应商（GPT-6 系/Claude 系/Kimi K3）＋Auto 智能路由＋Effort 档（Low→Max，最难任务成本上限 ~2x）＋Compare models（同 prompt 4 模型并行变体）；**Skills 走 agentskills.io 开放标准**（SKILL.md、GitHub 可导入）；模板已转型为 **Design 画廊设计模板**（设计起点，非工程脚手架）。
7. **对标结论**（详见 §6）：我们最大的结构差距是**切片并行化（隔离副本＋AI 合并）**与**真浏览器自测**；性价比最高、可立即借的是**决策时注入微指令**（对症我们单 turn 160 次迭代的指令衰减）与**自测选择性触发**（对症 self-test 12% 固定开销）；我们已有等价物的是 checkpoint（版本流＋ADR-0020，缺 DB 快照与会话回滚一体化）与模型路由（运营覆盖已具备，缺按片难度路由策略）。**不必学**：effort 计费的透明度灾难、并行一切的默认、「智能跳过终验」的极端化（我们「真实落库＋探活」收口判据是差异化优势）、过程可见性倒退。
8. **对标基准修正**：我方净生成 1h50m 的对标带应从「Replit 主观 40–60min」修正为「**中等复杂度首版 30–50min 实测带**」——且 Replit 该速度的来源是并行＋路由＋验证体系，不是更快的裸模型；我方提速路线（#313 已排好的修首试浪费/砍自测/换快模型）天花板 ~2–3x，恰与并行化前 Replit 的形态同构，方向一致。

---

## 0. 版本与口径基线

| 事实 | 证据 |
|---|---|
| Agent 4 发布 2026-03-11，四支柱（Design Freely/Move Faster/Ship Anything/Build Together） | 官方博客 introducing-agent-4-built-for-creativity（发布日期取页面 `publishedAt`） |
| 「Introducing Agent 4 — 10x faster than Agent 3 to unleash your creativity」＝融资博客小节标题；meta description 同口径 | replit.com/blog/replit-raises-400-million-dollars（curl 直取） |
| 发布博客正文：「ship production-ready software 10x faster」「ship production-ready apps 10X faster」（未给基线、无方法论） | 同上 introducing-agent-4 博客（curl 直取） |
| 首页口径仍为「minutes. No coding required.」 | replit.com 首页 meta（2026-11-06 直取） |
| 无 4.x 子版本；2026-04 buildathon 官方重申 10x | espressio 指南 2026-07（二手，官方口径转述）＋社区取证无反例 |
| 3x 口径不可复现 | 今日官方页面全文 grep 无「3x」（融资博客的「3x」指估值半年 3 倍增长）；Wayback 跨境不可达未核 3 月原版，如实备案 |

版本演进史（供定位证据年代）：Agent 3 发布 2025-09-10（二手）；自测工程文 2025-12-15；decision-time guidance 工程文 2026-01-20；Agent 4 发布 2026-03-11；Economy→Power 同价替换、Lite→Free Mode（docs 变更注记）；2026-09–10 changelog：Cloud Agents（企业代码贡献）、Monitoring Agent、BYOC、Meta 设备、Windows 桌面预览、Haiku 5.5/GPT-6.1 Terra 入模型选择器、custom API connectors、桌面 MCP 客户端。

> 备案：docs.replit.com 存在新旧两套模式术语（features/agent/agent-modes 现行 Free/Power/Max vs 旧 replitai/agent 页残留 Lite/Economy/Turbo「up to 2.5x faster… cost up to 6x」），本报告以 features/agent 现行页为准，术语 churn 本身记为产品仍在剧烈整形的证据。

## 1. 架构杠杆（Q1）

### 1.1 并行子代理：隔离副本＋Kanban＋AI 合并

- **官方分工描述**（发布博客）：「Agent 4 can use parallel agents to work on different parts of your app simultaneously — for example, authentication, database setup, back-end, and front-end — all at the same time. It can even split large builds into smaller pieces, work on them in parallel, and recombine them seamlessly. Specialized sub-agents resolve conflicts automatically.」
- **隔离机制**（Agent 3→4 变更文）：「that plan creates tasks that get executed in their own isolated environments—**exact copies of your current project**—and move through the Kanban board independently. Nothing touches your main project until you explicitly approve it.」
- **并发配额**（docs task-system）：Starter 0／Core 1／**Pro 10**／Enterprise 64；依赖自动检测（「a task that builds a dashboard depends on the task that creates the database schema. Dependent tasks wait until their prerequisites complete」）。
- **冲突解**（docs＋HQ 直播文）：「Agent uses AI-assisted conflict resolution. It understands what each task was trying to accomplish」；工程侧口径「**the last 10% of edge cases can be surfaced to the builder for a quick decision, while 90% resolves automatically**」。
- **审查语义**：任务完成后呈 work log＋test results＋live preview，用户 Apply/Dismiss——「review-then-apply」，主版本默认不被触碰。
- **宣称 3x/10x 的构成**：官方从未拆解 10x 的构成因子。可归因的官方证据只有：并行任务（结构性）＋模型路由（成本口径 65% 是 Agent 3 时代数字）＋Turbo 档「up to 2.5x faster builds」（旧文档页口径）。**结论：10x 是营销合数，非可复现工程参数。**
- **社区代价证据**（一手）：并行任务 merge 互毁（「if your running multiple tasks it can break them things after it merges the code」）；45 task plans 冻结；审批流比 Agent 3 难跟进（「You lose the granular back-and-forth」）。

### 1.2 「一个 agent」实为专用模型流水线＋路由

- **HQ 直播文原话**：「What looks like one agent is actually **a pipeline of specialized models — one for exploration, one for testing, one for the sub-agents handling parallelism**. The economy, pro, and power modes give builders some control over the mix. The orchestration underneath stays with us.」
- **现行模式体系**（docs features/agent/agent-modes，2026-11 现行）：Free（路由强制、5 小时刷新额度）／Power（省钱档）／Max（能力档）；Power/Max 内可选 **Auto＝intelligent model routing**（「Replit then chooses a model up to the selected mode's capability for each task」）。
- **模型清单**（docs model-selector）：Power 档 GPT-6 Sol、GPT-6.1 Terra、Claude Sonnet 5、Claude Sonnet 4.6；Max 档 GPT-6 Astra、Claude Fable 5.1、Claude Opus 5.5/4.8/5/5 Fast、Kimi K3（多供应商混供，Fast 变体需 Pro/Enterprise）。
- **Effort 档**：per-model 推理投入 Low→Max；「Agent applies that extra power selectively. It routes to the more capable models only when a request is genuinely hard」「higher Effort adds cost up to ~2x on the toughest tasks. It often adds little or nothing on simpler tasks」。
- **Compare models**（beta）：同 prompt 最多 4 模型各跑一个独立 agent 产变体，费用≈4 倍单跑。
- **企业控件**：Enterprise Model Controls 可限 Auto 能用的供应商与模型。

### 1.3 并行工具调用：decision-time guidance（+15% 出处）

工程博客 frontier.replit.com/decision-time-guidance（2026-01-20）原文：

- 「when we encourage parallel tool calling for Replit agent, **injecting a short prompt at the bottom of the trace led the agent to execute 15% more tools per loop** than placing the same guidance in the system prompt.」
- 机制：轻量多标签分类器（跑在「fast, cheap model」上、**每个 agent 迭代都触发**）分析轨迹（用户消息/近期工具结果/错误模式）决定注入哪条微指令；控制从单体系统提示词移入「bank of reusable micro-instructions」。
- 边界：提醒堆到第三四条后收益递减甚至转负；「Earlier model generations would often **mock data to pass a task** or perform dangerous deletions」——反 mock 提醒与其他提醒叠加时遵守率下降。
- 适配注记：该文是 Agent 3 时代的长轨迹治理技术（对我们单 turn ~160 次迭代的 ReAct 执行体同样对症，见 §6）。

### 1.4 checkpoint：全状态快照＋双向回滚

- **捕获范围**（docs checkpoints-and-rollbacks）：「project files… AI conversation context… Environment configuration… **Agent memory**… **Database contents**」——不是纯 git：连会话上下文与 agent 对项目的理解一起回滚。
- **触发时机**：feature completion／major milestones／stable states（测试验证后）／error recovery（修复尝试前）。
- **双向**：roll back＋roll forward（回滚后的新变更形成 alternate branch）。
- **计费语义**（docs ai-billing）：「**One checkpoint per request** eliminates intermediate checkpoints」——checkpoint 同时是计费单元（effort-based pricing）。
- **git 集成**：每 checkpoint 对应一个 git commit。
- **社区体验**（一手）：不总是自动（「has to be reminded to make a checkpoint」）、chat 历史缺失破坏回滚定位、手动 git commit 被自动 checkpoint 取代引发不满。

### 1.5 执行底座（厂商联合口径，一手披露级别次之）

- **Restate 案例**（restate.dev/use-cases/replit，Replit 联合发布）：Agent 循环内联持久执行（inline durable steps 毫秒级开销）、从 Temporal 迁来；guardrails 每步落账；**Virtual Objects** 承载有状态 agent 会话；并行任务用 Durable Coroutines 同进程并发；**durable cancellation 贯穿嵌套子代理**；人审批可挂起数月；峰值 ~25k durable actions/sec/cell。harness 编排「model inference, conventional and programmatic tool execution, sandbox commands, conversation state and compaction, parallel subagents, steering, cancellation, guardrails, and cleanup」。
- **LangChain 案例**（langchain.com/blog/customers-replit）：Agent 建在 LangGraph 上、LangSmith 追踪；单 trace 最高 ~15k spans；主 agent 上下文 <100k tokens 无需压缩；HITL 审批与测试子代理的执行都在观测面内；ingest 峰值 ~5k traces/min。

## 2. 验证与质量策略（Q2）

### 2.1 验证面谱系（官方一手）

| 层 | 机制 | 关键原文/数字 | 来源 |
|---|---|---|---|
| 浏览器级自测 | Agent 3 底座：REPL code-use | 「execute JavaScript in a sandbox and inject helper functions that allow it to manipulate the browser using Playwright」；notebook 持久化（变量/浏览器会话跨执行存续、「context stays in code, not in tokens」）；36 次月历翻页＝一次模型调用生成代码循环；ARIA/test 属性增强的精简 DOM＋**只读 DB 查询**＋增量客户端/服务端日志；**测试独立子代理**（主 agent 上下文 80–100k tokens，测试上下文污染两方向都伤任务）；中位 **$0.20/场**、多百步测试；「10x more autonomous… from 20 minutes to over 200 minutes」 | frontier.replit.com/automated-self-testing（2025-12-15） |
| Agent 4 现行 App Testing | 真浏览器、输 mock 数据、自动修 | 「enters relevant mock data, and automatically analyzes issues to self-correct」；视频回放；**「Agent doesn't test after 100% of user messages, but intelligently determines when testing would be most valuable」**；仅 Full Stack JS 与 Streamlit；10 分钟无响应跳过；登录/CAPTCHA 交还用户；测试本身计费 | docs features/agent/app-testing（2026-09-15 版） |
| 代码审查 | built-in code review | 「alongside built-in code review」（Code optimizations 开关，执行体自审代码） | 同上 docs |
| 任务级验证 | review-then-apply | 任务完成呈「work log, test results, and a live preview」再由用户 Apply | docs task-system |
| 安全 | Security Agent | Semgrep＋HoundDog.ai 静扫＋模型威胁建模（routes/APIs/数据流映射→威胁模型→静扫→上下文评估）；Publish 流程可一键发起；大项目深扫至 15 分钟；**用户触发件非默认门**；Level 3 黑盒 pen test 并行跑 | docs features/security/agent-security-scans |
| 企业代码贡献 | Cloud Agents（2026-10-30） | 「finds the relevant code, makes the change, **tests it in your running app**, and opens a GitHub pull request… with screenshots and video」 | docs changelog |

### 2.2 「一次做对」的真相：概率性验证＋用户侧生产化

- **没有终验门**：App Testing 是智能选择性触发（官方明说非 100%）；安全扫描要用户发起；生产要素默认不写（下述）。
- **首版默认不带生产安全件**（三独立来源一致，一手）：「no authentication on any API endpoint, customer emails and phone numbers exposed without access control, and no rate limiting anywhere」（madebyagents 实测审计）；「API keys and secrets shipped in the frontend JS bundle / CORS wide open」（r/replit 审计帖 2026-10-16）；「Secrets hardcoded in the code」（2026-06-03）。——安全扫描件的存在恰好反证默认产物不带这些。
- **首版做对率口碑双峰**：正评带＝「首版可用但有小瑕疵」（「Not perfect on the first shot — a chart legend was misplaced… but mostly functional from a single prompt」）；量化一手＝「Agent 4 self-resolved about 75% of errors on the first attempt」（agent-finder 五项目计时实测）。负评带＝「missing large chunks of what I asked for」「creates folders that do not make any sense」「parallel tasks… break things after it merges」、循环卡死（45 task plans 冻结）。
- **对我方映射**：我们的收口判据（真实落库＋8081 探活＋self-test 清单进收尾卡）是**保证性终验**，比 Agent 4 的选择性触发更硬；差距在**浏览器级 UI 验证**（我们无）与**安全默认件**（双方都缺，是行业空档）。

## 3. 迭代回路体验（Q3）

### 3.1 需求→可交互实测时长分布（一手优先）

| 场景 | 实测时长 | 来源（标注） |
|---|---|---|
| 静态 landing page | **3 分钟 flawless** | agent-finder 计时实测（一手，2026-03/08） |
| landing＋表单＋邮件集成 | **3 分 42 秒**（官方口径 "We timed it"） | 同上 |
| Todo app＋auth＋PostgreSQL | **12 分钟，一次手工修** | 同上 |
| 全栈 app（auth+DB+REST） | **18 分钟** | 同上 |
| SaaS dashboard＋Stripe | **48 分钟，三次重定向** | 同上 |
| booking dashboard（企业主视角） | **34 分钟基本可用，烧 $17 credits** | madebyagents（一手，2026-08-30） |
| habit-tracking app 全流程（plan→design→build） | **10 分钟** | creatoreconomy Peter Yang（early access 一手，2026-03-11） |
| 多租户项目管理工具 | **2.5 小时，显著人工调试** | agent-finder（一手） |
| 电商店面 | **3 小时，部分成功** | 同上 |
| 「AI 建成整个 SaaS 生意」 | **4 小时**（视频实录标题口径） | designerup（一手创作者） |
| 对照：Agent 3 Stripe waitlist 单 prompt | 34 分钟、11 credits | espressio（二手，官方+自测混合口径） |
| 负向：单段小改动 | **1h35m**（Agent 3 同款 2 分钟）／近 2 小时 | r/replit 一手（2026-03-14） |
| 负向：tiny change | Agent 3 **52 秒** → Agent 4 **10–30 分钟** | r/replit 一手（2026-03-11，score 4） |

**结构判读**：首版生成快、迭代慢是 Agent 4 的结构性时间形态——并行任务＋隔离副本让首版各件齐头并进，但每次迭代都要走「任务计划→隔离副本跑→审批 Apply」全环＋按 checkpoint 计费；多 agent 架构使单位任务 token 成本约 **2x**（一手：$0.60–0.70 → ~$1.40）。

### 3.2 反馈→变更生效回路的机制面

- **Steer/Queue**（docs steer-and-queue-messages）：任务运行中可发消息**转向**（注入当前 turn 的新上下文）或**排队**（下一 turn 处理），队列可改序/编辑/删除——中途插话是官方一等能力。
- **Canvas 圈选改错**（一手好评）：「I selected the broken chart element and described the problem… The agent correctly identified what I circled and made the change」。
- **Plan Mode**：先脑暴/任务清单再建（Plan 段也计费）；「Build here」或「Build in background」二选一；首 checkpoint 前只有 Start building。
- **Follow-up tasks／任务板**：建议后续任务批量起。
- **checkpoint 回滚**：改坏了一键回全状态。
- **口碑净结论**：小改动慢（52s→10–30min 代表性）、「修 A 坏 B」循环烧 credits（「1-Build 2-Find issues 3-Ask fix 4-Something else breaks 5-Spend more credits 6-Repeat」，烧光 Pro+$100 无 MVP 的一手样本）、发布一周内官方快速修复有正例（launch day 体验差→「noticeably better today」）。

## 4. 基础设施工件（Q4）

### 4.1 环境预置程度

- **标准 Agent＝预配置环境**：docs general-agent 原文「**Unlike standard Agent with pre-configured environments**, General Agent sets up its own environment and run commands based on your request（Nix）」——反证标准路径环境是预置的（全托管 projects/files/execution，`*.replit.dev` 常驻运行时）。
- **模板**：现行模板＝**Design 画廊设计模板**（replit.com/design，Landing/Mobile/Prototypes 分类；remix 到画布→restyle→「Build your design」转功能站，官方教程标 10 分钟）；非工程脚手架模板。社区「TEMPLATES ARE BEING DISCONTINUED」风波（一手抱怨）指老 Repl 模板让位——模板策略在收缩为设计起点。
- **生成范围**：发布博客「Agent 4 can handle both the complex and the mundane」——auth/DB setup/integrations 全写（并行分工本身含 auth 与 database）；Multi-Artifacts（web/mobile/slides/videos）共享同一 backend 与 DB。但**生产化件默认不写**（§2.2）。
- **第三方能力零配置**：「Some Agent capabilities use paid third-party APIs behind the scenes (such as Claude, ChatGPT, and Nano Banana / Gemini). Agent handles these integrations automatically — no API keys or setup required」，按供应商公开 API 费率从 credits 扣。

### 4.2 生态挂件（2026-09–11 现状）

- **Skills**：`/.agents/skills`＋`SKILL.md`，**遵 agentskills.io 开放标准**；预定义技能＋workspace 级分发（Required/Available/No access 三态）＋GitHub 仓库导入（≤50 skills/3,500 files/200MiB）；「Agent reads the metadata… on every chat. The full body loads only when relevant」——上下文经济性是设计目标。
- **Connectors**：BigQuery/Linear/Slack/Notion/Amplitude/Segment/Hex＋custom APIs（2026-09 changelog）。
- **Routines**：定时知识工作（默认确定性代码、需推理才上 Agent；per-run budget；≥1h 间隔）。
- **Cloud Agents**（2026-10-30）：企业共享开发环境＋隔离云沙箱＋按团队贡献规范出 PR。
- **其他**：Monitoring Agent（发布后监控）、Meta 设备构建、Windows 桌面预览（微软/NVIDIA）、桌面 MCP 客户端接入。

## 5. 速度口碑（Q5）

- **「≈40–60 分钟」的判定**：源头是我方 #313 引的二手带（Espressio「20–40 分钟带 Agent 3」＋Manus 横评 ~40 分钟）——**当时就是二手主观带**。Agent 4 的一手实测分布（§3.1 表）显示：该数字只覆盖「中等复杂度首版」这一档（30–50min），简单档 3–15min、重档 2.5–4h、迭代档更慢。**结论：40–60min 既非纯主观也不是稳定分布，是双峰分布的中段；对标应改用分档实测带。**
- **10x 宣称的复现情况**：支持方＝agent-finder「Replit claims it's 10x faster, and in our testing, that number tracks」（但其对照基线是 Agent 2 的 45 分钟 CRUD）；反对方＝对照 Agent 3 的一手用户大量报告小改动/修复反而更慢（52s→10–30min、2min→95min、4x 更贵更慢）。HN 讨论热度极低（全库仅 2 帖 0–1 评论），真实口碑战场在 r/replit。
- **抱怨频率排序**（2026-03 至 11 月 ~30 个一手帖样本）：①credits 成本/计费（压倒性，样本约半数帖子因账单/退款而起；$140/13min、$1,982 超订、$8k/月 runaway、$5,419/7 周）②可靠性（循环/卡死/崩溃）③代码质量与架构（无鉴权/密钥泄露/结构混乱）④UX/工作流回归（plan 模式切换不可靠、thinking trace 移除、模板停用）⑤部署/发布（10+ 次构建失败跨 3 天、Stripe connector 断连毁站）。
- **正面口碑的稳定区间**：非技术用户＋简单应用＋Economy/Free 档＋首版场景（「EW! I actually like replit」为代表）。

## 6. 对标差距清单

### 6.1 我们缺什么（按性价比排序；「我们现状」锚点取自 #313 代码取证）

| # | 缺口 | Replit 的做法（证据锚） | 我们现状 | 补齐路径与量级 |
|---|---|---|---|---|
| 1 | **决策时注入微指令** | 分类器每迭代判定注入、+15% 工具/loop（§1.3） | 系统提示词静态＋片间交接注入；单 turn ~160 迭代，中段指令衰减同款存在 | **低成本高信号**：在执行体 ReAct 循环的工具结果后挂「按错误模式注入微指令」的轻量判定（可用 flash 档模型）；对症失败首试与 mock 数据问题 |
| 2 | **自测选择性触发＋浏览器级验证** | App Testing 智能判定何时测（非每请求）；REPL code-use（Playwright 代码而非逐工具调用，$0.20/场）（§2.1） | self-test 子智能体每片固定一场（12% 开销）；命令级探活，无浏览器/截图 | 两步：a) 触发改智能（按变更面/错误信号判定，砍固定开销——#313 手段 #2 的升级版）；b) 浏览器级＝借 code-use 形态给 self-test 协议加 Playwright 代码执行（**需沙箱浏览器基建**，仓内 #271 已备案「沙箱零截图基建」是前置） |
| 3 | **模型路由策略** | Auto 按任务难度选模型＋Effort 档＋模式分档（§1.2） | agent_operational_config 运营覆盖已具备（不发版换模型），缺「按片难度自动路由」 | 协议/编排层加难度判据（片类型×历史失败率×代码量预估）→ 映射模型档；无新基建 |
| 4 | **切片并行化（结构工程，最大一格）** | 隔离副本＋依赖 DAG＋90% AI 自动解冲突＋review-then-apply（§1.1） | 严格串行＋片间交接耦合（ADR-0011 备案待评估） | 渐进：先识别无依赖片（PRD 切片时标注）→ 隔离副本并行 → AI 辅助合并；**注意 Replit 代价**：merge 互毁投诉、审批疲劳、成本 2x——并行度应按用户档位给（Replit 自己 Core 只给 1） |
| 5 | **checkpoint 全状态** | 代码＋DB＋会话上下文＋agent memory 一体双向回滚（§1.4） | git 版本流＋ADR-0020 断点续跑（等价物在） | 缺口在 DB 快照与会话上下文回滚的一体化语义；量级中，价值＝迭代试错安全感 |
| 6 | **中途转向（steer）** | 运行中消息注入当前 turn（§3.2） | 单 turn 阻塞 converse，轮内不可插话 | 执行体侧需流式注入通道；优先级低于上四项 |

### 6.2 可借（形态直接可抄）

- **review-then-apply 任务语义**：我们每片本就 git 成版，差的是「用户审批后并入主线」的产品面（现在片一完成即入主线）——与 #313 方案 C 的协议档位化天然契合。
- **自测视频回放**：信任帧强化（我们收尾卡已有自测 ✅/❌ 清单，若补浏览器基建则录屏顺理成章）。
- **Compare models 变体**：同 prompt 多模型并行出变体挑最好——我们 run 级可实验性支持，对首版一次做对率是直接杠杆。
- **agentskills.io 开放标准对接**：我方自持技能线的载体若兼容 SKILL.md 形态，可直通 Replit/Claude 生态的技能库（如 mattpocock/skills 这类公开库）——借生态不建生态。
- **模型路由的成本口径**：「同质量 65% 更低成本」（Agent 3 时代官方数字）——运营面改档即生效我们已具备，补策略即可兑现。

### 6.3 不必学（反面教材，均有口碑证据）

- **effort-based 计费＋按 checkpoint 计价**：头号口碑灾难（$140/13min、$8k/月 runaway、退款被拒是常态抱怨）；我们按 run 计量的口径更稳，别引入「同一任务内多 agent 各自烧钱且不可预估」的形态。
- **「智能跳过终验」的极端化**：官方明说非 100% 测试＋安全默认不写＋「Potemkin interface」靠用户发现——我们「真实落库＋探活＋自测清单」的保证性收口是差异化优势，提速别拿它换。
- **并行一切的默认**：并发配额本身（Core 1/Pro 10）说明成本与 merge 风险压力；对非技术用户，10 个并行任务的审批负担是倒退（「You lose the granular back-and-forth」）。
- **过程可见性倒退**：thinking trace 移除、审批流复杂化引发老用户流失声量——与我们「过程呈现」产品观（#61/#62 调研立项的对标点）相悖，不做。
- **模板策略反复**：老模板停用风波说明「模板＝设计起点」的收窄定位是踩出来的，我方 Demo 轨若走「同栈跳 DB」路线不必建模板库（#313 已论证模板复用运行时近零收益）。

## 7. 证据与来源索引

**官方博客（curl 直取原文核字面）**：replit.com/blog/introducing-agent-4-built-for-creativity（2026-03-11，四支柱/并行分工/sub-agents 解冲突）｜whats-changed-agent3-to-agent4（2026-03-19，隔离副本/Kanban/collaboration）｜replit-raises-400-million-dollars（2026-03-11，**10x faster than Agent 3 唯一标题级出处**）｜live-from-hq-agent4-launch-pt2（2026-03-23，**专用模型流水线原话/90% 自动解冲突**）。首页 meta「minutes」（2026-11-06）。

**官方文档（Mintlify .md 端点直取，2026-11-06）**：features/agent/agent-modes（Free/Power/Max、Economy 撤档注记、Auto）｜features/agent/model-selector（模型清单/Effort ~2x/Compare models 4 变体）｜core-concepts/agent/task-system（配额 0/1/10/64、依赖检测、AI 冲突解、review-then-apply）｜features/agent/task-lifecycle（状态机/Archive vs Cancel）｜features/version-control/checkpoints-and-rollbacks（全状态捕获/双向/四触发时机/git 集成）｜features/agent/app-testing（2026-09-15 版，**智能触发非 100% 原话**/mock 数据/视频回放/JS+Streamlit only）｜features/security/agent-security-scans（Semgrep+HoundDog/威胁建模/Publish 入口）｜features/agent/skills（agentskills.io 标准/GitHub 导入限制/metadata 常驻）｜billing/ai-billing（effort-based/one checkpoint per request/第三方 API 按公开费率/计划能力表）｜features/agent/plan-mode（Plan 段也计费）｜features/agent/steer-and-queue-messages（Steer/Queue/队列管理）｜features/agent/general-agent（**「Unlike standard Agent with pre-configured environments」**/Connectors/Nix 自配）｜chat/routines（定时/确定性优先/per-run budget）｜design/start-from-a-template＋build/build-and-publish-from-template（Design 画廊模板/remix 流）｜updates/2026/09–10 九期 changelog（Cloud Agents/Monitoring Agent/BYOC/Meta/Windows 预览/custom APIs/MCP 桌面/Haiku 5.5/GPT-6.1 Terra）。

**工程博客（frontier.replit.com，全文直取）**：decision-time-guidance（2026-01-20，+15%/loop 原话、分类器每迭代、mock data 反制、提醒堆叠递减）｜automated-self-testing（2025-12-15，Potemkin interfaces、验证谱系、REPL code-use、notebook 持久化、DOM+ARIA+只读 DB+增量日志、测试子代理、$0.20/场、20→200 分钟、CUA 成本账 $0.50/30–90s 每五字段表单）。

**厂商联合案例（一手披露级别次之，已标注）**：restate.dev/use-cases/replit（Temporal→Restate、inline durable steps、Virtual Objects、durable cancellation 贯穿子代理、25k actions/sec/cell）｜langchain.com/blog/customers-replit（LangGraph/LangSmith、15k spans/trace、<100k 上下文无压缩、5k traces/min）。

**社区一手（Arctic Shift 存档逐帖取回核日期，子代理取证）**：agent-finder.co/reviews/replit-agent（署名计时实测五档）｜madebyagents.com/blog/replit-agent-4-review-for-business-owners（34min/$17/安全审计三无）｜creatoreconomy.so（Peter Yang 10min 全流程）｜r/replit 约 30 帖：1rsrltw（95min 小改动）、1rx9xjy（正评）、1rwlhsd（45 plans 冻结）、1vqvfps（修复永动循环）、1vt4fo9（checkpoint 不自动）、1xe6tfz/1xebfkx（2026-10 循环与指令不匹配）、steven-usa 52s→10–30min（2026-03-11）、成本帖（$140/13min、$1,982、$8k/月、$5,419/7 周）等。

**二手（已标注可信度）**：espressio.ai Replit Guide 2026（Agent 3 34min/11credits 自测＋buildathon 10x 转述＋Agent 3 发布日 2025-09-10）｜GILD Intelligence 2026-10（成本带 $8–$8,000/月汇总）｜Latent.space AINews（2026-03-12 定位「Knowledge Work Agent」）｜HN algolia 检索（热度极低：2 帖 0–1 评论，作为分布证据）。

**既有仓内调研复用**：#313 demo-track-vs-production-split.md（我方耗时构成基线、提速手段排序、Replit 四杠杆 Agent 4 时代初引）｜2026-09-03 benchmark 三篇（agent 循环形态、自测行业标配）｜2026-10-03 design 调研（沙箱零截图基建备案）。

**未证实备案**：①「3x faster」在今日官方页面不可复现，Wayback 跨境不可达未核 3 月原版——若需终审可后续翻档案；②10x 无方法论披露，任何拆解（并行×路由×Turbo）均为推断；③docs 新旧两套模式术语并存（§0 备案），Turbo「2.5x faster/6x cost」仅存于旧 replitai/agent 页。
