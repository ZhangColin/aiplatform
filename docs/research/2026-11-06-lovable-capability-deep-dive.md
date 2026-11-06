# 调研快照：Lovable 全能力面（2026-11-06）

> 结论关联：wayfinder #413（本票）、#404（生成体验三线决定集：一次做对·净提速·先于客户）；口径同 #412（Replit 票）：不限速度、以竞品视之的全景调研，产出对标差距清单（我们缺什么、哪些可借、哪些不必学）。
> 基线声明：本仓 [2026-09-03 对标快照](2026-09-03-benchmark-ai-app-builders.md) 已有 Lovable 过程可见性画像，本文刷新至 2026-11-06 现状并在 §7 列「相对 09 快照的增量变化」；09 快照已确证的事实（Build/Plan 双模式、活动卡、版本/回滚、Drafts、提问卡、预览刷新节律等）不重复展开。
> 方法注记：三路并行取证（迭代时长与社区口碑／基础设施工件／Replit 对比）＋落稿人自查（生成架构与质量哲学两条主线）。落稿人逐字复核：官方教程哲学页、testing/agent-mode/goal-runs/glossary/cloud 六个文档页全文、TanStack Start／OJ／How we built Lovable／Inside Chats／model-picker 五篇工程博文全文、Vitest changelog 条目。取证代理转引的 changelog 条目与社区帖均在文内标注。调研日 2026-11-06。

## 结论速览

1. **「快」的真相是窄域＋frontend-first＋预置件，不是更强的 agent**：Lovable 官方推荐路径是「先用浏览器内假数据把界面跑通，再接库」，首版 5-15 分钟出的是**前端优先原型**（可含一键 Cloud 真库），复杂功能再加 30-60 分钟。我方净生成 1h50m 对标的是**全栈真库首版**，口径不同——但「首版快」的体感差距真实存在，杠杆在预置件厚度与首版范围裁剪，不在模型。
2. **生成架构已公开到「语义级」**（09 快照时「协议未公开」）：核心是 **Trajectory 事件日志**（Git 式 append-only、可 fork）＋**Agent Control Plane**（SpawnAgent/ForkAndSendMessage 等原语＋activation 队列）＋沙箱与 agent 舰队分离、可挂起重放。工作日 **~5 亿事件/260 万用户轮**。单条用户消息 = 一个服务端 agent 多步循环（interpret→explore→change→check→summarize），**不是切片流水线**；长任务形态是 `/goal` 单消息跑到完（≤10h、不提问、只做 credit check-in）。
3. **模型分工无 visor/hero 公开证据**（票面假设判「查无证据」；glossary 里 hero 是「Hero section」设计术语）。已公开的分工事实：官方反对模型选择器（"The model picker is a dead end"），由**控制平面按任务把构建的不同部分分派给不同模型**；**自研后训练模型已承载生产 app-building 工作的 meaningful share**（从路由/摘要/commit message 起步）；Chats 侧 agent 用「另一套为快对话调优的模型设置」；整应用级 eval（换模型 A/B：快 15%、少 40% 轮次）。
4. **验证策略是「用户点菜式」而非常开自检**——与 v0 相反。官方原话："Most verification tools run only when you ask for them... verification does not run silently in the background."。自动面只有：agent 循环内置 check 步、改规则自动写小测试并跑完才算完成（2026-10-06）、构建错误自动修（-91% 自报）。**一次做对率是 Lovable 社区最大痛点**（"You get 80% of the way in the first 5 minutes... the last 20% costs another 40 attempts and 300+ credits"）——这是我们的超越点而非追平点。
5. **并行度刻意保守**：主 agent 串行循环；并行只开放给**只读研究 subagents**（"cannot change your project"）与跨项目 fan-out（Chats 的 send_message_to_project 并行）。写路径的整机并行（Camille：可 fork/merge 整台运行中的计算机）是引擎能力、支撑 Drafts，但用户面 Drafts **共享项目数据库**（代码分叉、数据不分叉）。他们在 2024 年造过自主多代理（建任务、委派子代理），因不可预测**删掉了几乎全部**——「one user request → rather predictable chain of tools」。
6. **基础设施工件三层**：① 平台维护模板（2026-05-13 起默认 TanStack Start SSR＋Cloudflare Workers，模板内置 Vitest）；② 注入规则而非复制代码（与 TanStack 团队共建、以其 AI skill 为起点的 curated rules 注入每次新栈请求）；③ 通用件产品化为服务（Cloud 后端/部署/域名/支付/SEO/邮件/分析/MCP），生成时模型只写功能代码＋DB schema＋迁移＋小测试。
7. **预览基建刚换发动机**：2026-09-15 起预览迁到自研 **OJ**（Rust 版 Vite dev server，已开源）：~100 万沙箱/日、沙箱获取 14.5s→3.0s、可用中位 17.4s→8.0s、内存 ~6.5x↓，且 agent 突发写入「攒批一次刷」（hold and release as one batch）。
8. **社区口碑两极且 2026 年中有真实恶化窗口**：好评集中在首版魔法（"tiny app in 10 minutes that saves me hours every week"）；差评集中在迭代长尾（"Every small change takes two days"）、credits 消耗激增（项目均耗 30-35→100+）、模型更替致质量回归（Gemini 2.5 输出劣化帖、2026-10-20 退役）。第三方评：2026 年测过的 builder 里 Lovable 生成物「代码最干净、设计最一致、体验最好」，但整体评分 2/5、定位轻量原型（Superblocks，竞品博客）。
9. **与 Replit 路线分野清晰**：Replit 赌**重 agent 并行**（Agent 4 主代理拆任务给 infra/frontend/backend/design 并行子代理，官方 10x 口径、并行仅 Pro/Enterprise、Restate durable execution、Review 阶段自动测试）；Lovable 赌**窄栈＋快回路＋产品化后端**（消息级 credit 计费、Chat/Plan 模式近免费）。同 app 双平台实测共识：Lovable＝前端快而精致、per-credit 更省、"junior engineer"；Replit＝后端/集成深、"mid-level engineer"、前端粗糙且单位成本高。两家都在修复循环上烧钱被骂。
10. **对 #404 三线的直接启示**：净提速→首版口径分段＋预览基建数字目标（§6.1/6.3）；一次做对→自动验证做成超越点（§6.5）；先于客户→模板＋规则注入＋技能线（§6.2，Lovable 官方拿「TanStack 维护的 AI skill」当规则集起点，与我们技能线同构互证）。

## 1. 生成架构

### 1.1 执行模型：单消息=服务端 agent 多步循环，非切片

- 官方循环口径（[docs chat](https://docs.lovable.dev/features/chat)，09 快照已复核）："Behind every message, Lovable's agent works in a loop: it gathers context..., takes action with tools, and checks the result before moving on."
- **单消息上限 10 小时**：[agent-mode docs](https://docs.lovable.dev/features/agent-mode)（2026-11-06 复核全文）"Lovable works on a request for up to 10 hours"（此前 2026-01 曾宣布 15 分钟上限，后大幅延长——changelog）。
- **长任务形态是 `/goal`**：[goal-runs docs](https://docs.lovable.dev/features/goal-runs)（2026-11-06 复核全文）："Goal runs let you set a goal and let Lovable work on it until the goal is achieved, all from a single message... doesn't pause to ask questions... builds the finished version rather than a first draft"；收尾前半小时自动 wrap-up、credit check-in 照常。**反切片取向**：把「做到完」压进一条消息，而不是把任务切成用户可见的分步。
- 用户侧切片纪律靠 prompt 教育而非产品切分：官方教程要求 "Build in small sections... small enough to judge... but not so small that pieces can't be meaningfully tested"（[from-idea-to-app](https://docs.lovable.dev/tips-tricks/from-idea-to-app)，一手，已逐字复核）。
- 架构语义已公开（2026-08-25 工程博文 [Inside Chats](https://lovable.dev/blog/how-lovable-agents-work-together)，一手，已全文复核）：
  - **Trajectory 事件日志**："every agent gets two durable logs: an inbox for external arrivals and a trajectory for its own work. The trajectory is an append-only, ordered event log modeled after Git... parent pointers and heads give us forking"；事件类型枚举公开（UserMessage/AgentStart/IterationStart/tool_call/ToolExecutionStart/Revert/PromptCompaction/ThreadForkConfig 等，流式 UI 的 PartialDeltas 走旁路不落库）。规模："On a typical weekday, our Trajectory System appends roughly half a billion events, covering about 2.6 million user turns."
  - **Agent Control Plane（ACP）**：SpawnAgent/ForkAndSendMessage/SendMessage/NotifyParents/StopAgent 原语＋Pub/Sub activation；agent 舰队与沙箱分离，"Durable state is the repository, not the box"（沙箱挂了从 repo 重建、agent 挂起在迭代边界、部署重启不死）。
  - **模型上下文=轨迹投影**："we render a fresh context from the event log on every model call"；压缩走 side trajectory 异步进行。

### 1.2 模型分工：无 visor/hero 证据，公开口径是「控制平面分派＋自训小模型」

- **visor/hero：查无公开证据**。全网络检索＋三篇工程博文全文＋glossary 全文均无此二名；glossary 中 hero 唯一出现是设计术语 "Hero section: The large banner area at the top of a landing page"。判定：内部代号未公开或讹传（GitHub 泄露系统提示词里 "hero variant" 同为设计词汇，[x1xhlol repo](https://github.com/x1xhlol/system-prompts-and-models-of-ai-tools)，二手）。
- 公开的分工证据（[The model picker is a dead end](https://lovable.dev/blog/the-model-picker-is-a-dead-end)，2026-07-29，一手，已全文复核）：
  - 专职模型团队，按模型定制指令/工具/上下文："dedicated model team... instructions, tools, and context shape... unique characteristics of each model"；
  - **自训模型上生产**："Now our post-trained models are handling a meaningful share of app-building work in production"（先做 routing requests、summarizing、commit messages）；
  - 轮中不随意换模："Mid-build switching... has costs... history has to be reinterpreted, caches are cold"；
  - **分派粒度到构建内部**：控制平面 "can assign different parts of the build to different models instead of asking one model to own the whole thing"；
  - 供应商故障转移（同模型多 provider）；优化单位是整应用轨迹（"The unit of optimization is the finished application"），内部 eval 用「LLM 裁判＋人工判断」，换模型基准："15% faster, 40% fewer turns, 2-3% higher scores"。
- Chats 侧分工：chat agents "use a different model setup... tuned for fast conversation"（Inside Chats 博文，一手）。每类用例（builder/subagents）有 prompt preset。
- 模型阵容变迁（changelog，一手，经取证代理转引）：Claude 4（~2025-08，"-25% less errors and be 40% faster"）→ Opus 4.5（2025-12-10，-20% errors）→ GPT-5.2 → 推出 model picker → **Gemini 2.5 于 2026-10-20 退役**（社区归因质量劣化的对象）。
- 历史纵深（[How we built Lovable](https://lovable.dev/blog/how-we-built-lovable-and-why-we-keep-rebuilding-it)，publishedTime 2026-09-24、页面资产含 2026-11 更新，一手，已全文复核）：2024 末形态是单次响应＋XML 文件标签（`<lov-write>`）生成完再解析，无开放式循环；更早的自主多代理系统（agent 建任务并委派子代理）因 "got stuck in rabbit holes, no predictable finish" 被 **"We deleted almost all agent code"**；2025-06 Agent Mode 才把循环带回来（-91% build error，自报）；2024 年还用微调小模型做上下文文件选择（"fine-tuned tiny LLMs to identify code dependent on the request"）。

### 1.3 并行度：只读并行＋跨项目 fan-out，写路径保守

- **只读研究 subagents 并行**（[glossary](https://docs.lovable.dev/glossary)，2026-11-06 复核全文）："Temporary, read-only agents that Lovable spins up to research, inspect, or review focused parts of a task **in parallel**. Subagents report findings back to the main agent but **cannot change your project**."（2026-01 上线，09 快照已记）。
- **跨项目并行 fan-out**（Inside Chats 博文，一手）："Several send_message_to_project calls in one turn run the projects in parallel"，结果经 inbox/NotifyParents 回流，终态带 credits/files changed/build status。
- **整机快照/fork 引擎 Camille**（How we built 博文，一手）："a framework for forking and merging entire running computers... checkpoint machine memory, CPU state, and disk... restore... in seconds... fork an entire application, environment, and database... let agents try different approaches from the same starting point."
- 用户面并行=Drafts：每草稿独立聊天与预览，但 **"Every draft uses the project's database, so data a draft adds or changes is added or changed for the project too"**（glossary，一手）——代码分叉、数据不分叉（Camille 的 DB fork 未下沉到该产品面）。
- 对照：Replit Agent 4 才是「写路径并行」路线（主代理拆任务→infra/frontend/backend/design 子代理各自隔离环境并行写，见 §5）——Lovable 明确试过并放弃，理由是可预测性。

## 2. 验证与质量策略

### 2.1 「Sample data lives only in the browser」的原文、语境与哲学

- 出处锁定（一手，已逐字复核）：官方教程 [How to build a real product with Lovable](https://docs.lovable.dev/tips-tricks/from-idea-to-app)。原句语境——"Here is the moment you outgrow the frontend: a client books a session, you refresh the page, and the booking is gone. **Sample data lives only in the browser. When information needs to persist, your app needs a database.** Lovable includes a full backend. Enable the built-in backend (Cloud) and describe what to store."
- 哲学被官方一句话说破（同页 Plan 节）："**build frontend-first. Start with sample data, get the screens and flows right, and connect the database once the product feels right. Frontend-first iterates faster and keeps early sessions about your product instead of your data model.**"
- 质量哲学的完整拼图（同页，一手引文）：
  - 小步快循环："the smaller the first prompt, the faster and more accurate the results"；"Bugs live at the boundaries between features"（多 feature 一次生成会互相破坏）；
  - 首版就要边界态：提示里要求 empty/loading/failing states（"every screen has... a fallback message for a failed fetch"）；
  - 验证动作教给用户：两标签页刷新测试（验持久化）、两账号互访测试（验权限）、发布前跑 Security scan（免费）、上线后接 monitoring；
  - 测试口令是「结果」不是「点击」："Ask for outcomes, not clicks... Run the test and review the JSON results in chat"；
  - 已知好态锚点："Bookmark a known-good version... rather than debugging, revert"。
- 结构性动机（官方自认）：Lovable 起点是 SPA——"single-page applications are fast to prototype but have limitations"（[Supabase 客户故事](https://supabase.com/customers/lovable)，Lovable 参与的一手）；2026-05 默认栈切 SSR 后该哲学仍在（教程仍是 frontend-first 叙事），但 Cloud 时代「先假数据」已是**推荐路径**而非能力限制——2025-10-06 起 Cloud 内置后端默认可用、"generates a database schema that is immediately usable"（[cloud docs](https://docs.lovable.dev/features/cloud)，一手，已复核）。

### 2.2 验证工具面：点菜式，非常开

- [testing docs](https://docs.lovable.dev/features/testing)（2026-11-06 复核全文）四件套：**Browser testing**（真实浏览器跑流、逐步截图、失败出 JSON 报告回给 agent）；**Frontend tests**（Vitest＋React Testing Library＋jsdom）；**Direct edge function calls**（拿真实 JSON 回应修逻辑）；**Deno edge tests**（独立测边缘函数）。
- **关键口径（一手）**："**Most verification tools run only when you ask for them**... verification does not run silently in the background. When you ask Lovable to test your app, these tools run against your live preview." agent 自主发起的情况主要在 investigate 阶段。
- 自动化的例外（质量闭环里唯一「 silently」的两处）：
  - agent 循环内置 check（遇错自修，Agent Mode -91% build error，自报）；
  - **规则变更自动测试**（changelog 2026-10-06，一手转引）："Lovable writes and runs tests for the rules you change — Lovable now adds a small test when you add or change a rule in your app, such as a price, a limit, or a permission. It runs the tests that cover the change **before it reports the work as done**, and keeps the tests in your project."
  - 模板内置 Vitest（changelog 2026-01-16，已检索复核）："Vitest is now included in the React template, enabling the agent to write and run tests without additional setup."
- 首版缺陷的用户面处理：错误卡上 **Try to fix**（免费池 10 次/天、24h 恢复，changelog）；显示真实错误信息；goal runs 「continue fixing issues until the goal is achieved」；安全面 Basic/Deep 扫描免费＋[Aikido 渗透测试集成](https://docs.lovable.dev/features/testing)（一手）。
- **质量哲学的一句话概括：把「一次做对」外包给三个便宜机制——窄首版（小 prompt）、快回退（书签/版本）、用户点菜的验证工具；而不是常开自检。** 这与 v0「沙箱常开验证＋agent 自动截图回放」是两个极端（09 快照 §4），Lovable 选了省 token 的那一端。

### 2.3 质量的现实面（社区口碑，二手标注可信度）

- 长尾定律（r/lovable，2026-09 下旬，[20,099 credits 告别帖](https://www.reddit.com/r/lovable/comments/1vfc78r/)，中）："You get 80% of the way to your goal in the first 5 minutes (if you have a good prompt) but the last 20% costs another 40 attempts and 300+ credits."
- 迭代破坏（r/lovable，约 2026-10-28，["Every small change takes two days"](https://www.reddit.com/r/lovable/comments/1w1eizo/)，中）："Lovable initially builds quickly but it makes changes that break other things in the app. This is my experience with every project."
- 2026 年中 credits 激增＋质量回归窗口（多源交叉，中）：项目均耗 30-35→100+ credits（[we.inc](https://we.inc/blog/lovable-credit-usage-increase-2026)，2026-06-26）；["$570 in 6 months"](https://www.reddit.com/r/lovable/comments/1q7edj4/)；["Degradation of AI outputs"](https://www.reddit.com/r/lovable/comments/1s3toy8/)（点名 Gemini 2.5 Flash，官方 2026-10-20 退役该模型）；["Lovable apps breaking left and right in 2026?"](https://www.reddit.com/r/lovable/comments/1s0v370/)。
- 反面佐证（质量上限不低）：Superblocks 评测（2026，竞品博客，中）："Among all the AI app builders we've tested in 2026, Lovable-generated apps have, on average, the cleanest code, the most consistent design, and the best user experience"——但整体评分 2/5，判其适合原型不适合严肃产品。
- 官方承认的安全事故：2026-04 有公开事件与响应文（[Our response to the April 2026 incident](https://lovable.dev/blog/our-response-to-the-april-2026-incident)，一手存在性确认，正文未逐字复核）。

## 3. 迭代回路体验：时长分布与口碑

### 3.1 需求→可交互时长（数据点表）

| 档 | 时长 | 证据 | 类型/可信度 |
|---|---|---|---|
| 首版（简单 app） | **5-15 分钟** | 官方首页营销语 "around five minutes"（经 [Superblocks 评测](https://www.superblocks.com/blog/lovable-dev-review)转引）；Series C 博客 "from vision to a finished product in **10 minutes**"（官方，一手转引）；实测："first functional version within 5–10 minutes"（[Clover Labs 三方对比](https://cloverlabs.io/2026/06/21/lovable-vs-bolt-vs-v0-ai-app-builders)，2026-06-21）；"Around five minutes later, I had a working app with full signup/sign-in flow, transaction history, and charts"（Superblocks 实测） | 官方宣称高＋二手实测中 |
| 首版（含真库全栈） | ~15 分钟/5-10 credits | "新项目 5-10 credits、约 15 分钟出首个可用版本"（we.inc，2026-06-26） | 二手中 |
| 复杂功能增量 | **+30-60 分钟** | "more complex features typically take another 30–60 minutes"（Clover Labs） | 二手中 |
| 迭代小改（官方口径） | **秒级-分钟级** | inline 文本编辑 "now apply in a few seconds"（changelog，一手转引）；预览 "appear as soon as your app is ready" | 一手高 |
| 长任务 | 单消息最长 **10h**；/goal 显式跑到完 | agent-mode/goal-runs docs（一手，已复核） | 一手高 |
| 迭代长尾（社区体感） | **小时-天级** | "spent a good 4 hours just trying to get it right"（r/lovable，2025-12）；"Every small change takes two days"（2026-10） | 社区中 |

**读数**：①竞品 40-60min 的主观印象对应的是「首版＋少量迭代」的混合口径，其首版档（5-15min）远快于我方 1h50m——但首版范围不同（frontend-first 原型 vs 我们全栈真库）；②无任何官方端到端 SLA 数字；③无第三方统计级 benchmark（全部 n=1 实测）。

### 3.2 口碑主题

- 好评锚点：首版魔法（"I built a tiny app in 10 minutes that saves me hours every week"，r/lovable 2026-05，中）；非技术可用性（"build full websites with database storage and automated email without touching code"，HN 2025-02，中）。
- 差评锚点：credits 经济学（修复自己的错也计费——"Burning credits (at light speed) to fix its own errors makes it even worse"，r/lovable 2026-09，中）；复杂逻辑死循环（社区共识：Lovable 在复杂集成上打转=该功能需要正经后端的信号，中）；规模化成本（HN Series C 串主旨 "very easy to get started, it simply becomes too expensive"，中高）。
- 迭代小改口碑两极：官方秒级 inline edit vs 社区 "0.5 credit/条语法级小改、几 credits/消息"（r/lovable 2026-10，中）。

## 4. 基础设施工件：模板、预置件与「只写功能」的边界

### 4.1 默认栈与模板（2026-11 现状）

- **2026-05-13 起默认栈＝TanStack Start（SSR）**（[官方博文](https://lovable.dev/blog/building-apps-using-tanstack-start)，published 2026-06-01，一手，已逐字复核表格）：
  | 维度 | 旧（≤2026-05-12） | 新（默认） |
  |---|---|---|
  | Framework | Vite + React Router | TanStack Start (TanStack Router) |
  | Runtime | Cloudflare Pages 静态托管 | **Cloudflare Workers** |
  | Rendering | CSR only | **SSR/SSG/CSR per-route** |
  | Server logic | Edge Functions | TanStack Server Functions |
  | DB/auth/storage/realtime | Supabase | Supabase（不变） |
  | Secrets | — | Cloudflare Workers bindings |
- 动机与收益（一手）：SEO/可发现性（预渲染后 "+2.9% organic... +98.5% from AI tools"）；旧栈项目可**原位升级**（changelog 2026-07-28，"keeps your Git history"）；Enterprise 亦已切（2026-06-22）。FAQ（一手）："New Lovable apps created from May 13, 2026 use TanStack Start with server-side rendering (SSR). Older apps use React + Vite."
- **模板是平台侧工件**：changelog 称 TanStack Start 为 "Lovable's latest template"；模板内置 Vitest（2026-01-16）。Styling=Tailwind、组件=shadcn/ui（系统提示词口径，二手中高）。
- **注入规则而非复制代码**（TanStack 博文，一手）："Lovable's AI is given a **curated set of TanStack-specific rules, injected into the context of every new-stack request**. We didn't build this knowledge base from scratch, we've been working with the TanStack team, **using the skill they maintain for AI tooling as a starting point**."——以第三方维护的 agent skill 起步构建规则库，与我方技能线思路互证。

### 4.2 模板与预置件面

- 模板三层（FAQ，一手）：**Lovable templates**（官方策展）/ **Workspace templates**（自建）/ **Community templates**（社区）；lovable.dev/templates 免登录可逛；remix=整库拷贝成自己的项目。Business/Enterprise 可设**默认设计模板**保证新项目结构一致（changelog 2026-02-05）。
- **Design systems**（付费）：设计系统附着项目后 "every build inherits your components, tokens, and design rules"，文件落 `src/design-system` 常规代码；npm 包包装为设计系统＝Enterprise 专属（changelog 2026-08-19/06-25，一手转引）。
- 旧 **Figma Import 已死**（"removed due to quality issues"，2025-11-26），替代：Figma 插件导出（2026-08-21，≤100 文件/20MB）＋聊天直挂 .fig（2026-08-16）。
- 旧 **Dev Modes（FSDM/Gindre/Swift/RN）已无痕迹**：官方 blog/changelog 全文（2024-11→2026-11）无任何条目；FAQ 明言 "Lovable does not generate React Native projects."（本地开发官方形态=GitHub 双向同步＋外部 IDE；取证代理结论：只能证明「现在没有」）。

### 4.3 后端面：Lovable Cloud（首版即真后端）

- 定位（changelog 2025-10-06，一手转引）："Build full-stack apps with no manual Supabase setup. Lovable Cloud gives you on-demand databases, user authentication, and storage."
- **底座公开**（[cloud docs](https://docs.lovable.dev/features/cloud)，2026-11-06 复核全文）："**The built-in backend utilizes Supabase's open-source foundation.**"——Cloud=基于 Supabase 开源基座托管；托管面含 Database（SQL editor/edge functions/jobs/realtime/schema 可视化）、Users、Storage、Emails、Usage；扩展 pg_cron/pg_net/vector/postgis。
- 默认开启、按需自动启用、权限分级（"Always allow" 为默认档）；区域可选 Americas/Europe/APAC 且**选后锁死**；**无 Cloud→自带 Supabase 迁移路径**（官方 FAQ 明言 no migration path——锁入面）；反向路径存在：禁用内置后端的 workspace 可让 Lovable 代建 Supabase 项目（changelog 2026-10-20）。
- 工程化配套（changelog，一手转引）：DB 变更落 **Drizzle migrations** 进用户仓库（2026-10-07）；Webhooks v2＋公开 Cloud REST API（2026-09）；workspace Events & hooks（2026-10-31）；GitHub 双向同步、Bitbucket 同步（2026-09-25）；zip 导入项目（2026-10-29）。

### 4.4 「生成时只写功能」的程度

- 平台预置：模板脚手架（含测试基建）＋注入规则集＋整套产品化服务（Cloud/部署 Workers/域名 SSL/支付 Paddle/Stripe/SEO 与 favicon 自动生成/分析/邮件/Lovable AI 网关/MCP connectors）。
- 模型现写：页面与业务代码、DB schema、Drizzle 迁移、规则小测试。
- **结论：通用件几乎全部产品化，模型在厚模板上只写功能**。这也解释首版 5-15 分钟：起点不是空 repo，是「模板＋规则＋一键后端」。

## 5. 与 Replit 的路线差异

> Replit Agent 4 口径校准（重要，票面背景假设有误）：发布日 **2026-03-11**（官方博文署名），提速宣称为 **"10x faster than Agent 3"**；"并行子代理 3x" 无官方出处（取证代理多渠道核查）。

| 维度 | Lovable | Replit（Agent 4） |
|---|---|---|
| 核心赌注 | 窄栈＋可预测工具链＋快回路 | 重 agent 并行＋云端全生命周期 |
| 并行 | 只读研究子代理并行＋跨项目 fan-out；写路径串行（2024 年删过自主多代理） | 主代理拆任务→infra/frontend/backend/design **并行子代理各自隔离环境写码**（官方："multiple agents working at the same time... in isolated environments"）；宣称自动解 ~90% merge conflict；**并行仅 Pro/Enterprise** |
| 执行底座 | Trajectory 日志＋ACP；沙箱可弃（repo 为真源）；OJ 1M 沙箱/日 | Restate durable execution（"thousands of fast, inline durable steps per turn"） |
| 验证 | 点菜式工具＋规则自动测试＋agent 内置 check | 流程内置：Plan→Build（自测自修）→**Review（写自动化测试、审查修复后才提交审阅）**→Merge；checkpoint 自动落、可连开发库回滚 |
| 模型 | 无 picker、控制平面分派＋自训模型、整应用 eval | "routing specialized models for each step"（未披露名单） |
| 计费 | 消息级 credits（Standard 1 credit；Plan 1；**Chat 免费**；free 5/day）；修复免费池 | credits≈现金（100 credits≈$25）、checkpoint 也耗 credits；Core $20/月 |
| 快在哪 | 首版速度、UI 精致度、per-credit 成本、非技术可用性（同题实测双源共识："junior engineer" 但表单/表格又快又好） | 后端/集成深度、栈灵活性（可换语言/自定义包）、长自治任务（"mid-level engineer"） |
| 弱在哪 | 复杂逻辑循环返工、credits 长尾、Cloud 锁入、模型更替质量波动 | 单位成本高（"$25 in less than 15 minutes"帖）、前端粗糙需多轮、agent 失控毁数据个案（"Lost 90% of my database data"）、高档锁并行 |

- 同 app 双平台实测（最承重两篇）：[Superblocks](https://www.superblocks.com/blog/lovable-vs-replit)（2026，竞品博客，中高）与 [DesignRevision](https://designrevision.com/blog/replit-vs-lovable)（2026，中）结论一致——Lovable 前端快而精致、Replit 后端集成强；后者测试时**用不了 Agent 4**（并行高档专属），说明 Replit 最强形态对多数用户不可得。
- 无受控计时 benchmark（[ToolJet 2026-10](https://blog.tooljet.com/replit-vs-lovable) 明言），所有对比皆 n=1。
- **对我们**：Replit 证明「并行写代理」可行但有代价（冲突消解、成本、高档门槛）；Lovable 证明「窄域可预测」能赢首版体验。我们的切片架构介于两者之间——顺序切片（Lovable 式可预测）＋每片独立沙箱（具备 Replit 式隔离的前提）。

## 6. 对标差距清单（我们缺什么、哪些可借、哪些不必学）

> 我方基线：一句话需求→PRD 梳理→切片化生成（Next.js 全栈基座＋真库真 Redis）→沙箱预览→迭代；痛点＝净生成 1h50m vs 竞品主观 40-60min、首版一次做对率。

1. **首版速度：口径差先摊开，再补「快车道」选项**（缺：分档首版形态）。Lovable 首版 5-15min 是 frontend-first＋假数据口径；我方 1h50m 是全栈真库口径。可借：PRD 确认门后提供「先壳后库」两段式（首片出可交互 UI＋种子假数据，真库切片随后）——这正是 Lovable 官方教程的推荐路径，等于是把他们的 prompt 纪律产品化进切片编排。**注意**：他们自己也承认 SPA 原型有极限；我方全栈一次交付是差异化卖点，快车道应是「选项」不是「降级默认」。
2. **预置件厚度：模板＋规则注入＋通用件服务化**（缺：规则注入层；基座已有）。可借：①为 Next.js 基座维护一套「curated rules」注入每次生成（Lovable 拿 TanStack 官方 AI skill 起步——我们技能线可直接承载）；②把仍由模型现写的通用件（认证壳、布局、部署配置）继续从生成面挪进基座/平台面；③模板三层（官方/工作区/社区）＋设计系统附着是成熟形态，对我们即「基座变体＋工作区默认模板」。
3. **预览基建：数字目标明确**（缺：快启动与中间态；09 快照已判「差距最大一格」，现有具体标尺）。OJ 给出行业标杆：沙箱获取 14.5→3.0s、可用中位 17.4→8.0s、agent 突发写入「攒批一次刷」。可借：沙箱预热池（切片开始前容器就绪）、轮内增量预览（写入合并节流刷新，而非探活瞬切）。注意 eu-1 跨境带宽现实（#403）：预览快慢还要量传输字节，不可只学沙箱指标。
4. **过程架构：事件溯源语义**（半缺：SSE 帧已有，缺「事件=唯一事实、UI=投影」的架构语义）。Lovable 用一份 append-only 轨迹同时喂流式 UI/恢复/fork/审计（"The same data powers streaming and history"）。我方 ADR-0011 切片会话交接＋#225 直播卡方向与其同构，可按此语义收口：过程帧先落事件账、再由前端投影成卡片/统计/回放。
5. **验证策略：他们的空窗=我们的超越点**（缺：每切片自动冒烟；这是 #404「一次做对」线的直接抓手）。Lovable 验证点菜式（"only when you ask"），v0 常开沙箱验证；社区证明 Lovable 长尾全烧在「最后 20%」。我方切片本就有天然验证锚点（每片完成即冒烟＋浏览器自测回放截图），做常开即同时超过 Lovable（点菜式）并追平 v0；规则级小测试（改价格/权限自动写测试跑完才报完成）是低成本可直采的机制。
6. **并行度：借只读、缓写入**（现架构顺序切片是对的）。可借：并行只读研究子代理（调研竞品/API 文档/库选型，零冲突风险、直接压 PRD 与切片规划时长）；跨项目 fan-out 形态暂无对应场景。不必学：Replit 式并行写代理（Lovable 自己删过一次：不可预测；Replit 要靠 90% 自动 merge 消解+高档门槛兜底）。远期：Camille 式整机快照可让「并行试错后择优」成为可能，先记为扩展点。
7. **长任务形态：/goal 式「别停」开关**（缺：显式长跑模式）。我方切片=产品化分步，但用户确有「这个小需求别问我、做完再叫我」的诉求；可在 run 级加「连续完成不问询」档（保留资源/成本 check-in——Lovable 20 credits 阈值询问机制可直采）。
8. **信任机制**（部分缺，09 快照已判：版本快照/回滚全无）。维持结论：无保存按钮自动版本、书签已知好态、回滚只回代码不回数据。新增可借：**修复免费池**（10 次/天——把「平台自己弄坏的免费修」制度化，直接对冲 Lovable 社区最大骂点「烧 credits 修自己的错」）；Chat 式免费问答档（不动代码的咨询零计费）。
9. **成本透明**（缺：实时已耗时/已花费）。Lovable More options 实时显示工作时长（排除等用户）＋已扣 credits＋完成定格；不做事前估算（"cost depends on work that is discovered during execution"）而做事中 check-in。我方 1h50m 净生成若无分段成本可见性，用户焦虑只会更大——轮内实时已耗时/已生成切片数是低成本必做。
10. **不必学清单**：模型选择器（双方同判 dead end，维持我方模型抽象）；credits 按消息计费的长尾经济学（修复计费已被社区反噬）；Cloud 式锁入（区域锁死、无迁出路径——我方「真库真 Redis＋代码所有权」应反着打）；frontend-only 的能力天花板（复杂逻辑循环是 Lovable 结构性弱点）；Gemini Flash 级成本敏感模型混入默认档而不做质量回归（他们 2026 年中口碑恶化的直接教训——换默认模型必须带整应用级 eval，"unit of optimization is the finished application" 这句值得直采进我们的改档 SOP，参照 docs/agents/demo-stage-model-tier.md）。

## 7. 相对 2026-09 快照的增量变化（刷新点）

1. 事件架构从「协议未公开」→「语义公开」（Trajectory/ACP 博文，2026-08-25）；过程=数据模型的判断从行为面推断升级为官方自述。
2. LSO（Stack Overflow 知识注入）**已移除**："the Lovable Stack Overflow has been removed"（How we built，2026-09-24）——09 快照的 LSO 条目过时；替代思路是 OJ 提速（错误快速暴露胜于预注入知识）。
3. 预览发动机换 OJ 并开源（2026-09-15），沙箱获取 3s/可用 8s 成为新标尺。
4. 模型路由哲学公开＋自训模型上生产（2026-07-29）；模型阵容经 GPT-5.2/Opus 4.5/Gemini 2.5（2026-10-20 退役）多轮更替。
5. 默认栈 2026-05-13 切 TanStack Start SSR＋Cloudflare Workers（旧栈可原位升级）。
6. Cloud 深化：Drizzle 迁移落库、区域选择（选后锁）、Webhooks v2/API、Events & hooks；「底座=Supabase 开源基座」首次官方明言。
7. 质量工具：规则变更自动测试（2026-10-06）；Vitest 已在模板（2026-01-16，09 快照漏记）。
8. 口碑面新增：2026 年中 credits 激增＋模型更替质量回归窗口（多源交叉）；Series C $400M@$13.3B、ARR surpassing $500M（2026-08）。
9. Dev Modes/FSDM/Gindre：09 快照未记，本次确认 2026-11 已无官方痕迹（曾存在于 2025 年末的第三方记录与本仓旧对标讨论，现判下线）。

## 8. 未证实事项备案（如实，不推测）

- **visor/hero 模型分工**：查无公开证据（判：内部代号未公开或讹传；hero 在公开语料为设计术语）。
- Lovable 具体模型清单/配比（哪些环节用哪个厂商模型）：官方仅给机制不给名单；「自训模型承载 meaningful share」无百分比。
- 事件流 schema（字段级）与 SSE/WS 线协议：博文只到事件类型枚举与存储（Bigtable+blob store）口径。
- Chats（workspace 级 agent）是否全量开放、计费：博文称 "recently introduced"，开放面未证实。
- 首版端到端时长：无官方 SLA（"around five minutes"/"10 minutes" 为营销文案）；无第三方统计级 benchmark（对比文全部 n=1）。
- Replit Agent 4 具体 3x/10x 的构成拆解：官方仅 10x 总口径＋并行子代理定性描述；"3x" 无官方出处。
- OJ 全量接管比例：博文为 "rolling out gradually"（2026-09-15 时点），当前覆盖率未证实。
- 二手可信度档：官方 changelog/docs/blog＝一手（转引处已标注）；TechCrunch/Socket/daily.dev＝二手高；Superblocks/Clover Labs/we.inc 评测＝二手中（n=1 实测，Superblocks 有竞品利益）；Reddit r/lovable＝中（已尽量不单独承载关键论断）；aimagicx/digitalapplied 等＝中低（仅摘要级）。

---

**复核清单**（落稿人逐字复核的一手原文）：from-idea-to-app 教程全文、testing/agent-mode/goal-runs/glossary/cloud 五文档页全文、TanStack Start 博文（含栈对照表逐格）、OJ 博文（含全部数字）、How we built Lovable、Inside Chats、The model picker is a dead end 三博文全文、Vitest changelog 条目（检索复核）。其余 changelog 条目（日期+引文）与社区帖由取证代理全文抓取转引，文中已逐处标注来源与日期；引用 Reddit 相对时间均为约值。
