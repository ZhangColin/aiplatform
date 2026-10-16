# 参考平台「几十分钟出完整系统」机制实现层调研报告

> 票 [#314](https://github.com/ZhangColin/aiplatform/issues/314)（wayfinder 地图 #307）调研输入，2026-10-15/16。背景：用户追问「同样需求给 Replit 几十分钟出完整系统，我们可能只做了一个切片——好好调研参考平台怎么做到」。承重结论已并入 #314 决议（Demo＝真实系统落库、速度靠工程体系）；本报告为机制细节正本。

核心发现一句话：**Replit 的 40 分钟不是单执行体跑得快，而是「规划出任务图 → 任务在项目完整副本中并行执行（含任务内再拆子代理）→ 独立自测子代理验证 → 冲突由 AI 辅助合并 → checkpoint 保底回滚」这一整套工程体系**；Lovable 把状态外置为事件日志（trajectory）使 agent 可死可换可并行；v0/bolt 靠单次全量生成+生成环内验证赢首屏速度；Kimi 把 swarm 调度做进模型本身（我们不可复制）。

---

## 一、Replit（材料最厚，按六个问题组织）

### 1. 并行子代理架构（Agent 4，2026-03 发布）

- **任务拆分维度**：官方口径是按功能域并行——「Tackle auth, database, back-end functionality and front-end design all at once with parallel agents」。拆分算法本身（怎么从 plan 生成任务）未公开。来源：https://replit.com/blog/introducing-agent-4-built-for-creativity
- **工作区隔离 = 完整项目副本，不是共享工作区**：「That plan creates tasks that get executed in their own isolated environments—exact copies of your current project—and move through the Kanban board independently. Nothing touches your main project until you explicitly approve it.」每个后台任务拥有完整项目副本+完整项目上下文。来源：https://replit.com/blog/whats-changed-agent3-to-agent4 、https://docs.replit.com/core-concepts/agent/task-system
- **合并模型 = approve-then-merge**：任务完成后用户审阅再 apply 回主版本；Agent Modes 提供 auto-merge 选项（自动合并后台任务变更）。来源：https://docs.replit.com/features/agent/agent-modes
- **冲突解决 = 系统标记 + 专门子代理解决**：「If two tasks touch the same files, the system flags the conflict and handles resolution through the Agent—no manual intervention needed」；发布文更直接：「When changes conflict, Agent 4 uses specialized sub-agents to resolve them」。即先确定性检测（同文件触达），解不了才升级到 LLM 子代理。来源：https://replit.com/blog/whats-changed-agent3-to-agent4 、https://replit.com/blog/introducing-agent-4-built-for-creativity
- **应用一个任务后其余副本落后一个版本 → update-before-apply**：应用任务 A 后，其余进行中任务的副本基于旧版本，「需要 update before apply」——第二个任务应用前要在新版本上重做/重放（rebase 语义）。来源：https://docs.replit.com/learn/build-in-parallel
- **依赖自动检测**：主线程在生成 plan 时自动检测任务依赖（例：dashboard 依赖 DB schema 任务，会等待前置完成再启动）。来源：https://docs.replit.com/learn/build-in-parallel
- **任务内还可再拆子代理**：「Agent 4 can split a single task into smaller pieces, work on them simultaneously with sub-agents, and recombine the results」——两层并行（任务间+任务内）。来源：https://replit.com/blog/introducing-agent-4-built-for-creativity
- **并行档位**：Starter 0 个后台任务 / Core 1 / Pro 10 / Enterprise 64（并行度是商业限额，非技术上限）。来源：https://docs.replit.com/learn/build-in-parallel
- **手动切分指导**：官方文档的经验法则——「描述里有 and 就拆成两个任务」；同屏两处改动、探索性设计、一句话说不清的任务建议保持串行。来源：https://docs.replit.com/learn/build-in-parallel
- Agent 3（2025-09）是单执行体+并行**工具调用**：Agent 3 Max 平台级峰值「每分钟 670 万次并行工具调用」（平台聚合口径，非单任务）；Agent 3 平均 ~40 分钟出一个完整应用（官方自己给的对标数字），Agent 4 宣称 up to 3x faster than Agent 3。来源：https://replit.com/blog/introducing-agent-3-our-most-autonomous-agent-yet 、https://replit.com/blog/introducing-agent-4-built-for-creativity

### 2. 任务规划

- **plan-while-building**：规划和构建并行进行（不阻塞）——plan 是独立 chat，产出的任务直接进隔离环境执行。来源：https://replit.com/blog/whats-changed-agent3-to-agent4
- Kanban 看板：Drafts → Active → Ready（待审）→ Done；主线程负责方向决策，后台任务是独立工作线程。来源：https://docs.replit.com/core-concepts/agent/task-system
- 动态重规划的公开细节有限；评测博客（2026-06）提到 ViBench 专门适配了「Agent 4 的 parallel-and-merge 和 subagent decomposition」的评测，说明拆解-合并是作为被评测的核心能力在持续调优。来源：https://replit.com/blog/evaluating-and-improving-agent-at-scale

### 3. 模型路由（Intelligent Model Routing）

- 官方口径 65% 成本降低；路由对用户透明，可选显示「路由原因码」（如当前请求用轻模型因为「直接消息+无代码变更」）。来源：https://replit.com/blog/intelligent-model-routing
- **路由信号（可直接抄的启发式）**：直接短消息、重试、长文件上下文 → 轻模型；深度推理、多文件编辑、复杂需求、并行任务 → 强模型。来源：https://replit.com/blog/intelligent-model-routing
- 用户可全局覆盖或按 agent 覆盖模型选择。编码主力模型档位未公开点名具体模型。来源：https://replit.com/blog/intelligent-model-routing

### 4. 失败/超时处理（checkpoint 回滚网）

- **checkpoint 结构**：每次 checkpoint = 一个 git commit（全文件系统）+ 数据库状态 + agent memory + 对话上下文；双向回滚（checkpoint ↔ checkpoint）；数据库跑在快照文件系统上所以库状态随文件系统一起回滚。来源：https://replit.com/blog/inside-replits-snapshot-engine 、https://neon.com/blog/replit-app-history-powered-by-neon-branches
- **checkpoint 触发时机**：feature 完成、重大里程碑、稳定状态、**错误恢复前**（「Before attempting fixes for critical issues」——修危重问题前先打点，修坏了回去）。来源：https://docs.replit.com/features/version-control/checkpoints-and-rollbacks
- **快照引擎实现**：bottomless storage = 虚拟块设备（NBD 协议）+ 16MiB 不可变 chunk + manifest 指针，copy-on-write；复制整个磁盘=复制 manifest，常数时间（这使「每任务一个完整副本」在成本上可行，也是并行采样的基础）。来源：https://replit.com/blog/inside-replits-snapshot-engine
- Agent 只碰 dev 库，prod 库分离；git 远端 append-only 备份。来源：https://replit.com/blog/inside-replits-snapshot-engine
- **任务失败语义**：取消任务=丢弃该副本工作（不可恢复）；「应用失败 → 任务重建分支重试」循环；agent memory（含此前的学习）也入 checkpoint，回滚后保留。来源：https://docs.replit.com/learn/build-in-parallel
- 展望方向（官方明说）：parallel sampling——多个 agent 并行解同一问题取最佳原子应用（SWE-bench 72%→80% 的路径）。来源：https://replit.com/blog/inside-replits-snapshot-engine
- 「工具卡死/模型流挂起的专门处理」：**未找到公开细节**，如实说。

### 5. 质量保障（自测子代理，材料最扎实的一篇）

来源（以下全部）：https://replit.com/blog/automated-self-testing （2025-12，工程博客）

- **核心教训**：用 Computer-Use 模型测试太贵太慢——中位 $0.50 + 30-90 秒才填完一个 5 字段表单；改用 **REPL-based verification**：把 Playwright API（page.click 等）作为函数注入持久 JS REPL，测试代码是普通程序，变量/浏览器会话跨命令持久，**测试状态留在代码里不烧 token**。测试会话中位成本降到 $0.20。
- **信息通道**：精简 DOM+ARIA（非原始 DOM）、数据库**只读** SQL 直查验证数据层、客户端/服务端日志按需增量捕获、多浏览器标签页并行测互不干扰（购物车两个用户视角）。
- **Potemkin interface（波将金界面）防线**：UI 看着能点但没接真后端——只看截图会漏，必须走「浏览器+数据库+日志」三源交叉验证。
- **测试是独立 subagent，与主 agent 上下文隔离**：主 agent 只传高层计划（项目结构+测试目标），测试 subagent 返回测试结果**摘要**；主 agent 上下文维持 80k-100k token 量级（含系统提示/文件清单/测试摘要），测试的完整输出（DOM、日志、截图）不进主上下文——这是 200 分钟自主运行（从 20 分钟提升 10 倍）的关键。
- Agent 3 落地口径：App Testing 周期性在真实浏览器点击/表单/API/数据源验证，agent 自己判断「变化足够多才测」（不是每消息后都测）；测试系统比 computer-use 快 3 倍、省 10 倍成本。来源：https://replit.com/blog/introducing-agent-3-our-most-autonomous-agent-yet
- 评测侧：ViBench（自然语言 PRD+自然语言测试计划，eval agent 用 Playwright 灵活探索）+ Telescope（trace 聚类归因）+ 自改进环（agent 给 agent 提修复，工程师评审入库）+ sandbox forking 并行评测。来源：https://replit.com/blog/evaluating-and-improving-agent-at-scale

### 6. 上下文管理

- 主 agent 80k-100k token；测试细节隔离在 subagent，摘要回流（见上）。
- agent memory 随 checkpoint 持久化。来源：https://docs.replit.com/features/version-control/checkpoints-and-rollbacks

## 二、Lovable（三篇官方工程博客）

### Trajectory 架构（状态外置，agent 可死可换）

来源：https://lovable.dev/blog/how-lovable-agents-work-together （2026-08）

- **trajectory = 仿 Git 的 append-only、可 fork 的事件日志**：每事件单 parent 指针+named heads；事件粒度到 tool_call/ToolExecutionStart/End/Revert/Approval。
- **上下文不是存储而是投影**：每次迭代从 trajectory 反向 walk 渲染 prompt；**compaction 异步跑在 side trajectory 上**（PromptCompactionStart/End 事件对，摘要替代旧事件），主循环永不阻塞在压缩上。
- **双日志**：inbox（外部/用户写入）+ agent trajectory（agent 独占写）；迭代边界把 inbox 未处理消息拷入 trajectory。
- **Agent Control Plane (ACP)**：SpawnAgent、ForkAndSendMessage、SendMessage、NotifyParents、StopAgent 原语；activation 是唤醒信号不带 payload；**每条 trajectory 单一独占 writer**。
- **「从不 merge 两条 trajectory」**：语义永远不清，agent 间共享结果走 ACP 消息，trajectory 只 branch。
- **suspend/resume at iteration boundary**：迭代边界可挂起，agent 进程可抛弃——工作全在 trajectory+git 里；**sandbox 死亡时挂起等待恢复而非盲目重试**。
- 存储：Bigtable 行键布局+content-addressed blob store；activation log + Pub/Sub + reconciler re-drive。规模：工作日约 5 亿事件/260 万用户 turn；SSE 重连=从 trajectory 重放（掉线不丢状态）。

### Chats 多智能体协作（KANBAN-QUEUE）

来源：https://lovable.dev/blog/chats-how-lovables-multi-agent-work （2026-09）

- workspace 级 chat agent 分解工作 → 并行 fan-out 多个 project builder agents（每 agent 一条独立 trajectory，同一 git 分支，提交经由 chat agent 仲裁后合入 main）。
- **KANBAN-QUEUE 协议**：子代理完成当前任务后必须请求下一个（绑定当前 in-flight 任务），仲裁者维持队列+in-flight 集，支持 requeue/redo/abort；队列每 30 秒心跳重验证。
- **TEMPLATES（生成器谱系）**：确定性生成器（纯代码）→ LLM 生成器（LLM+渲染器，如 React 组件生成器）→ LLM 规划器（@-mention 已有生成器组成 spec）；生成结果经人工评审可晋升为模板，**「LLM 输出是运行时 Artifact，不是资产」**——个体工作沉淀为物种资产。
- **上下文注入按测量驱动**：每 agent 注入全部 in-flight 兄弟的输出测量（非完整输出）；提交测量含锁获取时长、失败率等元数据；agent 死了 requeue 换新 agent 接手。
- 运行时目标：子代理约 100 秒/任务；Postgres advisory lock 获取中位 4.2 秒；共享 pgvector 上下文存储（title embedding 检索既有叙事，避免重复劳动）。

### 模型路由/负载均衡

来源：https://lovable.dev/blog/routing-billions-of-tokens-per-minute （2026-02）

- 多 provider 路由层：**PID 控制器**按 provider 错误率动态调流量权重；错误率 >0.5% 开始降权；provider 每 30 秒打分 = 成功数 − 200×错误数 + 1（sliding window）。
- **project-level affinity**：同一项目的请求粘同一 provider，保住 prompt cache（大系统提示复用）。

另：Lovable 官方 X 口径——默认全栈生成（full-stack by default）+ Agents 使典型任务时间缩减 75%（营销口径，量级参考）。

## 三、v0（Vercel）

来源：https://vercel.com/blog/v0-composite-model-family （2025-06）、https://v0.app/docs/agentic-features 、https://vercel.com/blog/v0-app

- **复合模型流水线（三层）**：① **RAG-codebase**（基于 RAG 的小模型，从最新版本真实包代码建检索索引，把「真实世界的 React/shadcn 惯用法」注入上下文——对抗模型幻觉 API）；② **v0-1.0-md-large**（基于前沿模型微调的 BCI，**一次性生成全量代码**，只做最小必要编辑）；③ **validator**（大模型验证，多轮校验 import/依赖/渲染，失败带错误回 BCI 重试=AutoFix 循环）。教训：通用编码模型对自有组件库认知过时，RAG 补「组件当前真实形态」。
- **agentic 机制**：to-do 管理（跟踪任务、更新计划、输出技术分解）；100+ 并行 action 共享状态；active streams（自托管 Vercel sandbox 上并行长时任务）；browser use（打开自己构建的应用，真实浏览器反馈+截图回环）；生成环内自动检测修复错误；权限三档 Ask/Auto（并行 FSDP：预构建沙箱直接全栈部署）。
- v0 无公开的「sub-agent 拆任务并行」细节——它的并行主要是工具级+预构建沙箱。

## 四、bolt.new（StackBlitz）

来源：https://newsletter.posthog.com/p/from-0-to-40m-arr-inside-the-tech （2025-09，PostHog 对 bolt CTO Albert Pai+founding engineer Dominic Elm 的访谈，一手访谈转述）

- **单次精心构造的 LLM 调用生成整个应用**：初始生成=一个 prompt 一次调用输出全部文件——「整个应用源自单次精心构造的 prompt」；后续按聊天消息逐轮修改。
- **WebContainer（浏览器内 Node 运行时）是速度根基**：Rust→WASM 文件系统+SharedArrayBuffer/Atomics（在线程间共享内存无需拷贝）；每个「进程」=Worker，shell 是 TS 写的 JSH；Service Worker 虚拟 localhost；WebSocket relay 桥 TCP。**服务器即浏览器**——无云端容器冷启动，企业防火墙内也能跑。
- **冷启动优化**：runtime <10MB、node_modules 压缩 blob 流式加载、CDN 层预压缩包 → 常见 npm install <500ms。
- **System prompt 反直觉教训**：对单文件（如 App.tsx），**一次性输出全量代码+抑制宽容性**（anti-accommodation，防止「// 其余代码在此」式省略）比拆多文件+宽容性好——更快、更不容易断；所以 bolt 主打 React 集中态架构（单一 App.tsx 类）。
- 后续演进：2026-08-03 退役 v1 Agent 和 Discussion Mode，转向 plan-first 的 Bolt Agent+自动模型路由（二手：bolt release notes 转述，机制细节未公开）。

## 五、Kimi（月之暗面）

来源：https://zhuanlan.zhihu.com/p/2030671467550095254 （Kimi 官方知乎发布文，2026-04-20，一手）、https://www.kimi.com/help/agent/agent-swarm （官方帮助文档）、https://kimi.com/news/kimi-k3 、https://kimi.ai/blog/kimi-k3

- **Agent 集群调度做进模型里（K2.6 起的「模型原生能力」）**：300 个子 Agent 并行、4000 步协作（K2.5 为 100/1500）；「调度逻辑内置在模型里」——上层框架只给目标，模型自己编排（官方称「Agent 的 OS」思路）。**这条路对我们不可复制**（需要训自己的模型），但它说明：工程框架层的 swarm 编排（我们可做的）与模型原生调度（我们做不了）是两条路线。
- **长程编码**：13 小时不间断、4000+ 行代码、1000+ 工具调用；最长 5 天自主运行（Claw 群组：K2.6 做协调者，按技能画像动态分派、检测中断自动重分配）。
- **Swarm 机制细节（官方帮助文档）**：适用判据=重度场景（≥5 个网页产出、≥3 类工具、任务树≥3 层）；**中间态显式建模**——任务树三层（目标→子任务→行动项），节点状态含「锁定/进行中/等待输入/已完成/**冲突**」（冲突是一等公民状态，自动解决或升级用户输入）；**变更分批提交（约每 10 个文件一批）**防止大规模冲突；全局上下文共享池+并行 LLM 语义检查把关一致性。
- K3（2026-07）：2.8T 参数、KDA 混合线性注意力、原生视觉；同步开源 MoonEP/FlashKDA/AgentEnv 三项 Infra；Goal 模式=目标导向 Loop 循环（用户定终点，agent 自主推进，策略自我进化；细节来自 kimi.com/help/kimi-work/goal-mode 搜索摘要，页面解析失败未能全文核验）。

## 六、对我们架构的可借鉴改动点

我们的架构事实：纵向切片严格串行、片间交接摘要、单 turn ReAct 上限 300 迭代、切片=尝试环 3 次×30 分钟墙钟、agentscope-java 自研编排可改、每项目一个工作区容器（wsp_workspaces）。

按预期收益排序：

1. **切片并行化（最大杠杆，直接对标 Replit 差距）**：我们已有 build plan 切片清单（saveBuildPlan），把「严格串行」改为「依赖分层调度」——plan 阶段给切片标注依赖（Replit：自动检测依赖，无依赖切片才并行）；无依赖切片（独立前端页/独立后端域）开 2-3 个**容器副本**并行执行，完成后合并。合并顺序走 Replit 的 update-before-apply 语义：每合入一个切片，其余在途副本需要重放/重做差异。工程前提：容器 fork 能力（容器快照或 git worktree 级），Replit 用 CoW 块设备让「复制整个项目」成本近零——我们用 git+容器快照可近似。冲突先确定性检测（同文件触达，我们有 FileChangeFacts 逐字上报可复用），解不了才升级 LLM 辅助合并子代理（Replit 的 specialized sub-agents）。首期可保守：**并行度 2、只并行前端型切片**，与 Replit Core 档单任务→Pro 档 10 任务的渐进路径一致。
2. **独立验证子代理（把「跑得起来」变成判定者而非声称者）**：Replit 的关键设计四件套可整体移植到 agentscope-java——① 验证体是独立 subagent，主执行体只传切片计划+收到结果**摘要**（我们主上下文不被 DOM/日志污染，300 迭代上限的压力直接减轻）；② 验证基于「真容器+真端口」的三源交叉（HTTP 探活/Playwright 或 curl 级点击流 + DB 只读 SQL + 服务端日志），防波将金界面——这正对我们「绿测≠能跑」的反复教训；③ 验证脚本走持久会话（REPL 思路），状态留在代码不留在 token，成本可控；④ 验证不过→回滚到切片前 checkpoint 重来，而不是在脏现场上叠加修复（我们尝试环 3 次目前是「原地重试」语义）。
3. **checkpoint 粒度+回滚语义**：每切片起跑前 git commit+DB 快照点（我们已有 Flyway/PG 基础设施）；把「修危重问题前先打点」学过来（Replit 触发时机第四条）；尝试环失败=回滚到切片前而非第三次重试后转终态失败。回滚便宜才敢让子代理快速失败。
4. **片间交接摘要 → append-only 事件日志投影（Lovable trajectory 思路）**：我们片间交接是一次性有损压缩；改为 append-only 事件流（工具调用/文件变更已有逐字事实）+ 惰性投影渲染下一切片上下文 + **异步 compaction**（不在关键路径上压缩）。附带收益：agent 进程崩溃可从日志恢复（Lovable suspend/resume at iteration boundary——迭代边界挂起而非盲目重试，对我们「容器意外重启后 agent 态丢失」一类问题是结构性解法）；SSE 重连可重放。**#314 决议裁定不入本轮**：为日均五亿事件规模建的基建，我们断点续跑（ADR-0020）已覆盖主要面，做了是为不存在的规模花钱。
5. **模型路由（低成本档位）**：我们已有多模型接入口（DEEPSEEK 门控），路由信号直接抄 Replit 启发式：重试/短上下文/纯文本任务→轻模型；多文件编辑/并行任务/复杂调试→强模型。成本收益口径参考 65%（量级）。
6. **首屏速度：UI 先行轨道**：三家共同启示——Replit Agent 3 有 frontend-only 快速原型模式、Lovable 默认全栈+前端先行、bolt 单次全量生成让用户 10 秒内看到东西在跑。我们的纵向切片第一条若是端到端薄轨（含后端），首屏必然被后端拖住；可加「UI 骨架先行」：第一切片只出前端+假数据/乐观态让预览先活，系统切片后台继续。
7. **切片内文件写入策略（bolt 反直觉经验）**：对入口级单文件，一次全量输出+反宽容提示（禁止「其余代码略」）比多文件小步快跑更快更稳——可试验在我们的前端切片生成提示词上。
8. **明确不借鉴**：Kimi 的模型原生 swarm 调度（需自研模型）；Replit 的 bottomless 块设备（自建存储层，我们用容器快照+git 近似即可）；Lovable 的 Bigtable 级事件基建（规模不需要，关系库事件表起步即可）。

## 没找到的（如实）

- Replit Agent 4 任务拆分算法的内部实现（按什么策略从 plan 生成任务、任务内子代理数量上限）——官方只给了功能域举例和产品语义。
- 各平台「工具卡死/模型流挂起」的专门 watchdog 机制——无公开细节；最接近的是 Lovable 的「sandbox 死亡时挂起而非盲目重试」。
- v0 各复合模型的具体基座模型名与参数档位（博客未点名）。
- bolt 2026 plan-first agent 的机制细节（二手转述）。
- Kimi Goal 模式官方页全文（解析失败，仅有摘要级信息）。
