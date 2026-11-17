# 调研报告：竞品「又快又好」的生成策略——Replit / Lovable / v0 / Bolt ＋ Google Stitch / AI Studio Build

> 票 #511（wayfinder:research，地图 #510「生成『快与美』策略」子域），2026-11-15。
> 方法：官方 docs / 官方博客 / help center / 官方论坛一手来源逐条取证（三路并行调研汇总裁决），每条结论带 URL、访问日期均为 2026-11-15；二手来源与推断显式标注；无一手证据处明示。
> 与前次报告分工：#447（docs/research/2026-11-11-interview-build-pattern-lovable-replit.md）已定论「访谈模式与首版时长」（Lovable 约 10 分钟/问题卡 ≤4 题、Replit 10 分钟教程、Bolt 约 5 分钟、v0 无访谈门），本报告不重复，只回答「**为什么又快又好**」——生成策略四件套（预置/范围/会话/模型）、Stitch 与 AI Studio Build 机制、示意级取舍证据、可借鉴清单。

## 结论速览

1. **预置/模板：首版快的第一来源不是模型，是现成件。** 四家全部把数据库/鉴权/支付做成「一条 prompt 即得」的托管预置件（Lovable 内置 Cloud〔Supabase 底座〕workspace 默认启用；Replit 每 App 默认 Postgres＋Replit Auth zero setup；v0 Marketplace 集成即自动开户注入环境变量；Bolt 静默自动建真库）。v0 更进一步：模型「specifically trained on the default implementations of the shadcn/ui components」——预置件直接进了模型先验。
2. **范围控制：四家一致「窄而深＋显式推迟清单」。** Lovable「one user, one action, one outcome」「No login or database yet」；Replit 首 app 教程 prompt 模板原句「Do not add login, a database, payments, or external integrations yet」；v0 以全功能电商为 Too Complex 反例（先做单页＋localStorage）；Bolt「Do one thing at a time」。首版只深做单条核心路径，宽功能显式推迟。
3. **会话结构：写路径收敛单代理，调查/测试只读并行；Replit Agent 4 是唯一的多代理并行写（每任务完整副本＋子代理解冲突＋共享 Kanban）。** 渐进呈现的标准件＝常驻 live preview 边建边刷＋版本链可回滚；Stitch 2026 升级为「Agent 流式直绘画布、边画边实时纠偏」。
4. **模型选择：没有一家把模型选择置于首版体验核心。** Lovable 完全不公开型号（自动升级）；Bolt 型号保密、只留 agent 档位（Standard/Max/Forge）；v0 公开复合模型家族——快慢分流做进管线（Quick Edit 小改快路由＋自研 AutoFix 比 gpt-4o-mini/gemini-2.5-flash 同效快 10-40 倍）；Replit 全第三方目录＋Auto 路由＋Effort 滑杆。共性是「小改走快路由、例行调查走轻模型、难题才上最贵模型」。
5. **Stitch：「配色先行」无官方流水线声明——官方形态是主题/配色为一等控制（prompt 彩词→主题选择器→DESIGN.md 设计系统层）＋首屏定调逐屏继承＋点击处自动生成下一屏；产物是高保真信息丰富含图片的设计稿＋静态数据前端脚手架＋可点击原型，生产明说去别处接后端。**
6. **AI Studio Build：「纯 Demo＋localStorage」是其 2025 初代形态且被系统提示词实证（持久化优先级原文「libsql, sqlite (WASM), IndexedDB, localStorage」；美学硬约束原文「simple but aesthetic」「Exclusively use Tailwind CSS」）；2026-05-14 起切全栈（React＋Node 服务端、Antigravity Agent、服务端 secret、一键 Cloud Run），但官方定位仍是 lightweight web apps。**
7. **示意级取舍：数据层光谱＝Lovable 浏览器内 sample data / Replit session 级 / v0 默认纯前端（接库为 properly mock）→ Bolt 最激进静默开真库；支付四家全部停在 test mode/沙箱；auth 在 Bolt 是显式停手线。**「功能展现丰富」的来源不是功能真做，而是 realistic sample data（两家官方均反对 placeholder 文案）＋高保真排版＋真服务的测试态。
8. **对本项目：速度主战场不在模型档位（本平台 flash 全量＋失败升档已与竞品同构），在两轴——基座预置扩张（支付示意件/默认主题套/图片能力下放系统轨/通用 UI 模式件）与复杂度治理（首版推迟清单进切片协议、演示数据由平台 seed 预置而非执行体建、切片数量跟信息量走）。** 详见第四节。

---

## 一、Replit / Lovable / v0 / Bolt：从几句话到可玩首版的生成策略

### 1.1 预置/模板程度

**Lovable：统一模板＋内置 Cloud，预置程度持续加码。**
- 新项目起点是统一模板而非空白脚手架：2026-05-13 起默认 TanStack Start（SSR）模板，此前默认 React＋Vite；官方博文给出新旧栈对照（数据库/鉴权/存储/realtime 两代均为 Supabase 系）。https://docs.lovable.dev/features/upgrade-to-tanstack-start.md ；https://lovable.dev/blog/building-apps-using-tanstack-start
- 样式体系官方口径 Tailwind CSS＋React（Glossary 定义）；shadcn/ui 为官方强证据但非条款式声明：changelog 宣布「Added the shadcn sidebar」、官方 knowledge 模板写「Use shadcn/ui components when possible」——「组件库是 shadcn/ui」无定义式官方原文（推断，二手 vibe-eval 佐证）。https://docs.lovable.dev/glossary.md ；https://docs.lovable.dev/features/knowledge.md ；https://docs.lovable.dev/changelog
- 后端已是「内置 Cloud」而非让用户配 Supabase：基于 Supabase 开源底座，workspace 默认启用，提供数据库、鉴权（email/phone/Google/Apple/Microsoft/SAML）、存储、edge functions、定时 Jobs；外接 Supabase 降级为备选。https://docs.lovable.dev/features/cloud.md ；https://docs.lovable.dev/introduction/faq.md
- 模板市场：官方 ready-made 应用模板 remix 即得独立副本；企业级 Design systems 以 React 组件库形式分发进各项目。https://docs.lovable.dev/introduction/faq.md ；https://docs.lovable.dev/features/design-systems.md

**Replit：反向操作——框架 starter 模板全部撤除，押注 Agent 现场定栈；但数据库/鉴权预置程度极高。**
- 官方明文「Language and framework starter templates…are no longer available on Replit」，新项目三入口＝Agent 直接脚手架/导入/Gallery remix；Agent 4 官宣「No manual stack selection needed」。https://docs.replit.com/features/project-setup/developer-frameworks.md ；https://replit.com/blog/introducing-agent-4-built-for-creativity
- 数据库默认自带：「All Replit Apps come with a database by default」——托管 Postgres、dev/prod 双库、20GB 免费、checkpoint 级时间旅行回滚。https://docs.replit.com/features/data-and-storage/sql-database.md
- 鉴权模板化：Replit Auth「zero setup」——一条 prompt 即装，底层 Firebase/Google Cloud Identity Platform＋reCAPTCHA＋Stytch；「The only way to implement Replit Auth is by using Agent」；要独立品牌切 Clerk（有迁移指南）。https://docs.replit.com/features/auth-and-identity/authentication.md

**v0：默认栈深度收敛＋预置件进模型先验＋一键开户。**
- 官方 FAQ 明言「v0 uses Next.js, React, TypeScript, Tailwind CSS, and shadcn/ui」；「While v0 can use other frameworks, Next.js provides the most reliable results」。https://v0.app/docs/faqs ；https://v0.app/docs/full-stack-apps
- 组件库是训练面：legacy 设计系统文档「v0 is specifically trained on the default implementations of the shadcn/ui components」——偏离默认实现反而质量下降。这是预置的最高级形态。https://v0.app/docs/design-systems
- 设计系统 2.0「skill 化」：导入时 v0 先生成一个小 starter app 存进 skill，此后「the agent can work on your prompt without using time and tokens to repeat the setup」——官方明确承认预置 starter 是省时省 token 手段。https://v0.app/docs/design-systems-2
- 运行环境：预览跑 Vercel Sandbox（轻量 VM、秒级启动、预装 Node＋pnpm/npm/yarn/bun、框架感知 dev server），取代旧浏览器预览以支持 server 代码。https://v0.app/docs/sandbox
- 通用集成预置件：Marketplace 一键集成 Neon/Supabase/Upstash/Stripe/AI 平台，「Adding an integration provisions a new user account on that service and adds the necessary environment variables」——用户无需去服务商开户。https://v0.app/docs/databases ；https://v0.app/docs/agentic-features

**Bolt：WebContainers 零安装＋官方连接件（n8n 不是官方预置件）。**
- 运行环境官方叙事：「Bolt runs on WebContainers, so you don't have to install anything」；WebContainers 工程原文「boots in milliseconds」「100% of code execution occurs in the browser security sandbox」、builds 快 20%、包安装快 ≥5x。https://support.bolt.new/get-started/intro-bolt ；https://blog.stackblitz.com/posts/introducing-webcontainers/
- Bolt Cloud 预置基建：需要数据库时自动创建＋免费 bolt.host 子域托管。https://support.bolt.new/get-started/intro-bolt
- 官方连接件：Expo（移动 app 自动用）、Stripe（全自动生成服务端，见 3.x）、GitHub、Google SSO、Supabase、MCP；2026-03 Connectors 扩展 Notion/Figma/Netlify。**n8n 核实为非官方预置件**（support 站 sitemap 无 n8n 页，社区走 webhook 自接）。https://support.bolt.new/integrations/expo ；https://support.bolt.new/reference/supported-technologies
- 栈硬边界：JS-only（Node 后端、前端任意 JS 框架；PHP/Python 不兼容）。https://support.bolt.new/reference/supported-technologies

### 1.2 范围控制（首版做多宽 vs 多深）

四家一致**窄而深＋显式推迟清单**，且推迟清单是写进官方教程 prompt 模板的机制而非口头建议：

- **Lovable**：官方方法论长文示例 prompt「Build the one screen that fixes this…Use realistic sample data. No login or database yet」，并明说「Payments, reminders, and a calendar view can all come later, and later is cheaper than a first prompt that asks for everything at once」；「one change per prompt…Prompt by component (a booking form, a schedule card), not by page」；frontend-first 顺序——「Add the backend when data needs to survive」。https://docs.lovable.dev/tips-tricks/from-idea-to-app.md
- **Replit**：首 app 教程 prompt 模板原句「…submit an RSVP using realistic sample data. Success means…Do not add login, a database, payments, or external integrations yet」＋解释「Those boundaries keep the first build focused」；small slices 五原则「Avoid asking for the whole product at once」；并行纪律「if your task description contains "and", consider splitting it」。https://docs.replit.com/build/your-first-app.md ；https://docs.replit.com/learn/foundations/vibe-coding-101.md ；https://docs.replit.com/learn/build-in-parallel.md
- **v0**：以「全功能电商平台」为 Too Complex 反例，官方主动教用户首版收窄到「product catalogue page」＋localStorage 持久化；Prompt 队列最多排 10 条按序执行（把「一次说清所有需求」转化为顺序队列）；full-stack 推荐流程先 UI→数据层→核心功能。https://v0.app/docs/text-prompting ；https://v0.app/docs/full-stack-apps
- **Bolt**：官方纪律句「Do one thing at a time. Don't try to add multiple features in one go」；Plan Mode「starts by creating the base structure of your app. After that, it shares a plan…continue building step by step」——先骨架后计划再步步走；排障指引教「Break your request into smaller parts」。https://support.bolt.new/get-started/project-lifecycle ；https://support.bolt.new/prompting/plan-mode.md

### 1.3 会话结构（单/多智能体、并行、流式渐进呈现）

- **Lovable：单主智能体执行＋只读子代理并行调查。** 官方 Subagents 文档：主 agent 按需启动 temporary、read-only subagents（research/inspect/review，可多并行、fresh context），「cannot change your project. All file changes still come from the main Lovable agent」；两类——Generic（对比/清单/评审）与 Explore（用最强模型结构化取证）；FAQ 透露分层路由「Lovable routes routine research and lookups to lighter models where appropriate」。无用户级并行写任务（并行实验走 Drafts 项目副本）。渐进呈现＝常驻 live preview（临时云环境，「It updates as Lovable builds, so you can watch changes appear」，闲置暂停可恢复）＋Visible tasks 步骤级进度视图。是否逐组件热替换级刷新，官方未披露（无一手证据，推断）。https://docs.lovable.dev/features/subagents.md ；https://docs.lovable.dev/features/drafts.md ；https://docs.lovable.dev/features/projects/preview.md
- **Replit：唯一做多代理并行写的，且是旗舰能力（Agent 4，2026-03 起）。** 每请求拆离散任务、后台并行、显式批准才合入；官宣原句「Tackle auth, database, back-end functionality and front-end design all at once with parallel agents」；每任务在项目完整副本上跑、冲突由 specialized sub-agents 解决；plan-then-build 改 plan-while-building。工程博客（2026-09-29）披露 core loop 架构：主模型自主决定 subagent 的 tier（small/standard/large）与 effort，四原语＝domain-aware subagents（只读 explorer、browser tester、reviewer、design subagent）/ tier+effort / reusable subagents / dynamic effort tuning；harness 哲学「scaffold less / free the models」。质量环＝App Testing 默认开：Agent 自主在真实浏览器测试（可见其光标点击、输 mock 数据、自动修复、可回放）。https://replit.com/blog/introducing-agent-4-built-for-creativity ；https://replit.com/blog/whats-changed-agent3-to-agent4 ；https://replit.com/blog/free-the-models ；https://docs.replit.com/features/agent/app-testing.md
- **v0：单智能体＋后台任务编排（无并行多智能体证据）。** agent 能力＝web search（内联来源）＋browser use（打开自建 app 使用/评设计/调试、发回截图）＋terminal（Bash 三档权限）＋Marketplace/MCP，全跑在隔离 sandbox；每条消息改码即生成新版本（diff/restore 线性历史）；Design mode 视觉编辑选元素微调落回源码；2026-02-04 起升级 Projects：多 chat 共同贡献一个 app（共享部署/环境变量）＋VS Code 式编辑器＋Git 集成（自动分支/commit/PR，从不直推 main）。值得记录的张力：官方 FAQ 坦承「Why does v0 feel slower? Previews now run real builds」——用慢一点换真环境跑通。https://v0.app/docs/agentic-features ；https://v0.app/docs/versions ；https://v0.app/docs/projects ；https://v0.app/docs/faqs
- **Bolt：单代理三档（Standard/Max/Forge）＋preview 即建即点。** Standard「fast and token-efficient」适合任务明确的小中型应用；Max「thinks more about each step」适合大型应用/重构/开放式任务；模型选择对用户隐藏（见 1.4）；移动端每次 build 完成出 preview card、Expo 项目扫码真机快测。无并行多智能体证据。https://support.bolt.new/building/agents ；https://support.bolt.new/building/preview-controls ；https://support.bolt.new/integrations/expo

### 1.4 模型选择

- **Lovable**：FAQ 明文不公开型号、用户不可选——「The agent runs on current frontier models, and Lovable rolls out model upgrades automatically」；唯一分层证据是 Subagents FAQ（例行调查走轻模型、Explore 用最强模型）。质量打法是规则工程：TanStack Start「shipped after most LLM training cut-offs」，Lovable 注入与 TanStack 团队合维护的 curated rules——生成质量靠 prompt/规则而非自研模型。https://docs.lovable.dev/introduction/faq.md ；https://docs.lovable.dev/features/subagents.md ；https://lovable.dev/blog/building-apps-using-tanstack-start
- **Replit**：**无自研模型在售**——2023 年 replit-code-v1.5-3b 补全模型是历史遗产；Agent 3/4 官方材料从未宣称自研模型驱动（网络传闻「Agent 3＝GLM-4.6 微调」定向检索无可引用来源，不予采信）。现行模型目录全第三方（Lite 档 Kimi K2.7/GPT-5.6 Luna/Gemini 3.5 Flash/DeepSeek V4 Flash；Economy 档 Auto/Claude Sonnet 4.6/GPT-5.6/GLM 5.2；Power 档 Claude Fable 5/Opus/Kimi K3/GPT-5.6 Sol），默认「Agent automatically picks the best model for each request」＋per-model Effort 滑杆（高档只在真难处路由最贵模型）＋Compare models beta。生产 core loop 实锤（工程博客表 1）：Claude Fable 5/5.1、GPT-6 Astra。Turbo 口径「up to 2.5x speed and 6x cost」。https://docs.replit.com/features/agent/model-selector.md ；https://replit.com/blog/free-the-models
- **v0**：公开复合模型家族（Vercel 工程博客）＝自研 RAG（docs/UI 示例/项目源/Vercel 内部知识）＋可替换前沿基座（v0-1.0-md＝Sonnet 3.7、v0-1.5-md＝Sonnet 4）＋Quick Edit 模型（小改走速度优化路由、流式 inline edits）＋自研 AutoFix（vercel-autofixer-01，与 Fireworks AI 用 RFT 训练，效果与 gpt-4o-mini/gemini-2.5-flash 持平但**快 10-40 倍**）。动机自述：前沿模型对快速演进的框架知识过时。API 模型枚举已演进 v0-mini/pro/max/max-fast。https://vercel.com/blog/v0-composite-model-family ；https://v0.dev/docs/v0-model-api
- **Bolt**：型号保密、档位抽象——「You choose the agent that matches your work, and Bolt handles model selection behind the scenes」；官方博客泄露一处锚点：付费顶配「the top paid model in Bolt（Claude Opus 5）」。第三档 Forge 全开源型号公开：GLM 5.3 Flash（推荐默认）/GLM 5.3/Kimi K3/DeepSeek v4 Pro，自建 Bolt Build Index 上开源池 92.2 vs 顶配 101.0（91%）。https://support.bolt.new/building/agents ；https://bolt.new/blog/what-is-bolt-forge

**共性**：四家都不把模型选择放进首版体验（Lovable/Bolt 藏型号、v0 默认粘滞、Replit Auto 兜底）；「快」做进管线的位置是小改快路由与轻调查轻模型，而不是首版整体降档。两家官方自划「快/便宜 vs 好/贵」明线（v0 FAQ 认慢、Bolt Forge 承认 91% 分建议严肃构建留 Standard/Max）。

---

## 二、Google Stitch 与 AI Studio Build

### 2.1 Stitch（stitch.withgoogle.com，Google Labs）

**定位与生成流。** 官方 meta「Stitch generates UIs for mobile and web applications, making design ideation fast and easy」（Beta）；发布公告（2025-05-20）「turn simple prompt and image inputs into complex UI designs and frontend code in minutes」；2026-03 升格「AI-native software design canvas…natural language into high-fidelity UI designs」。https://stitch.withgoogle.com ；https://developers.googleblog.com/stitch-a-new-way-to-design-uis/ ；https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design/

**「配色先行」取证——地图预设的修正。** 没有官方 FAQ/文档声明生成管线内部「先产出 color scheme 再排版」（官方 FAQ 页为 SPA 抓不到正文，Wayback 也只有壳——如实报告）。官方证据支持的形态是：**主题/配色是全项目一等控制＋首屏定调逐屏继承**：
1. 发布公告明示 prompt 里就该含配色（「including details like color palettes or desired user experience」），且初版就有 theme selectors。https://developers.googleblog.com/stitch-a-new-way-to-design-uis/
2. 官方论坛置顶 Prompt Guide（2025-05-19）：工作流＝起项目（「start high-level and then drill down on details screen by screen」）→逐屏迭代（一次一两个改动）→Controlling App Theme（改主色/按情绪改整个 palette/改字体/圆角；「influences colors, fonts, imagery」——配色、字体、图片风格同层控制）→修改图片（「Update theme to light orange. Ensure all images…match this new color scheme」——图片与主题联动）。https://discuss.ai.google.dev/t/stitch-prompt-guide/83844
3. 2026 年主题升格为设计系统：从任意 URL 抽取 design system；DESIGN.md（YAML 设计令牌＋rationale）跨项目导入导出；官方 demo「Apply this design system to the whole site」一句全站换装。https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design/
4. 行为旁证（二手）：animaapp 逐帧引 Google I/O 2026 官方 demo——官方演示 prompt 即以 palette 开头（「primary background be a warm off-white, secondary…sky blue」）。https://animaapp.com/blog/ai-design-en/google-stitch-review

**逐页排版、信息丰富含图片。** 高保真非线框（「Instead of starting with a wireframe, you can start by explaining the business objective」）；Prompt Guide 有专门「Describe Desired Imagery」「How to modify images」章节（例：专辑图为海洋微距、页面背景意象呼应）；导出可「download HTML and images」。图片生成模型官方未点名（Beebom 等称 Gemini 2.5 Pro＋Imagen，二手；Imagen 归因仅二手）。反面证据如实报告（二手）：uithings 认为实际更接近 mid-fidelity、Figma 导入常为扁平图层。https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design/ ；https://discuss.ai.google.dev/t/stitch-prompt-guide/83844

**模式与控制。** 逐屏迭代＋「Stitch can automatically generate logical next screens based on the click」（点击处自动生成逻辑下一屏）；2025-12 Prototypes 把多屏缝合为可播放交互原型；I/O 2026（2026-05-19）Stitch Agent「streams its work straight to the canvas, and you can see it working in real time」（过程流式直绘画布、成品前实时纠偏）＋Agent manager 并行管理多条探索线＋语音改画布。速度档（Standard/Experimental＝Flash/Pro、2-5s/5-15s、350/50 次）为二手口径（apiyi），官方唯一量化口径是「in minutes」。模型线：Gemini 2.5 Pro（发布）→Gemini 3（2025-12）。https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-gemini-3 ；https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-updates

**产出与边界（示意级定位的一手证据链）。** 导出形态：初代 Paste to Figma＋「clean, functional front-end code」；代码具体为 HTML＋Tailwind 类名、静态数据单组件常见（二手一致）；2026-03 起扩为 AI Studio/Antigravity（经 MCP）/Figma，Stitch MCP server＋SDK 可让下游 agent 直接消费 DESIGN.md；I/O 2026 加「经 AI Studio 一键出分享链接」与「Netlify 直接发布」。官方边界原句：**「when it's time to move to production, you can export your screens into Google Antigravity to easily plug in your backend logic」**——生产＝去别处接后端，Stitch 止步设计/前端。https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design/ ；https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-updates

### 2.2 AI Studio 的 Build（aistudio.google.com「Build」标签页）

**定位与机制。** 现行官方文档：「use Google AI Studio to quickly build (or "vibe code") and deploy apps that test out the latest capabilities of Gemini…through natural language prompting」；入口四式（prompt/I'm Feeling Lucky/App Gallery remix/GitHub 导入）；「Once you run the prompt, you'll see the necessary code and files get generated, with a live preview of your app appearing on the right-hand side」。https://aistudio.google.com/docs/aistudio-build-mode

**「纯 Demo＋localStorage」的实证与演进（如实报告）。** 该形态是 **2025 初代**且被泄露系统提示词实证（testingcatalog，2025-04-21，二手泄露）：
- 产出固定 TypeScript/HTML/CSS(Tailwind) 三段插入；「no JS libraries that cannot be embedded directly in the HTML…only pull in external libraries that can be imported via URL embeddings」（CDN importmap、无构建步骤）；「Git is NOT available」——单文件、零 build、iframe 即跑。
- 持久化优先级写进生成规范：「When choosing databases or npm packages, strictly prefer options that DO NOT rely on native binaries…**Prioritize libsql, sqlite (via WASM if possible), IndexedDB, localStorage**, or other pure JavaScript/WASM solutions」。
- 美学硬约束写进系统提示词：「generate **simple but aesthetic** web apps」＋「**Exclusively use Tailwind CSS** for all styling」。
https://www.testingcatalog.com/google-ai-studio-experiments-with-live-ui-preview-for-gemini-apps/
- 现行（2026）已是全栈：Web app＝React 前端＋Node.js 服务端，由 Antigravity Agent harness 管理（「maintaining context of your entire project, managing multiple files…Verified execution」）；官方文档锚点「For apps built **before May 14, 2026**, the agent will automatically upgrade your Gemini API integration to the recommended server-side approach」——官方自认 2026-05-14 前后从客户端切服务端。即便如此 FAQ 仍自述「We are working on adding direct support for storage in the future」——内置直接存储至今未做。Android 线＝Kotlin＋Jetpack Compose。https://aistudio.google.com/docs/aistudio-build-mode ；https://aistudio.google.com/docs/aistudio-fullstack
- 专项核实：**@google/genai SDK 无 simulate/模拟 API 能力**（任务假设不成立，如实报告）——搜索命中的「simulated」仅是 function calling 手工回灌的 simulated function responses。localStorage 用法另有多个 Build 生成应用实录（dev.to RPG Portrait Generator 等，二手）。

**「快而美」机制。** 快：无构建（初代）＋预览即跑＋chat 迭代 diff/checkpoint＋2026 dev container 即开即用（agent 自动装包）＋流式代码呈现。美：美学是写进提示词的硬约束（载体 Tailwind）＋现代栈默认。API 衔接：初代 placeholder key 平台代理计费（「all API usage by its users is attributed to their Google AI Studio free of charge usage」）；现行 GEMINI_API_KEY 服务端 secret 自动注入（不进客户端代码）＋一键 deploy Cloud Run（免费档 2 应用＋ai.studio 子域）。https://developers.googleblog.com/google-ai-studio-native-code-generation-agentic-tools-upgrade/ ；https://aistudio.google.com/docs/aistudio-deploying

**边界与分工（官方口径）。** 2025 公告自称 experimental、长项「one-shot generation…primarily optimized to work with Gemini and Imagen models」；Google Cloud 官方博客分工定调：「Google AI Studio…**vibe code lightweight web apps**」vs「**Firebase Studio** is the right choice when you want to build professional-grade software」。与 Stitch 的接力棒官方明写：Stitch 导出「to developer tools like AI Studio and Antigravity」、I/O 2026「instantly generate a shareable link via Google AI Studio」。https://cloud.google.com/blog/products/ai-machine-learning/choose-the-right-google-ai-developer-tool-for-your-workflow ；https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-updates

**两产品共性（快而美机制拆解）**：①美学前置为系统约束而非事后打磨（Stitch 主题/DESIGN.md 一等控制；AI Studio 提示词硬约束）；②生成单元小、反馈环短（一屏/一个能跑的 app，分钟级闭环）；③刻意停在示意级（不内嵌数据库、不承诺生产级——速度与美观的共同前提）；④导出即接管（Stitch→AI Studio/Antigravity→Cloud Run/Netlify，资产 DESIGN.md/MCP 可被下游 agent 消费）。

---

## 三、「功能展现丰富但示意级」的取舍证据

### 3.1 数据层光谱（mock 到什么程度）

| 产品 | 首版数据层 | 证据 |
|---|---|---|
| Lovable | 浏览器内 realistic sample data；「Sample data lives only in the browser. When information needs to persist, your app needs a database」 | https://docs.lovable.dev/tips-tricks/from-idea-to-app.md |
| Replit | session 级 sample data（首版＋第一轮改进都明确「for the current session…do not add login or a database」）；Agent 自测时「entering mock data when necessary」 | https://docs.replit.com/build/your-first-app.md ；https://docs.replit.com/features/agent/app-testing.md |
| v0 | 默认可纯前端；接库的目的官方原话是「to **properly mock out more complex flows**」——真实服务（测试态）替代深层 mock | https://v0.app/docs/databases |
| Bolt | 最激进：需要即静默开**真托管库**（「Bolt creates a database automatically if your project needs one」）；可显式拒绝「Don't use a database, I want to use local storage」 | https://support.bolt.new/bolt-cloud/database |
| AI Studio Build | 初代系统提示词把 localStorage/IndexedDB 排进持久化优先级；现行官方自述内置存储「working on adding」 | 见 2.2 两 URL |
| Stitch | 静态数据脚手架（「one large component with static data is a common result」，二手一致）；不含状态层 | https://animaapp.com/blog/ai-design-en/google-stitch-review |

**「丰富感」的正确来源**：两家官方都反对占位文案——Lovable「Use real content from day one. Placeholder text hides design problems」；Replit 教程用「realistic sample data」；Stitch 评测过关项是「realistic content instead of placeholder text」（二手）。即**示意级≠假大空，而是真实感内容＋浅持久化**。

### 3.2 假逻辑停手线（哪里停手）

- **支付**：四家全部停在 test mode/沙箱——Lovable 内置 Paddle/Stripe，test 环境映射 preview、自动加 test mode banner、go-live 是带清单的显式阶段（「A paywall on an unproven flow just hides whether the flow works」）；v0 publish 审查「unclaimed Stripe test sandbox or Shopify development store」；Bolt Stripe 流程以 test mode＋sk_test 为默认路径（全自动生成 Supabase edge functions 处理 checkout/webhook/subscription）。https://docs.lovable.dev/features/payments.md ；https://v0.app/docs/quickstart ；https://support.bolt.new/integrations/stripe
- **鉴权**：Bolt 显式停手线「Features like user authentication **may not be added automatically**, but you can prompt Bolt」；Lovable/Replit/v0 的鉴权是预置件（一条 prompt 即装），不存在「假登录」。https://support.bolt.new/bolt-cloud/database
- **外部 API**：v0 三步走——描述集成→v0 生成调用与错误处理→**密钥生成后补**（代码先行、密钥后补）；Replit 第三方服务走 OAuth 连接流引导而非贴 key。https://v0.app/docs/quickstart ；https://replit.com/blog/introducing-agent-3-our-most-autonomous-agent-yet
- **示意级上限**：Stitch 止于设计稿＋静态前端脚手架＋可点击原型（生产去 Antigravity）；AI Studio Build 初代止于单文件 localStorage 应用（官方定位 lightweight）。

---

## 四、对本项目的可借鉴清单

> 本平台现状锚点（CONTEXT.md / ADR-0009/0013/0035/0042/0046）：基座＝TS/Next.js/React/pnpm/shadcn/PostgreSQL/Redis 固定栈、镜像内置依赖（golden 硬链种子 ADR-0046）；厚模板六层（#407：镜像运行时＋工程模板＋基座健壮件＋Vitest 测试跑道＋栈规则集＋设计 token 注入）；基座健壮件首批四件（数据访问层/fetch 封装/三态 UX 件/会话存储件——登录页面/流程/角色模型永留执行体）；生成＝阶段 0 先起服空白页＋纵向切片逐段加厚（ADR-0009）＋preview-ready 事件推 URL；并行波 SliceParallelExecutor 已存在（ADR-0042）；模型＝DeepSeek flash 全量＋失败升档 pro 兜底（#454/#471/#472）；设计 token 三件套（平台确定性写入基座/协议短条目/收口合规扫描，带规范项目）；图片工具仅发设计线、系统构建执行体不出图（ADR-0026）；演示数据预置入真库。

### A 轴：基座预置扩张（登录之上还该预置什么）

1. **支付示意件（性价比最高、与地图裁决零冲突）。** 四家全部停在 test mode/沙箱，本平台已裁「真支付对接不在本期」——可直接把「示意级支付」收口进基座：预置假 checkout 页/支付结果流组件（三态 UX 件同款机制/呈现分离），执行体只写业务侧「下单」语义。对齐 Lovable「内置 Stripe test mode＋test banner」的形态而无需接真 Stripe。
2. **默认主题套（无规范项目的美学兜底）。** 本平台设计 token 三件套只覆盖「带规范项目」；Stitch 证据（主题是一等控制、首屏定调逐屏继承、DESIGN.md 跨项目复用）支持给基座预置**多套精选默认主题**（配色/字体/圆角成套语义 token），首屏由五问采集的偏好选套定调、逐片继承——补上「无设计线项目」的美学下限。AI Studio 侧的对应证据：美学约束写进生成规范（「simple but aesthetic」＋Tailwind 专属），本平台已有同构协议位（禁裸色禁任意值条目），补「默认排版密度/信息丰富度」条目防空壳页。
3. **图片能力下放系统轨（把 ADR-0026 备案触发器提前激活）。** ADR-0026 裁定「系统构建执行体不发图片工具、系统内资产来自设计线搬运、备案触发器＝无设计线的系统单真要出图」。Stitch 的核心证据是「信息丰富含图片」本身就是「美」的第一构件（Prompt Guide 专门 imagery 章节、图片与主题联动）——纯文字排版的信息丰富度有硬上限。路径有二（不新增第三条）：走既有「设计线出图→资产进构建上下文」通道（搬运不是生成），或给构建执行体发受限图片工具（按张计量、四供应商适配器均已备）。这是「功能展现丰富」最便宜的一块。
4. **通用 UI 模式件进栈规则集/基座件。** v0 的质量下限来源是「模型受训于 shadcn 默认实现」——本平台无法改模型先验，但等价物是**栈规则集扩容**（#407 六层之一，平台维护）：把高频模式的惯用法示例（列表＋筛选＋分页、表单＋校验＋提交态、图表卡、设置页壳）写成基座示例件＋规则条目「优先用基座件」，命中「每应用都要写、与具体功能无关」的健壮件判据的可升格健壮件（lint 预置档同款）。
5. **不建议抄的**：v0 Marketplace「一键开户」式真服务预置（Neon/Supabase/Stripe 开户）——本平台基座已自带真 PostgreSQL/Redis（比 Lovable 初代还全），缺的不是服务是「服务之上的示意件」；Replit 的 OAuth 连接流属生产集成面，demo 阶段出界。

### B 轴：复杂度治理（信息量定深度）

1. **显式推迟清单进切片计划协议（直接对齐「杜绝臆想加戏」）。** 四家全部把「Do not add login, a database, payments, or external integrations yet」写进首版 prompt 模板——本平台切片计划由主智能体产出（ADR-0009），可在其产出协议中加**首版推迟清单**显式段：支付/通知/外部集成/多角色权限类默认入推迟清单；信息量不足的功能（五问没问出来的）进推迟清单而不是执行体拍脑袋做。这是「实现深度跟采集到的信息量走」的执行面落点——信息量少＝清单长＋片少。
2. **首版切片数量跟信息量走（宽 vs 深的一锤定音）。** 竞品一致「窄而深」（one user, one action, one outcome）；v0「Too Complex 全功能电商」反例可进切片协议负面清单。本平台切片数量已是 PRD 结构的涌现结果（非平台旋钮），可补的是协议层引导：五问信息量→建议片数区间（如 3-5 片收口成版），信息量少时抑制铺页面的冲动——每片都是端到端可操作的完整点（ADR-0009 已保证深），治理的是宽。
3. **演示数据从「执行体建」降为「平台 seed 预置」。** 本平台现状「演示数据预置入真库」——真库是对的（三硬底线「刷新不丢」要求持久化；Lovable browser 级过不了这条），但竞品快的关键是**数据面不在执行体身上花模型轮次**：Lovable 数据在浏览器（零成本）、Bolt 数据库是托管件（agent 只建 schema）。本平台可挪的是：预置 seed 数据集（平台确定性灌入真库——与基座健壮件「阶段 0 平台确定性写入」机制同构），执行体只写功能面消费数据、少花轮次造数据；「realistic 内容反对 placeholder」两家官方口径照抄（丰富感来源）。
4. **会话结构：并行是 30min 理想的候选杠杆，但按单机容量裁。** Replit Agent 4 证明多代理并行写可行（副本隔离＋子代理解冲突＋依赖自动排序「Tackle auth, database, back-end and front-end all at once」）；本平台 SliceParallelExecutor 波内 fork 片＋生成车道宽度（#478 单机最大吞吐口径）已备好结构缝。可借鉴的具体件：**独立页面切片先行并行、依赖片（共享数据模型的）保持串行**（Replit「contains "and", consider splitting」＋依赖排序证据）；写路径仍收敛单合并点（四家共性——Lovable 只读子代理、v0 单智能体都证明「调查并行、写入收敛」是安全默认）。60min 红线内优先做 1-3，并行属 30min 理想的二期项（如实评估：多 run 副本合并的冲突面是新复杂度）。
5. **模型面无需大改（如实结论）。** 本平台「flash 全量＋失败升档＋pro 兜底」与 Lovable（分层路由暗示）/v0（Quick Edit 快路由＋AutoFix 快 10-40x）/Replit（Auto＋Effort 高档只路由真难处）同构，窄修一轮（ADR-0041）对应 v0 AutoFix 的流后 final pass 位置。竞品证据一致指向：**速度主战场在预置件与环境，不在模型档位**——四家的分钟级首版靠的是「一条 prompt 即得」的现成件与秒级环境，模型档位再降对 1.5-3h→60min 无决定性贡献。
6. **渐进呈现已同构、可补叙事面（长期项）。** 本平台「阶段 0 先起服空白页＋逐片加厚＋preview-ready 事件推 URL」正是「watch it take shape」的平台确定性版本（比纯 prompt 的竞品更硬）。可补两件：Stitch 2026 式「过程流式直绘、成品前实时纠偏」（片内中间态可见——ADR-0009 已定刷新单元＝切片收口，片内流式属后续演进，如实标注长期）；Visible tasks 式步骤级进度叙事（Lovable glossary 有此件，本平台切片描述已有用户语言，呈现面是纯前端工作）。

### 与地图预设的两处修正（如实报告）

- 「Stitch 配色先行」修正为「主题/配色一等控制＋首屏定调逐屏继承」——无官方「先配色再排版」流水线声明，但官方 demo 行为与 Prompt Guide 支持等价效果（配色定了全站继承、图片联动）。
- 「AI Studio 纯 Demo＋localStorage」是其 2025 初代形态（系统提示词实证）；2026-05-14 起官方主推全栈＋服务端 secret＋Cloud Run——但其「快而美」机制（无构建、预览即跑、美学硬约束、存储靠 localStorage/外接）对 demo 阶段仍是正解参照，且官方至今把它定位在 lightweight 档（professional-grade 归 Firebase Studio）。

---

## 附：一手来源清单（全部访问于 2026-11-15）

- Lovable: docs.lovable.dev（features/upgrade-to-tanstack-start.md、features/cloud.md、features/subagents.md、features/drafts.md、features/projects/preview.md、features/payments.md、features/knowledge.md、features/design-systems.md、tips-tricks/from-idea-to-app.md、introduction/faq.md、glossary.md、changelog）＋ lovable.dev/blog/building-apps-using-tanstack-start
- Replit: docs.replit.com（features/agent/overview.md、features/agent/model-selector.md、features/agent/app-testing.md、features/project-setup/developer-frameworks.md、features/data-and-storage/sql-database.md、features/auth-and-identity/authentication.md、build/your-first-app.md、learn/foundations/vibe-coding-101.md、learn/build-in-parallel.md）＋ replit.com/blog（introducing-agent-4-built-for-creativity、whats-changed-agent3-to-agent4、free-the-models、introducing-agent-3-our-most-autonomous-agent-yet）
- v0: v0.app/docs（faqs、full-stack-apps、design-systems、design-systems-2、templates、sandbox、text-prompting、instructions、versions、agentic-features、projects、databases、quickstart、ai-models、model-picker）＋ v0.dev/docs/v0-model-api ＋ vercel.com/blog/v0-composite-model-family
- Bolt: support.bolt.new（get-started/intro-bolt、get-started/project-lifecycle、building/agents、building/preview-controls、prompting/plan-mode.md、reference/supported-technologies、integrations/expo、integrations/stripe、integrations/supabase、bolt-cloud/database）＋ bolt.new/blog/what-is-bolt-forge ＋ blog.stackblitz.com/posts/introducing-webcontainers/
- Stitch: stitch.withgoogle.com（meta）＋ developers.googleblog.com/stitch-a-new-way-to-design-uis/ ＋ blog.google/innovation-and-ai/models-and-research/google-labs/（stitch-ai-ui-design/、stitch-gemini-3、stitch-updates）＋ discuss.ai.google.dev/t/stitch-prompt-guide/83844 ＋ discuss.ai.google.dev/t/stitch-is-no-longer-providing-visual-designs/114425
- AI Studio Build: aistudio.google.com/docs/aistudio-build-mode ＋ aistudio.google.com/docs/aistudio-fullstack ＋ aistudio.google.com/docs/aistudio-deploying ＋ developers.googleblog.com/google-ai-studio-native-code-generation-agentic-tools-upgrade/ ＋ cloud.google.com/blog/products/ai-machine-learning/choose-the-right-google-ai-developer-tool-for-your-workflow
- 二手（仅旁证、正文已标注）: testingcatalog.com（AI Studio 初代系统提示词泄露）、animaapp.com（Stitch I/O 2026 逐帧）、apiyi.com（Stitch 模式/额度）、uithings.com、Beebom、vibe-eval.com、UsagePricing、Reddit r/n8n
