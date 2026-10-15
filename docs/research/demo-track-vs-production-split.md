# 调研报告：轻量 Demo 轨与现有轨提速（2026-10-15）

> 票 [#313](https://github.com/ZhangColin/aiplatform/issues/313)（wayfinder 地图 #307 research 子票），供「Demo/生产拆分裁决」票作事实素材。
> 方法：三路并行取证（执行链路代码证据 / 容器与运行痕迹取证 / 业界一手证据）＋落稿人对承重引文逐条复核＋本机 dev 库 SQL 实测。数字给量级不给假精确；DB 实测截至 2026-10-15（8 项目、32 个成功切片 run）。

## 结论速览

1. **现有轨慢的主因不是「基座重/容器慢」，是三块：失败首试作废（~1/3）、LLM 有效推理（~1/3）、未计量间隙＋自测（~1/3）**。dev 库 32 个成功切片合计 648.7 分钟的构成分解：失败首试作废 ≈33%、LLM 净推理 ≈31%、未计量间隙 ≈22%、self-test 子智能体 ≈12%、容器命令（install/dev/test）≈2.5%、收口尾序（探活+lint+git 成版）≈0%。容器与 npm 环节几乎无感——基座依赖镜像预装（ADR-0013）已把 install 拿掉，阶段 0 起服实测仅 38–112s，唤醒实测 ~2.1s。
2. **「十几个步骤往一天去」的构成已对上账**：每切片净耗时中位 15–25 分钟（分布 0.6–46.7min）× 严格串行逐片（片间交接摘要耦合）＋ 34% 的切片经历失败首试（每次平均作废 ~19 分钟，其中多数是 30 分钟墙钟超时、llmMs=0 的「零产出超时」）＋ 隔夜/中断（净生成只占墙钟 5–10%）。
3. **单轨提速的可望空间按性价比排序**：修失败首试（量级 -30%±，可靠性工程非架构工程）＞砍/降 self-test（-10%±）＞执行体换快模型（LLM 段 2–3x，DeepSeek flash ≈173 t/s 对 pro 档；质量风险需实测）＞跳数据库轻协议（LLM 生成面估减 30–50%——DB schema/造数/落库链路是每片代码量的大头）＞片间并行（受片间交接耦合与同工作区写冲突限制，Replit 用隔离副本+fork 合并才敢做）。全拿满理论上限 ≈3x（小时级→20–40 分钟级），到不了 Replit Agent 4 的分钟级首版——那是并行子代理（官方宣称较 Agent 3 提速至 3x）＋模型路由＋降形态（frontend-only mode）的组合拳。
4. **业界快出原型的秒级/分钟级全部落在「降形态」产品**：模板+LLM 填充（Durable 官方「30 秒」）、单文件 HTML 沙箱（Claude Artifacts）、纯前端+sample data（Lovable 官方教程原话「Sample data lives only in the browser」、Replit Agent 3 frontend-only mode「skip database integration first」）。头部三家（v0/bolt/Lovable）主线全是「真实全栈+真实构建」，官方速度口径统一「首版 minutes」；v0 官方自认「预览跑真实构建所以显得慢」。「无库+假数据+可交互+好看」的 Demo 档有充分业界先例，且与全栈档并存、以「何时接库」为切换点。
5. **三方案对比（详见 §5）**：A 单轨提速（工程量小、天花板 ~2–3x、不动概念面）；B 双轨拆分（Demo 分钟级可期、工程量大、两套执行体与呈现语义）；C 同执行体轻协议（Demo 档 15–30 分钟级可期、工程量中、复用编排与呈现、跳 DB 判据＋砍自测全在协议层）。**速度天花板：B（分钟级）＞C（~2–4x）＞A（~2–3x）；工程量与风险同序**。对 Replit 40 分钟的差距：A/C 靠拢到同量级，B 的 Demo 档反超（但产物形态降级为纯前端）。

---

## 1. 现有生成轨耗时画像（Q1）

### 1.1 链路机制一张图（代码证据）

生成 = 主智能体意见轮收口后自动派发 → 异步轨道按「阶段 0 起服 + 纵向切片」**严格串行**逐段跑 → 每段 = 尝试环（**默认 3 次尝试 × 单次 30 分钟墙钟**）→ 每次尝试 = 执行体单 turn ReAct 循环（上限 300 次模型迭代，实测中等 PRD ≈160 次）＋片内 self-test 子智能体（框架缺省上限 10 步）＋收口尾序（8081 探活 + 设计 lint 扫描 + git 全量成版）。

承重引文（已逐条复核）：

- 尝试环配置：`aiplatform-server/src/main/java/com/aieducenter/aiplatform/business/project/application/GenerationProperties.java:19`（`maxAttempts = 3`）、`:22`（`timeout = Duration.ofMinutes(30)`）；`application.yml:94-96`。
- 串行主循环：`GenerationAppService.java:783`（`for (int index = startOrd; ...)` 逐片）＋ `:796`（`coderRunAttempts.run(...)` 同步阻塞）；本片失败即 `failSegment`+发 run-failed+return，不自动跳片（`:808-814`）。片间交接：前片收口终文写 `.platform/slice-handoff-{n}.md` 注入下一片 prompt（`:816-818`、`placeSliceHandoff :830-847`）。
- 单次尝试 = 一次 `converse` 阻塞调用（`CoderRunAttempts.java:308`，内部 `blockLast(timeout)`），turn 内模型迭代上限 300：`application.yml:69`；实测注释「一次中等 PRD 的系统生成单 turn 需约 **160 次迭代**」（#25 活体对照）：`base/agentscope/AgentscopeProperties.java:33-38`。
- 执行体模型 `deepseek-v4-pro`：`business/project/domain/model/AgentProfile.java:142`；主智能体 flash（`:38`）；模型可运营覆盖不发版（`AgentConfigAppService.effectiveOf`，`application/AgentConfigAppService.java:55-63`；CONTEXT.md「智能体运营配置」词条）。
- 收口判据「真实应用带数据库、真实落库」：`AgentProfile.java:158-160`（curl 确认 8081 有响应才算完成）＋ `:184-187`（收口前必委派 self-test 子智能体）。
- self-test 子智能体：`infrastructure/agentscope/ProfileSubagentSupplier.java:54-56`（工具白名单含 `execute` 跑真实测试命令）、`:76-82`（ISOLATED 工作区、未设 steps → 框架缺省 10 步：agentscope-java `SubagentDeclaration.java:393`）。
- 收口尾序（每次成功尝试固定串行动作）：`GenerationAppService.closeGenerationStage :858-870` → 8081 探活（探针 `curl -s --max-time 2`，`:389`）→ 设计 lint（带规范项目 oxlint，`DesignSpecAppService.java:275`）→ git 全量成版（`git add -A && git commit`，`WorkspaceVersions.java:89-93`）→ 收尾卡落库+run-finish。lint 违规自动修一轮：`CoderRunAttempts.java:351-359`。
- lint 修轮后一段最多 4 个完整 turn；无独立 review 子智能体（只有 self-test 一个声明，`ProfileSubagentSupplier.java:91-97`）。
- 耗时埋点已随每片收尾卡落库：`closing.durationBreakdown { llmMs, toolsMs(按工具分桶), selfTestMs, closingMs, attempts[] }`（`CoderRunAttempts.java:588-602`；规格 `docs/spec/SSE事件清单.md:155-179`）——本报告 §1.2 的数字即从此拉取。

### 1.2 真实耗时实测（本机 dev 库 SQL，最强证据）

表：`prj_generation_segments`（计划）＋`prj_conversation_entries.closing`（收尾卡 JSONB）。

**(a) 单切片净耗时**（`closing.durationMs`，32 个「完成切片」run）：中位 ~15–25 分钟，分布 0.6–46.7 分钟。直方图：0–10min×8、10–20min×11、20–30min×5、30–40min×3、40–47min×5。

**(b) 整项目净生成**（切片 run 合计）：最早项目（轻简待办，7 片）44 分钟 → 最新项目（9 片）258 分钟。墙钟跨度 7.2–58 小时，**净/墙钟比仅 5–10%**（隔夜/中断为主）——「往一天去」大头是跨度不是净时长，但用户体感的是「每片几十分钟」的直播节奏。

**(c) 648.7 分钟的构成分解**（32 片合计）：

| 构成 | 分钟 | 占比 | 证据 |
|---|---|---|---|
| LLM 净推理（末次成功尝试 llmMs） | 199.5 | ~31% | SQL 汇总 |
| self-test 子智能体 | 76.7 | ~12% | 同上 |
| 失败首试作废（attempt 1 durationMs） | ~211 | ~33% | 11/32 片有重试，首试 322–1800s、**全部 llmMs=0**（模型流从未完成计量，多数为 30 分钟墙钟超时整段作废） |
| 未计量间隙（dur−llm−selfTest−tools） | ~145 | ~22% | 单成功尝试内典型 ≈3–4 分钟：agent_spawn 委派、流式等待、框架开销（未逐项计量，如实标宽口径） |
| 容器命令（install/dev/test 合计） | ~16 | ~2.5% | toolsMs 分桶：单片典型 ≈30s（read_file/edit_file/update_plan/command 等） |
| 收口尾序（探活+lint+git） | 0.2 | ~0% | closingMs 合计仅 13s |

**(d) 重试形态**：32 片中 21 片一次过、11 片两次（34%）。失败首试平均作废 ~19 分钟；第二试 435–1307s 全部成功。零产出超时（30 分钟、llmMs=0）是最刺眼的浪费形态——首试不是「做得慢」而是「没做完就被墙钟掐死」，重试（原地修携错误现场）反而 12 分钟内过。

**(e) 环节逐项排除法**（票面问的每一项）：

- **执行体模型调用节奏**：确认是大头（LLM 31%＋间隙 22% 的主体）。deepseek-v4-pro × ~160 迭代/turn，每次迭代 = 一次模型流 + 工具往返；框架 Toolkit 默认已启用并行工具执行（agentscope-java `HarnessAgent.java:1498-1500`「parallel tool execution enabled」）——但工具只占 2.5%，并行工具调用的提速空间在我们这近乎零（Replit 该杠杆 +15% 工具/loop 是在工具重的形态下）。
- **切片/步骤串行**：确认结构性约束。逐片串行＋片间交接（前片摘要注入下片），片内步骤由执行体 update_plan 自主拆。并行化需解决交接耦合＋同工作区写冲突。
- **基座工程重量（Next.js 全栈+PG）**：**运行时不慢**（install 预装、探活秒级、命令 2.5%），但**间接吃 LLM 时间**：每片产出 6–15 个文件（git 实证：`page.tsx` + server actions + `scripts/setup.mjs` 造数 + e2e 脚本 + pnpm-lock）——DB schema、造数、落库链路是执行体必须生成的代码量，「带数据库真实落库」的收口判据（AgentProfile.java:158-160）决定了生成面。这是 Demo 轨跳 DB 能省的真正账。
- **容器起停**：无感。镜像 4.67GB（基座 pnpm install 层 964MB，`dev-image.Dockerfile:66-67`）、生成期零 install；唤醒编排实测 ~2.1s（`docs/smoke/184-预览停机体验联调手册.md:26`）；应用拉起 `next dev` 常驻（baseline `package.json` 的 start 即 `next dev -p 8081`），在途 run 恒活跃不休眠（`WorkspaceHibernationScheduler.java:93`）。
- **自检/自测子智能体**：12%，每片一场（≤10 步跑真实探活/测试命令），是明确的固定开销。
- **主智能体/设计过程阻塞**：意见轮与 PRD 写作异步不阻塞生成轨（`MainAgentAppService.dispatchOnTurnClose :634-729`，生成在途时静默跳过）；但**设计类终点真正阻塞构建**（全部定稿才允许，`GenerationAppService.java:511-521`），设计轨本身逐件串行、每件同款尝试环。

---

## 2. 提速手段清单与可望空间（Q2）

按性价比排序（收益量级以 §1.2 的 648.7 分钟构成为基数）：

| # | 手段 | 预期收益量级 | 代价/风险 | 证据锚点 |
|---|---|---|---|---|
| 1 | **修失败首试浪费**（超时 30m 的零产出 attempt：调 timeout/改超时语义/判据宽容/首试快速失败） | **-30%± 全轨**（11/32 片×19min） | 低工程量（配置+重试语义）；风险=超时收紧可能误杀慢片 | GenerationProperties:19/22；§1.2(d) llmMs=0 形态 |
| 2 | **砍/降 self-test**（Demo 场景跳过、或降为探活 curl 单步） | **-10%± 全轨** | 低工程量（协议已委派制，执行体协议第 9 条改口径即可）；代价=信任帧消失（自测 ✅/❌ 清单是收尾卡组成部分） | AgentProfile.java:184-187；ProfileSubagentSupplier |
| 3 | **执行体换快模型**（pro→flash 档或业界更快编码模型） | LLM 段 **2–3x**（flash ≈173 t/s 对 pro 档，Artificial Analysis 二手实测；全轨口径约 -15~20%） | 配置级可换不发版（agent_operational_config）；风险=复杂全栈片质量回归，需按片实测 | AgentProfile.java:142；AgentConfigAppService.effectiveOf；AA 对比页 |
| 4 | **跳数据库轻协议**（假数据/localStorage、收口判据换「8081 可交互」） | LLM 生成面估 **-30~50%**（每片少写 schema/造数/落库链路；以每片文件构成 git 实证推量级） | 中工程量（执行体协议变体+收口判据参数化）；风险=与「系统=真实应用」领域口径冲突（CONTEXT.md「系统」词条）——正是 Demo 轨裁决要拍的板 | AgentProfile.java:158-160；卷内 git 每片 6–15 文件 |
| 5 | **片间并行**（多片同时跑） | 理论高（片数/并发数），实际受限：片间交接摘要耦合（后片依赖前片产物语义）＋同工作区 git/文件写冲突 | 高工程量（Replit 的做法=隔离副本+fork 合并+冲突解 sub-agent，Agent 4 官方博客）；风险=合并冲突与收口判据复杂化 | GenerationAppService.java:783/816；Replit Agent 4 博客 |
| 6 | **未计量间隙排查**（22% 的 dur−llm−selfTest−tools 差值：agent_spawn 委派链、流式等待） | 未知，上限 -20%± | 低工程量先观测（埋点已按 attempts 分桶，补委派段计量即可看清） | §1.2(c)；CoderRunAttempts.java:588-602 |
| 7 | 模板复用/更薄基座 | 运行时近零收益（install 本就不跑）；LLM 面收益归入 #4 | — | §1.2(c) 工具仅 2.5% |

**叠加口径**：#1+#2+#3 拿满 ≈ 全轨 -50~60%（小时级→30–60 分钟级），不动概念面、不动编排结构——这是「单轨提速」方案的真实天花板。#4（跳 DB）再拿 ≈30% 但语义出轨道。到 Replit Agent 4 的「较 Agent 3 提速至 3x＋分钟级首版」还差并行子代理一档（#5），那是结构工程。

---

## 3. 轻量 Demo 轨技术形态（Q3）

目标形态：「无数据库、假数据（localStorage 级）、可真实交互、布局好看」的静态站/轻应用，分钟级产出。

### 3.1 四条可行路径（业界证据）

| 路径 | 业界一手先例 | 产出速度量级 | UI 质量预期 | 对本平台的适配成本 |
|---|---|---|---|---|
| (a) 纯前端单文件/少文件生成（执行体直出静态站） | Claude Artifacts＝官方「single-file HTML/单文件 React 组件」沙箱形态；OpenAI Apps SDK＝客户端 iframe 标准 | 单回合 0.5–2 分钟（LLM 吞吐 60–170 t/s × 3–8k token/页，算术推算）；整站 3–8 页 ≈ 5–15 分钟 | 中高：单文件内闭环、无构建面；shadcn/Tailwind 预置下「组装 UI」质量稳（v0/Lovable 官方同款栈） | 低：现执行体+协议变体即可（写 HTML/静态页而非全栈切片）；容器仅作静态 serve（serve.js 已在镜像） |
| (b) 模板+LLM 填充 | Durable 官方「30 秒出站、答三问」；Google Stitch 秒级 UI 生成 | 秒级 | 低天花板：模板档「可用初稿」，二手评测一致 | 高：要建模板库+填充机制，与「AI 生成系统」定位错位（模板复用是硬编码面） |
| (c) 现有基座静态化裁剪（Next.js 但跳 PG/Redis、mock 层） | Replit Agent 3 官方 frontend-only mode「skip database integration first」；Lovable 官方教程「build frontend-first. Start with sample data… Sample data lives only in the browser」 | 每片 5–10 分钟（少写 DB 链路），整站 20–40 分钟 | 高：与现轨同栈同组件库，观感一致 | 中低：基座不动（pg/redis 装而不用），协议跳 DB+假数据层；收口判据不变（8081 探活照跑） |
| (d) 业界快出原型方案外采（如 Stitch/artifacts 嵌入） | 见 (a)(b) | 分钟级 | 中 | 高且引入外部依赖，与自持执行体架构相悖，不建议 |

**最贴合的两条**：(a) 纯前端直出（速度最快、形态最轻）与 (c) 基座裁剪跳 DB（质量最稳、复用最大）。两者可以同源：同执行体、协议按档切换（见方案 C）。

### 3.2 对标 Replit Agent ≈40 分钟的差距分析

- **Replit 的 40 分钟是二手实测带**（Espressio 2026-07「20–40 分钟带 Agent 3」；Manus 横评自测 ~40 分钟）：官方口径只到「minutes」宣传语，无精确承诺。用户实测 40 分钟出完整可交互系统在公开带内，无反例（抱怨集中在 credits 贵与偶发循环，非慢于 1 小时）。
- **它为什么快**（官方一手四杠杆）：①并行子代理（Agent 4「parallel agents… up to 3x faster than Agent 3」——auth/DB/后端/前端同时做）；②并行工具调用（工程博客：短提示注底让 agent 每循环多执行 15% 工具）；③checkpoint 回滚网（敢放长自主跑，出错回滚不重来）；④智能模型路由（官方「同质量 65% 更低成本」）。
- **我们的差距对应**：①无并行子代理（逐片串行＋交接耦合）——结构差距最大的一格；②框架已支持但工具占比 2.5%、无感；③已有版本流+断点续跑（ADR-0020，等价物在）；④模型运营覆盖已具备（agent_operational_config），缺的是「按片难度路由」的策略。
- **还有一个不可比因素**：Replit 40 分钟的产物是「可交互全栈」（自带 auth/DB/hosting 零配置运行时，`*.replit.dev` 常驻）；我们 Demo 档若降形态为纯前端假数据，同 40 分钟预算内质量上限更高（少写的代码量转投 UI 打磨）——**降形态是对齐速度的捷径，这正是方案 B/C 的立论**。

---

## 4. 「跳过数据库用假数据」的业界正当性（Q3 补充）

- **正向先例（一手）**：Lovable 官方教程直接教两段式——「build frontend-first. Start with sample data, get the screens and flows right, and connect the database once the product feels right」＋边界原话「Sample data lives only in the browser. When information needs to persist, your app needs a database」（docs.lovable.dev/tips-tricks/from-idea-to-app）。Replit Agent 3 官方 frontend-only mode「skip database integration first」。
- **产品级无后端预览先例（一手）**：Claude Artifacts＝沙箱 iframe 纯客户端无后端；OpenAI Apps SDK＝client-side iframe 标准。
- **反面证据（一手，界线参考）**：Replit 工程博客指出早期模型「mock data to pass a task」在全栈终点档被当失败模式反制。**业界共识清晰：mock/无库在「预览/原型档」是 feature，在「全栈交付档」是 bug——两档并存、以「何时接库」为切换点。**
- v0/bolt 无此档（v0 官方反而强调 preview 跑真实构建、自认变慢；bolt 只讲 Supabase 接法）——两档并存不是人人做，是定位选择。

---

## 5. 三方案对比素材（Q4）

| 维度 | A 单轨提速 | B 双轨拆分（新建轻量 Demo 生成器，现有轨转付款后生产） | C 同执行体轻协议（一套执行体、Demo 协议跳 DB 与重步骤） |
|---|---|---|---|
| 做法 | §2 表 #1/#2/#3/#6：修首试浪费＋砍自测＋换快模型＋间隙排查；不动结构与语义 | 新建独立 Demo 生成器（纯前端直出或模板填充），现有轨原样转「付款后生产」角色 | AgentProfile 加 Demo 协议档（或运行时协议开关）：跳 DB/假数据层＋收口判据换轻量版＋自测降档；编排/容器/呈现复用 |
| 速度天花板 | 全轨 2–3x（小时级→30–60 分钟） | Demo 分钟级（5–15 分钟）；生产轨不变 | Demo 档 ~2–4x（15–30 分钟级）；生产轨可同享 #1/#2 提速 |
| 产物质量 | 同构现轨（真实落库全栈） | Demo＝纯前端假数据（好看可交互，不能落库）；生产＝现状 | Demo＝同栈跳 DB（观感与生产一致、升级路径连续：接库即转正） |
| 工程量 | 小（配置+重试语义+协议两行；模型运营覆盖已就绪） | 大：新执行体/新协议/新收口/入口分流（entry-mode 已有两档可挂）/呈现语义/两轨状态机与转正衔接 | 中：协议变体+判据参数化+入口分流（同 B 的入口面）+自测档位；无新执行体 |
| 风险 | 低；模型质量回归需实测 | 高：两套执行逻辑长期双维护；「Demo→生产」转正衔接（假数据→落库的改造轮）是隐藏成本；概念面冲突最大（CONTEXT「系统」词条 Avoid Demo——用户面词汇需另立） | 中：协议分叉的测试面；「跳 DB 判据」与「系统=真实应用」口径需裁决例外口径；转正=再跑一轮接库改造（与 B 同款隐藏成本但在同一执行体内） |
| 与 Replit 对齐度 | 靠拢到同量级（40 分钟带） | Demo 档反超（分钟级）但形态降级 | Demo 档同量级（15–30 分钟）、形态半降 |

**素材性观察（裁决归裁决票）**：
1. §1.2 的数据说明「慢」的第一矛盾是**可靠性（33% 零产出超时）不是形态**——无论选哪方案，修首试浪费都是无争议的第一刀，且它不依赖方案分岔。
2. B 与 C 的本质差是「新执行体 vs 协议档位」。现架构里执行体协议即 systemPrompt＋收口判据的组合（AgentProfile.java:142-197），协议档位化（C）改动面小；但 C 的 Demo 档速度受同一 ReAct 单 turn 结构限制，到不了 B 纯前端直出的分钟级。
3. 入口分流的结构已存在（entry-mode 两档、mode 进载荷 endpointType，#299 刚落地）——B/C 都有现成挂点；「用户面怎么称呼 Demo 档产物」（CONTEXT.md「系统」词条 Avoid Demo/原型）是两案共同的前置裁决。
4. 若 Demo 档的定位是「付款前看效果」，C 的同栈跳 DB 有一个 B 给不了的性质：Demo 与生产**同基座同观感**，用户看到的即付款后得到的（升级=接库改造，不换壳）——这与「沙箱中生成的高保真可操作实现即系统的当前形态」（CONTEXT.md「系统」词条）的承诺最连续。

---

## 6. 证据与来源索引

**代码/配置（均已落稿人复核行号）**：`aiplatform-server/src/main/java/com/aieducenter/aiplatform/business/project/application/GenerationProperties.java:19,22`｜`GenerationAppService.java:389,511-521,783,796,808-814,816-818,830-847,858-870,901-908`｜`CoderRunAttempts.java:258,308,351-359,588-602`｜`business/project/domain/model/AgentProfile.java:38,142-197,203`｜`infrastructure/agentscope/ProfileSubagentSupplier.java:44-82`｜`infrastructure/agentscope/ProfileToolkitSupplier.java:126-135`｜`base/agentscope/AgentscopeProperties.java:33-48`｜`base/agentscope/DockerEnvironmentBackend.java:76-86,391-437`｜`src/main/resources/application.yml:58-96`｜`src/main/resources/docker/workspace/dev-image.Dockerfile:1-67`｜`src/main/resources/docker/workspace/baseline/package.json`｜`src/main/resources/docker/workspace/init-workspace.sh:26-52`｜agentscope-java：`HarnessAgent.java:1498-1500`（Toolkit 并行默认开）、`SubagentDeclaration.java:393`（子智能体缺省 10 步）、`ExecutionConfig.java:37-38`（模型调用 5 分钟超时+3 次退避）。

**DB 实测**（`PGPASSWORD=truth psql -U postgres -h localhost -p 5432 -d aiplatform`）：`prj_conversation_entries.closing->durationMs / ->durationBreakdown`（32 片构成分解、attempts 分布、逐尝试 llmMs/selfTestMs）；`prj_generation_segments`（8 项目切片状态）；docker 卷 git log 与 segments 秒级对应（每片一 commit、6–15 文件）。

**ADR**：ADR-0009（纵向切片+收口判据）、ADR-0011（每片新会话+交接；Swarm 并行待耗时埋点数据再评估——本报告即补该数据）、ADR-0013（基座镜像预装、TTFF 压缩）、ADR-0014（预览网关）、ADR-0016（休眠唤醒）、ADR-0020（断点续跑/原地修）。

**业界（一手为主）**：Replit 官方博客 introducing-agent-4（并行子代理 3x）、decision-time-guidance（并行工具调用 +15%/loop、mock data 反制）、introducing-agent-3（frontend-only mode、200 分钟自主运行）；Replit 首页（minutes、Intelligent Model Routing 65% 成本）；Lovable docs tips-tricks/from-idea-to-app（frontend-first/sample data 原话）、guides/lovable-vs-v0（全栈形态）；v0 官网/FAQ（in minutes、真实构建自认变慢）；bolt/WebContainers（毫秒级环境启动）；Claude Artifacts 帮助中心（单文件沙箱形态）；OpenAI Apps SDK 公告（client-side iframe）；Durable（30 秒官方口径）；Google Stitch（官方站）。**二手（已标注）**：Artificial Analysis 吞吐测量（GPT-5 117 t/s、Sonnet 5 74.9 t/s、DeepSeek V4 Flash 173.1 t/s、Haiku 5.5 237.3 t/s）；Espressio Replit Guide 2026（20–40 分钟带）；Manus 横评（~40 分钟自测）。未找到的如实备案：主流厂商官方不发布绝对 tps；v0/bolt 无「mock 快速档」官方表述；「首轮 ~13s」未在仓库文档找到字面出处（近邻：ADR-0016「应用启动 10s+」、184 手册 10–20s 预期带）。

**既有仓内调研复用**：`docs/research/2026-09-03-benchmark-ai-app-builders.md`（三家 agent 循环「interpret→explore→change→check→summarize」、自检是行业标配）、`docs/research/2026-09-03-benchmark-replit-coze-kimi.md`（Replit 自测子 agent Playwright、Kimi Swarm 上下文分片提速 4.5x）。
