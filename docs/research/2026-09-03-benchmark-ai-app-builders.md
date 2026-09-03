# 调研快照：Lovable / Bolt.new / v0 过程可见性与支撑能力对标（2026-09-03）

> 结论关联：wayfinder #60（主对标拆解）、#59（体验改版决定集）；供「概念裁决：直播」「概念裁决：指令区与成果区」作证据。
> 口径：只看线上 SaaS。bolt.diy（开源衍生）仅作 bolt.new v1 协议的谱系证据、随文标注差异；一手来源（官方 docs/changelog/blog/API 文档/源码）为主，二手标注可信度。
> 方法注记：三路并行取证报告综合而成；落稿人对 15+ 处承重引文与源码论断做了逐字/逐行复核（复核清单见 §8），全部命中。调研日 2026-09-03。

## 结论速览

1. **「直播」的行业同构物存在，且比我们的纯文字解说流浓一档**：三家在生成过程中播的都是**结构化活动卡/步骤流**——每个文件操作/命令/搜索/测试是一张带状态与时长的卡，而非自述气泡+动作摘要文字行。动作粒度（工具调用级）是三家共同的粒度下限。判断尺结论：**行业都有 → 低配标配该升级**。
2. **百分比进度条三家都没有**（均查无证据，判未证实不存在）；替代物是三种：Lovable「任务清单+实时已耗时+credits 累计」、v0「活动卡+轮末 Work Details 统计（时长/文件数/行数/credits）」、bolt「带 order 的命名阶段条（Analysing→Files→Generating）」。判断尺结论：**进度条不是行业同构物，不构成标配缺口**。
3. **过程呈现的共同架构是「过程=数据模型」而非「过程=文案」**：v0 把 agent 执行轨迹建模为有序 `message.parts`（8 种类型、带 startedAt/finishedAt），官方原话「**The same object can drive a one-line status, a changed-files view, or the full trace**」——同一份服务端事件数据，前端三档密度自取。bolt 是 LLM 输出自描述 `<boltAction>` 流+前端 streaming parser 逐 action 渲染。Lovable 协议未公开，但行为面同构（动作级活动卡→Details 两级下钻）。
4. **预览是最强的过程呈现，一半可见性不在解说流里**：三家预览都渐进可用——v0 沙箱 VM 起真实 dev server（预览未就绪时轮询返回 null+细进度线）、bolt WebContainer 客户端执行文件流式写入毫秒级 HMR（「写完即可见」）、Lovable 云端 dev server「每个 agent 更新刷一次」防闪烁。我们当前预览是探活通过瞬切、无中间态——这是与行业差距最大的一格。
5. **「每轮=版本快照、可回滚」是三家标配**：Lovable「无保存按钮、每次改动自动成版本」+History 面板（diff/跳回消息/看当时快照/回滚只追加）；v0 每条生成消息挂版本控件+线性历史（restore 追加新版本）；bolt Version History 时间线+聊天内每条消息即检查点（eye 预览/箭头恢复）。**回滚均只回代码不回数据库**。
6. **迭代变更的呈现分层**：摘要给人读（Lovable 回复正文=自然语言摘要、v0 收尾摘要是协议一等字段 `message.content`）+ diff 供查证（Lovable 默认并排 diff、v0 编辑器 diff view、bolt 本体一级 diff 入口未证实）+ 轮末量化统计（v0 Work Details）。「摘要与 diff 分层」而非二选一。
7. **信息架构三家同构：对话区不是 tab、成果区是 tab 簇**。左 chat 右成果区；成果区 tab：Lovable `Preview / Files / Code / More`、v0 `Preview / Code / Design (+Database)`、bolt `Preview / Code` 两态。代码视图三家都有但**默认不进入**（「暴露存在性、不强迫理解」：Lovable glossary 明言不需要代码编辑器；bolt 官方自述 Code View「shaped to feel approachable」）。指令侧升级形态是**指认代替描述**（Lovable preview toolbar 四模式点选、bolt Select 工具、v0 Annotations 点选标注）。
8. **服务端支撑能力四种一手证据形态**：v0 API v2 公开完整协议（SSE 4 事件：快照+`message.parts.chunk` jsondiffpatch 增量+usage+error；断线 resume；预览短时 token+代理 iframe）；bolt.diy 开源代码级（artifact 流协议、`isStreaming` 只对 file 动作开门、100ms 采样、`server-ready` 驱动视图切换）；Lovable 工程博客（fly.io 4000+ 常驻容器、增量 patch 只推被改行+HMR、LSO 预循环知识注入）；三家 agent 循环都收敛于「interpret→explore→change→**check**→summarize」，自检/自修是标配（v0 沙箱内验证+autofix、Lovable build error 率降 90%+、bolt autofix 错误分类追踪进聊天史）。
9. **截图是过程证据的升级方向**：v0 agent 用无头浏览器自测用户流、截图直接回放聊天（`agent-browser` 官方文档化）；Lovable 浏览器测试在 Details 里回放步骤截图+成败总结——「让它自己测给你看」替代用户自查。

## 1. 判断尺总表（#59 口径：行业都有=标配该升级；行业都没有=才质疑概念）

| 维度 | Lovable | Bolt.new | v0 | 判断尺读数 |
|---|---|---|---|---|
| 过程呈现 | 活动卡（动作级）+任务清单+实时耗时/credits+Details 两级下钻 | artifact 卡逐 action 流式展开（文件/命令一行一个+状态色点）+命名阶段条+终端实时滚 | 结构化活动部件流（8 种 part）+「v0 is working」+轮末 Work Details 统计 | 活动卡/步骤流=**标配**；进度条=**无人做**；轮末统计=两家做 |
| 信息架构 | Chat 左+Preview 右；成果区 4 tab（Preview/Files/Code/More）；Build/Plan 模式；预览内四模式工具条 | Chat 左+workbench 右；Preview/Code 两态；代码视图可编辑+Target/Lock file | chat 中+preview 右；Preview/Code/Design(+Database)；版本控件挂消息上 | 双栏+成果区 tab 簇=**标配**；代码视图存在但默认不进=**标配** |
| 迭代变更 | 并排 diff 默认可见+自然语言摘要+无保存按钮自动版本+History 可回滚/书签/看当时快照 | 每消息=检查点（eye 预览/箭头恢复）+Version History 时间线；本体 diff 入口未证实 | 每消息挂版本控件（inspect/diff/restore）+线性历史+编辑器 diff view+收尾摘要协议字段 | 版本快照可回滚=**标配**；diff 呈现=两家半（分层折叠） |
| 支撑能力 | 动作级事件+Details timeline（协议未公开）；fly.io 常驻 dev server+每 update 刷一次；增量 patch；LSO+自愈 | XML action 流+streaming parser+WebContainer 客户端执行；`server-ready` 驱动视图切换；finishReason=length 自动续写 | v2 API：parts 模型+快照/差分混合 SSE+resume；沙箱 VM+dev server+预览轮询就绪；`restorable` 消息级恢复 | 「过程=结构化数据模型+预览渐进可用」=**标配底座**，实现路线三家不同 |

## 2. Lovable 平台画像

术语校准先行：**「Edit mode / Default mode」是历史术语**（2024-11 Targeted Edits → 2025-02 Visual Edits → 2025-06-30 Agent Mode beta → 2025-07-23 Agent 成默认（$100M ARR 文）→ 2025-09-01 旧单步退役 → 2026-01-28 改名 **Build/Plan mode** → 2026-06 Visual edits 面板被**预览工具条**取代）。现行体系 = Build/Plan 双模式（输入框旁 ⌥P 切换、会话连续）+ 预览内四模式工具条（S/T/D/C）。证据：[docs chat](https://docs.lovable.dev/features/projects/chat)、[preview-toolbar](https://docs.lovable.dev/features/preview-toolbar)、changelog/blog（一手）。

### 因果链 L1：服务端 agent 以动作为粒度循环执行 → 聊天流渲染「活动卡」→ Details 两级下钻

- 官方把可见性明确归因于服务端循环（[docs chat](https://docs.lovable.dev/features/projects/chat)，一手，已逐字复核）："**Behind every message, Lovable's agent works in a loop**: it gathers context from your conversation and project, takes action with tools, and checks the result before moving on. You'll see that loop reflected in everything below, from activity cards to the questions Lovable asks."
- "**Lovable's work shows up in chat as activity cards** that track its individual actions: file edits, commands, web searches, browser tests, and the subagents it delegates research to."（同上，已复核）
- 两级下钻："Click a card while Lovable works, or **Details** on a finished change, to open the Details view, **which opens where the preview usually appears** and shows the full timeline of steps and file changes. On bigger Build mode requests, Lovable also **shows the tasks it's working through**."（同上，已复核）——默认折叠成卡、愿意看的人才下钻全量 timeline；**Details 视图占据预览位**，是「指令区↔成果区」联动的同构物。
- agent 循环公开口径（[blog agent-mode-beta](https://lovable.dev/blog/agent-mode-beta)，2025-06-30，一手）：pre-agent 时代「tries to do everything in one single step」是错误率根源；Agent mode = interpret → explore codebase → uncover missing context → make changes → **auto-fix** → wrap up with a clear summary；宣称 build error 率降 90%（2025-07 更新 91%）。2026-01 加只读并行研究 subagents（进度在活动卡可见）。

### 因果链 L2：成本不可事前预估 → 运行中实时累计 + 阈值 check-in

- 官方明说不做估算（[agent-mode docs](https://docs.lovable.dev/features/agent-mode)，一手，已复核）："Lovable **does not show an upfront credit estimate** before a Build mode request runs, because cost depends on work that is discovered during execution."
- 替代三层：消息 More options 实时显示已工作时长（排除等用户的时间）与已实际扣费 credits、完成后定格；**credit check-in**（累计超阈值默认 20 credits 暂停询问是否继续）；欠费暂停出「Add credits / Wrap up」卡（docs chat + credits-and-usage，一手）。
- 计费按工作量（blog agent，2025-07，一手）：「Make button gray」0.50 credit / 「Add authentication」1.20 / landing page 1.70。
- 对非技术用户**隐藏 token 概念**，只露出 credit。

### 因果链 L3：每轮改动结构化为文件级 diff + 自然语言摘要 → 分层呈现 + diff 渲染性能设计

- [glossary](https://docs.lovable.dev/glossary)（一手）："Diff: A side-by-side comparison of file changes Lovable made in a turn."；回复正文则是摘要——「its response summarizes what it did and what changed」（docs chat，已复核）。**摘要给人读、diff 供查证，同屏分层**。
- 性能教训（[blog diff-viewer 工程文](https://lovable.dev/blog/anthropic-sonnet-3-7-lovable-diff-viewer)，2025-03-12，一手）：单轮 diff 常达 15-20+ 文件，CodeMirror 阻塞式初始化致主线程 500-1500ms 冻结；解法 **time slicing**（先渲 2 个文件、批间 50ms 渐进替换），副作用成了体验：「diff 逐个流入而非整体弹出」——**diff 渲染策略本身是过程可见性设计的一部分**。

### 因果链 L4：每次改动自动成版本（无保存按钮）→ History 面板四动作 + 回滚只追加

- [history docs](https://docs.lovable.dev/features/projects/history)（一手，已复核）："**Every change Lovable makes to your project creates a version automatically.** There is no save button."
- 每版本动作：Open preview in new tab / **View code changes**（diff）/ **Go to message in chat**（跳回产生该版本的对话时刻）/ Revert / Bookmark；点版本开只逛不换的 snapshot 视图。
- Revert 语义：全项目粒度、**只回滚代码不回滚数据库**、回滚不退 credit；回滚产生新版本卡（类 git revert，「历史永不改写只追加」，blog versioning-2.0，2025-03）。
- 聊天侧近路：**Undo latest edit**（一键零确认）/ Revert to this version / **Edit message → Revert and resend**（改写旧消息从该点重跑）/ More options 里 **Preview**（不动当前版本，直接看「那条消息刚完成时」的应用快照）——历史可视化但不破坏现在（docs chat，一手，已复核）。
- 2026 新增 **Drafts**：每草稿独立聊天与预览地址，接受后并入主线——方案对比从回滚升级为并行分支。

### 因果链 L5：预览=云端常驻 dev server、增量 patch → 「看着它长出来」+ 刷新节律治理

- 预览基建（[blog visual-edits](https://lovable.dev/blog/introducing-visual-edits)，2025-03-13，工程一手）："an ephemeral development server spins up instantly in the cloud... we continuously host over **4,000 instances on fly.io**"；保存时「Diffs are computed to update **only precisely modified lines** / Changes are securely pushed to the cloud-hosted environment / **An HMR event is immediately triggered**」——增量 patch 优于全量重生成（动机原文："Each regeneration costs both time and computational resources"）。
- 刷新节律演进（changelog，一手）：2026-02 "**refreshes once per agent update** instead of multiple times, reducing flicker and incomplete states while code is being written"；2026-08 手动编辑时不再整页 reload；2026-06 起可关闭 live preview 改为「完成一个变更才更新」的构建版预览。
- 预览生命周期：临时云环境闲置自动暂停（"Still building?" 卡，Keep building 从原状态续跑）、Shift+Refresh 重启（[preview docs](https://docs.lovable.dev/features/projects/preview)，一手）。
- **服务端执行是事后可看的前提**："Your request runs on Lovable's servers, not in your browser, so you can close the tab and come back"（docs chat FAQ，一手）；单消息最长 10 小时；changelog 提及「持续延长项目锁+沙箱心跳」支撑长任务。

### 其余形态要素（Lovable，逐项）

- 流式文字：有（changelog 修 bug 措辞反证打字机渐显是常态）；**流式原始代码：未证实（判无）**——代码以完成后 diff 呈现。
- 截图：多源——agent 主动截图（changelog 2025-11）、浏览器测试自动截图（[browser-testing](https://docs.lovable.dev/features/browser-testing)）、版本 hover 缩略图；聊天内图片查看器带 Draw 标注 + Add to chat。
- 提问卡：最多 4 问、每问带选项或自由答、可逐问或全跳过（跳过取合理默认）、草稿存浏览器防刷新丢失（docs chat，一手，已复核）——对 BA 面谈 PRD 的交互直接同构。
- 运行中可插话：follow-ups 在「下一个自然停点（usually within seconds）」被捡起、未捡起前灰显在底部；旧消息队列已弃用（docs chat，一手，已复核）。
- 错误闭环：活动卡上 **Try to fix**（扫日志找问题尝试修）；10 次免费池 24h 逐次恢复；错误对话框显示真实错误信息（changelog 2025-03）。
- 平台工程：Python→Go 42k 行、「单次 chat 请求内处理 50+ HTTP 请求」、DI 依赖图就绪即执行（[blog from-python-to-go](https://lovable.dev/blog/from-python-to-go)，2025-02，一手）；LSO 预循环知识注入（classifier→selector→synthesizer，无命中即跳过；stuck 率 -5%，[blog vent-tool](https://lovable.dev/blog/we-gave-our-agent-a-vent-tool)，2026-05，一手）。

### Lovable 对非技术用户暴露度总评

「过程对齐自然语言，技术细节按需下钻」：默认层全是自然语言（活动卡动作名、摘要、提问卡、设计三方向预览）；技术细节三层下钻且全部可选（Details timeline → diff → Code tab）；裸露的技术物是 diff（默认可见含代码内容）与真实错误信息，但 glossary 明言「You never need the code editor to build with Lovable」。信任策略是**用「永远退得回来」替代「永远不出错」**（无保存按钮+一键 Undo+回滚只追加+可关标签页+免费修复池）。

## 3. Bolt.new 平台画像

谱系声明：主研究对象为线上 bolt.new。代码级证据取自 **bolt.diy**（github.com/stackblitz-labs/bolt.diy，官方 fork、自称 "the official open source version of Bolt.new"）——保留 v1 核心架构（boltArtifact 流协议+WebContainers）；**bolt.new 本体 2025-10 转向 V2（Claude Agent）后协议未再公开**，凡本体与谱系可能分叉处均标注。源码为 2026-09-03 main 分支（关键文件已由落稿人经 GitHub API 逐行复核）。

### 因果链 B1：LLM 输出自描述 XML action 流 → 前端 streaming parser 逐 action 渲染卡片（边生成边出）

- 协议（bolt.diy `app/lib/common/prompts/prompts.ts`，系统提示词原文）："Bolt creates a **SINGLE, comprehensive artifact** for each project"，内嵌 `<boltAction type="shell|file|start">`（file 带 filePath）；「The order of the actions is VERY IMPORTANT」；dev server 已起则不重启（"The existing dev server can automatically detect changes"）。
- 解析（`app/lib/runtime/message-parser.ts`，已复核：`ARTIFACT_TAG_OPEN = '<boltArtifact'`、`export class StreamingMessageParser`、`onArtifactOpen/onActionStream` 回调）：按 messageId 维护增量指针逐 chunk 扫描标签，触发 open/stream/close 五类回调——**UI 卡片逐 action 增量出现，不等整条消息生成完**。
- 卡片视觉（`Artifact.tsx`）：每个 action 一行——文件/终端图标+文件名或命令（shiki 高亮）+**状态色点**（pending 灰/running 加载/complete 绿/failed 红）；点文件行右侧直接跳该文件。信息密度：**每个文件一行+每条命令一行**，典型首轮 10-30 行。
- 本体现状（重要差异）：V2 后聊天呈现转 agent 式中间步骤，每步视觉形态官方未描述，**未证实**（artifact 卡是否保留存疑）。

### 因果链 B2：服务端 progress 注解 → 聊天顶部命名阶段条（不是百分比）

（bolt.diy `app/routes/api.chat.ts`）服务端在 LLM 调用前后向流写 `{type:'progress', label, status:'in-progress', order, message}` 注解——'Analysing Request' → 'Determining Files to Read' → 'Code Files Selected' → 'Generating Response' → 'Response Generated'，前端渲染为有序步骤条。**「进度」的 bolt 答案：带 order 的命名阶段序列**，诚实且可扩展到多智能体阶段。

### 因果链 B3：文件动作流式直写 WebContainer fs → 预览毫秒级渐进 → server-ready 驱动视图自动切换

- **执行环境在客户端**（[webcontainers.io/api](https://webcontainers.io/api)，一手 API 文档）：`boot/spawn/fs.watch/on('server-ready')/reloadPreview` 等。PostHog 采访 CTO Albert Pai（2025-09，二手高可信）："People assume we're running a giant server farm. **In reality, the server is your browser.**"；HMR "working in tens of milliseconds"——**文件写完预览毫秒级变，是过程可见不卡顿的根因**。
- 流式门控（`action-runner.ts`，已复核逐行）：`if (isStreaming && action.type !== 'file') return`——**文件随 token 增量即时落盘，shell/start 等闭合标签才执行**；全局串行队列保序（install 先于 dev start）；workbench 用 `createSampler(..., 100)` 把流式写入 **100ms 采样合并**防逐 token 重渲染。
- 视图导演（`workbench.ts`/`Workbench.client.tsx`）：文件 action 开始 → 强制切到 code 视图并选中该文件（**生成中自动带你围观代码**）；`hasPreview` 就绪 → 自动切 preview——零点击的「看代码→看成品」。
- 2025 年后变化（一手，Safari 支持文档）："Previews are **hosted** rather than running in the browser"，出现「Building a hosted preview」等待态——部分场景转服务端托管构建；分工边界官方未说明，未证实。
- 错误回传：WebContainer `forwardPreviewErrors` + `on('preview-message')` 把 iframe 里 console.error/unhandledrejection 带栈回传宿主——**预览里的前端报错能变成聊天里的修复 prompt**；本体官方 autofix："Bolt now autofixes errors for you... tracked as **separate error types in your chat history**"（release notes，一手）。
- 生成中断恢复：`StreamRecoveryManager`（45s 流活性监控+2 次重试）；`finishReason === 'length'` 自动注入 CONTINUE_PROMPT 续段——用户感知连续流、后台拼段。

### 因果链 B4：前端保留文件历史栈 → 纯前端 diff + 每消息检查点 + Version History

- diff（bolt.diy `DiffView.tsx`+`FileHistory` 类型）：每文件 `originalContent / changes / versions[]` 快照栈，自实现逐行+字符级 diff、`+N/-N` 徽章、File Changes 下拉列出全部被改文件。**本体一级 diff 入口未证实**（本体是 Preview/Code 两态；diff slider 是 bolt.diy 形态）。
- 版本（一手 [rollback-backup](https://support.bolt.new/building/rollback-backup) + release notes）：顶栏时钟图标→时间线→**点选先预览该版本**→Restore 确认；可改名/收藏；**数据库不随版本回滚**。聊天内近路：历史消息 eye 预览 / 箭头恢复——**每条消息即一个检查点**。

### Bolt 信息架构与暴露度

- 布局：左 chat+右 workbench；本体 **Preview/Code 两态**（顶部 `<>` 切换）；官方定位（[code-view](https://support.bolt.new/building/using-bolt/code-view)，一手，已逐字复核）："**By default, your Bolt project shows your chat and a preview of the app you're building**"；"Although Code View is generally intended for more technical users, **the interface is shaped to feel approachable for anyone who wants to dig deeper**."
- 代码视图：Files 树（右键 New/Delete/**Target file**（圈定只准改它）/**Lock file**（禁改））、Ctrl+S 后 "Bolt automatically builds your changes"（已复核）、选中代码段出 Ask Bolt 带引用提问。
- 预览区（bolt.diy 代码级+本体官方）：地址栏/端口下拉/全屏/12 种设备模拟/新窗口；本体 **Select 工具**（[chat-tools](https://support.bolt.new/building/chat-tools)，一手）：预览里 hover 高亮任意元素、点击选择显示在 chatbox 上方——指哪打哪的反馈回路。
- agent 循环（本体，一手 [agents](https://support.bolt.new/building/using-bolt/agents)，已复核）："Bolt offers two agents, **Standard and Max**... **You choose the agent that matches your work, and Bolt handles model selection behind the scenes**"——把「选模型」从用户认知里删掉；**Plan Mode** 取代 Discussion Mode（2026-08-03 生效，先想清楚再写）；上下文治理：`/clear`、`.bolt/ignore`、claude.md。bolt.todo 计划文件：多渠道检索无果，**未证实**。
- token 计量：token 是统一计量单位（订阅额度/滚存/reload），用量可视化在 Subscriptions & Tokens 面板；usage 作为消息注解随流落账（bolt.diy 代码级）。

### Bolt 对非技术用户暴露度总评

「面向产出、收起过程细节、保留挖掘路径」：默认落在 Preview（官方原话），可全程不看一行代码；聊天 action 卡被压缩成带状态图标的单行列表，读作「正在做什么」的进度叙事；技术分层：聊天卡→Code 视图→终端（最深，官方因「terminal errors often go unnoticed」而做自动检测）。变更可见性主通道是**预览前后对比+Version History 时间线**而非 diff。

## 4. v0 平台画像

版本坐标（先校准时间线）：2023-24 单组件流式滚代码（过时背景）→ 2025-07-23 v1 Platform API（generations/versions 模型）→ 2025-08-11 v0.dev→v0.app 全面 agent 化 → **2026-08-05 v2 API GA：数据模型重构为「chat 持有当前文件状态 + message.parts 承载过程历史」**（[blog](https://vercel.com/blog/introducing-the-new-v0-api)，publishedTime 2026-08-05，一手，已逐字复核）。本节以 2026-09 现状为准。

### 因果链 V1：agent 执行轨迹建模为有序 message.parts → 聊天区渲染结构化活动部件流（三档密度同源）

- 官方核心论断（blog，已复核）："**Your interface needs to show what happens between sending a request and seeing the updated preview.** Every message includes ordered `parts`: text, thinking, file reads and edits, searches, bash commands, tool calls, and agent actions. **The same object can drive a one-line status, a changed-files view, or the full trace.**"
- v2 API 的 8 种 part（[Send Message Streaming 参考](https://v0.app/docs/api/v2/reference/messages/send-message-streaming)，一手，已复核关键词），各带 startedAt/finishedAt：`text`（解说）/`thinking`/`file-read`/`file-edit`（operation∈create/update/delete/rename/patch+path，**只有路径元数据无代码内容**）/`search`/`bash`（command+output+isDangerous）/`tool-call`（含待批准状态）/`agent-action`（generate_image、manage_todos、**ask_user_questions**、**exit_plan_mode** 等交互卡）。
- 产品端渲染（[agentic-features](https://v0.app/docs/agentic-features)，一手）：Progress indicators（agent 动作实时更新）、Task cards、引用 pill、工具状态卡。
- **关键读数：聊天区不是自述气泡流而是结构化活动部件流**；且密度由前端取舍（一行状态/变更文件视图/全量 trace），服务端只发一份。

### 因果链 V2：快照+差分混合 SSE → 前端一致性 + 断线续传

- SSE 仅 4 类事件：`message`（流首/尾各一次**全量快照**）、`message.parts.chunk`（parts 数组的 **jsondiffpatch 增量**）、`message.usage`（最终用量含 tokens/creditsCost）、`error`；`finishReason` null=进行中、非 null=终态（v2 API 文档，一手，已复核）。
- SDK 抹平为累积快照（[resuming-streams](https://v0.app/docs/api/v2/guides/resuming-streams)，一手）："Each item in `result.stream` is an **accumulated snapshot**... not a raw delta that you need to patch yourself."
- **断线续传是服务端职责**：刷新/关标签页不中断生成，resume 端点重连同一条流；changelog（2026-06-26，一手）："Preview sandboxes no longer time out mid-generation during long agent runs, including when the tab is closed."

### 因果链 V3：版本化快照模型 → 每条消息挂版本控件 + 线性历史回滚（v2 迁移为消息级恢复）

- [versions docs](https://v0.app/docs/versions)（一手，已逐字复核全文）："**Each time v0 updates a code block from a message, it creates a new version.**"；"Restoring an old version creates a new, most recent version... to maintain a **linear version history**."；"Use the version controls attached to each generated message to **inspect a version, view its diff, or restore** an earlier generation."
- v1 API version 对象含 `status: pending|completed|failed`、`demoUrl`、`screenshotUrl`、`files[{name, content, locked}]`——**版本快照带全量文件+截图+状态机**，文件可 locked 防 AI 覆写（v1 API 文档，一手）。
- v2 迁移（blog，已复核）："The chat holds current app state, and messages hold its history. **Replace version workflows with chat file workflows**"；message 带 `restorable` 布尔、`restore-message` 端点按消息恢复——**「每条消息=一个潜在可恢复点」是 v2 版本语义**。
- diff 呈现：编辑器 diff view（"View v0's changes in the diff view. This helps you understand exactly what was modified in each generation"，[code-editing](https://v0.app/docs/code-editing)，一手）；diff 顶部 summary header 文件统计+变更行数（changelog，一手）。任意两版互 diff 入口**未证实**（diff 是「本轮 vs 上一轮」形态）。
- 轮末三件套：①收尾摘要是**协议一等字段**——`message.content` 官方定义 "The trailing prose of the message — **the agent's closing summary**"；②Work Details 量化卡："time worked, files modified, lines of code changed, and credits used"（changelog 2025-10-31，一手）；③diff summary header。

### 因果链 V4：预览=真实沙箱 VM + framework-aware dev server → 预览渐进可用 + agent 与用户同文件系统

- [sandbox docs](https://v0.app/docs/sandbox)（一手）：Vercel Sandbox 轻量 VM 秒级启动、framework-aware dev server（自动检测 Next.js/Vite/Node）、文件系统同 chat 持久、每 chat 隔离+网络出站策略；"It replaces the **older browser-based preview, which couldn't run server code, API routes, or real database connections**."
- Code tab 编辑器与 agent **共享同一沙箱文件系统**（"a built-in editor attached to the same filesystem, so edits from the editor, v0, or the terminal all read and write the same files"）——生成中切 Code tab 可见已写入文件；聊天流里**拿不到代码文本**（file-edit part 无代码载荷），代码走文件系统通道。
- 渐进可用：首轮生成前显示空/旧预览+细进度线（"a subtle progress line and no loading status pills"，changelog 2026-07-07，一手）；API 侧 `chats.getPreview` 未就绪返回 null 轮询、就绪发短时 token、预览过期用 `x-v0-preview-refresh` 头通知（[Accessing Previews](https://v0.app/docs/api/v2/guides/accessing-previews)，一手）；官方推荐**隔离域名代理 origin+iframe（allow-scripts+allow-same-origin）**防生成代码攻击宿主。
- **agent 自验证闭环**（blog，已复核）："**v0 verifies the code running in the Sandbox, so it can catch and fix errors in your app in real time.**"；`agent-browser` 在终端自动放行列表——"launch a headless browser session against your live preview to **verify user flows and capture errors**"（[terminal-commands](https://v0.app/docs/terminal-commands)，一手）；changelog 2026-05-15："v0 can now show the **browser screenshots the agent captures while testing** your preview."——自测截图直接回放聊天。

### v0 信息架构与暴露度

- 三区：左 sidebar（chats 按项目分组/Favorites/archive）｜中 chat 面板（对话+活动部件流+composer：模型选择、权限模式、附件、**prompt 队列最多 10 条可重排**）｜右预览面板，tab 官方命名 **Preview / Code / Design**（[quickstart](https://v0.app/docs/quickstart)，一手；已复核）+Database（changelog 2026-08-28）。**没有单独叫 Chat 的右栏 tab——对话就是页面主体**。
- Preview：iframe 内嵌沙箱 dev server；Console panel 含 Logs+Terminal（与 agent 同一 shell）；可新窗/分享 URL。Code：VS Code 内核完整 IDE+diff view+split view；手动编辑出 Unsaved Changes 横幅并阻止发消息。Design：点选元素可视化改样式、Apply 后作为新版本写回。
- 澄清问题**表单化**（单选/多选/跳过/自定义，协议对应 `ask_user_questions`）+ 计划审批（`exit_plan_mode`：approved/request-changes/rejected）+ 工具权限卡——**人机交互是协议一等公民**（三类 pending task+resolve 端点，409 冲突保护）。
- 面向非技术的反馈形态：**Annotations mode**（changelog 2026-06-19，一手）："click elements in the preview to drop numbered comments and send them to the agent as one batch of feedback."
- 生态位：v0 线上协议非 AI SDK 协议，但官方提供 `V0Transport` 把 v0 流转成 AI SDK UIMessage（[custom-chat-interface](https://v0.app/docs/api/v2/guides/custom-chat-interface)，一手）——parts 模型与 AI SDK UIMessage parts 高度同构、更细分（file-read/file-edit/search/bash 独立类型）且带 per-part 时间戳。

### v0 对非技术用户暴露度总评

「过程低噪声、结果高保真、迭代可回退」：无进度条、无 token 计数、无代码流；一句「v0 is working」+依次出现的活动卡+需要你时浮现的表单；默认视图是可交互真实预览（非截图）；改了什么用 diff+轮末统计回答；agent 自测截图直接展示；技术概念按需下沉（文件树/终端/diff 全在 Code tab 里，提意见进化为预览上点选标注）。

## 5. 跨三家共性：能力 → 呈现因果链归纳

1. **动作级结构化事件流 → 活动卡/步骤流**：三家粒度下限一致（文件操作/命令/搜索/测试各为独立可渲染单元，带状态与时长）。实现路线三家不同（v0 parts 协议公开、bolt XML action 流+前端解析、lovable 协议未公开行为同构），但「过程=数据模型而非文案」是共同底座。**纯文字解说流缺的不是文案而是事件结构**。
2. **同一份过程数据、多档呈现密度**：v0 官方明示三档（一行状态/变更文件视图/全量 trace）；Lovable 两级（卡→Details）；bolt 两级（单行 action→Code 视图）。默认低密度、按需下钻是共识。
3. **预览渐进可用是过程可见性的另一半**：v0 沙箱 dev server 轮询就绪、bolt WebContainer 毫秒级 HMR、Lovable 每 agent update 刷一次。「解说流+可交互预览」双轨并行；单靠解说流撑过程可见性，三家都没有这么做。
4. **版本快照+线性回滚=迭代型产品安全感标配**：每轮改动自动成版本（无需用户保存）、可看当时快照/diff、回滚=追加新版本不改写历史、只回代码不回数据。
5. **摘要与 diff 分层并存**：自然语言收尾摘要（v0 是协议字段）+文件级 diff（供查证，分层折叠）+轮末量化统计（v0 Work Details）。
6. **自检/自修闭环**：三家 agent 都在真实运行环境里验证产物（v0 沙箱+无头浏览器、Lovable 浏览器测试、bolt 预览错误回传+autofix），错误呈现均带「一键修复」入口。
7. **服务端执行、连接可断**：生成与浏览器连接解耦（关标签页不中断、回来继续看），是过程「事后可看」与长任务的前提。
8. **提问/审批表单化**：需要用户输入时不出自由文本框而出结构化表单（选项/可跳过/草稿保留），且是协议一等公民（v0 pending task+resolve）。
9. **指认代替描述**：预览上点选元素作为反馈载体（Lovable 四模式工具条、bolt Select、v0 Annotations）——「提意见」的门槛从写文字降到点截图。
10. **对非技术用户：暴露存在性、不强迫理解**：代码视图存在但默认不进；模型选择被抽象掉（bolt Standard/Max「Bolt handles model selection behind the scenes」、v0 auto）；token 概念被隐藏（Lovable 只露 credit）。

## 6. 对本平台的映射（#59 决议两档：现能力可做 / 需动服务端）

本平台现状基线（自家事实）：SSE 名册（`aiplatform-web/src/lib/api/schema.d.ts:699-719`，正本 `aiplatform-server/docs/spec/SSE事件清单.md`）= 平台帧 `run-start/run-created/error/run-finish/question-raised`（+ #56/#58 的收口帧族）+ 引擎透传 `text/reasoning/patch/tool/step-start/step-finish`（引擎 part 原样）；预览=探活端点轮询、通过才返回 URL 并发 `preview-ready`、**「无应用期间不出文件列表中间态」**（schema.d.ts:466）；直播口径=用户语言解说广播、思考与代码不播、收起即逝（CONTEXT.md:60-62）；交付文件视图=直读工作区实时文件清单（schema.d.ts:506），无快照无 diff。

以下为**证据映射**（裁决归裁决票，此处只标两档代价）：

1. **直播从「文字行」升级为「动作卡/步骤卡」**——行业同构物支持升级（§5.1/§5.2）。**现能力可做**：引擎透传 `tool/patch/step-*` 帧已含动作类型与负载，前端渲染成带状态（进行中/完成/失败）与时长的卡即可；「用户语言、代码不播」口径不受影响（v0 file-edit part 本就只有路径元数据，行业同样不播代码正文）。代价：前端为主，直播词汇表或需补「动作卡」词条。
2. **轮末统计卡（时长/文件数/变更行数）**——v0 Work Details 同构。**现能力可做**：`patch` 帧可聚合成轮末数字；如需进 `run-finish` payload 则扩帧（轻量服务端改动）。
3. **收尾摘要为一等帧**——v0 `message.content` 定义即收尾摘要。**现能力可做**（收口 text 帧承载或扩 `run-finish`）。
4. **预览渐进可用（run 进行中长出）**——CONTEXT 口径已要求渐进（「可访问后随编码进展逐步显现」），现状是探活瞬切、无中间态。**需动服务端**：行业三家共同底座是「dev server 存活期间增量刷新」（HMR/每 update 刷一次/轮询就绪）；我们单容器 8081 服务探活通过后，轮内增量呈现需补「部分可用」的探测/通知机制（如 webcontainer 式 file-change 广播或 Lovable 式每更新刷一次的信号帧）。这是与行业差距最大、也是「想看系统一点点做出来」最直接的一格。
5. **版本快照/回滚**——三家标配、我们全无（交付文件视图只读实时态）。**需动服务端**：工作区卷上做轮末快照点+落账（Lovable「无保存按钮自动版本」同构；回滚只回代码不回数据的口径三家一致，可直采）。
6. **轮间 diff 呈现**——依赖 5 的快照；前端渲染可后置（Lovable time-slicing 教训：单轮多文件 diff 要做渲染性能设计）。**需动服务端**（快照）+ 前端分层（摘要默认、diff 下钻）。
7. **截图/agent 自测回放**——v0/Lovable 已把「它自己测给你看」做成升级方向。**需动服务端**（沙箱内 headless 浏览器与截图管道），v1 扩展点级。
8. **提问卡表单化**——我们已有问答卡（question-raised+续跑），形态已同构行业标配；Lovable 细节（最多 4 问/可跳过取默认/草稿防丢）可直接对表体检。**现能力可做**（呈现细节）。
9. **进度条**——三家均无、替代物是任务清单+已耗时+轮末统计。**不必做**（判断尺：行业都没有的不构成缺口）。

## 7. 未证实事项备案（如实，不推测）

- **Lovable**：事件流协议（SSE/WS、schema）未公开，过程事件粒度按行为面确证；百分比进度条/ETA/流式原始代码/token 展示：查无证据，判不存在。
- **Bolt.new 本体**：V2 聊天流每步视觉形态官方未描述；artifact 卡是否保留存疑；一级 diff 入口未证实（diff slider 是 bolt.diy 形态）；bolt.todo 计划文件多渠道检索无果（疑讹传）；hosted preview 与浏览器内 WebContainer 的分工边界未说明；自动版本保存节奏官方未写明（约 10 分钟一备系 Reddit 用户口径，二手中）。
- **v0**：生成中无任何形式进度条/token 实时条（查无证据）；Code tab 生成中「逐字滚动」效果未证实；预览桌面/移动 viewport 切换 2026 现状未提（旧版有）；任意两个历史版本互 diff 入口未证实；v0.app 自身前端是否直接用 AI SDK useChat 未证实。
- 二手来源可信度档：PostHog 采访 bolt CTO（2025-09）＝高；StackBlitz 官方 LinkedIn＝官方口径；Taskade/Devwiz/HostAdvice 评测＝中；Reddit＝中低（已尽量不承载关键论断）。

## 8. 来源与复核记录

**落稿人逐字/逐行复核清单（2026-09-03/04，webReader+curl .md 源+gh api，全部命中）**：

- v0：[introducing-the-new-v0-api](https://vercel.com/blog/introducing-the-new-v0-api)（parts 有序模型原话、三档密度原话、「Render message parts, not only final text」、chat 持状态/消息持历史、Sandbox 实时验证）；[versions](https://v0.app/docs/versions) 全文三句核心口径；v2 streaming API 参考（file-read/file-edit/agent-action/tool-call/message.parts.chunk/finishReason/jsondiffpatch 均在）；quickstart（Code/Design tab 命名）。
- Lovable：[chat docs](https://docs.lovable.dev/features/projects/chat)（agent 循环原话、活动卡原话、Details 占预览位、follow-ups 灰显捡起、提问卡 4 问可跳过草稿保留、Undo/Revert/Preview 动作、消息队列弃用、Try to fix 10 次 24h）；agent-mode（无事前成本预估原话、credit check-in、10 小时）；history（无保存按钮自动成版本原话）。
- Bolt：[code-view](https://support.bolt.new/building/using-bolt/code-view)（默认聊天+预览原话、Code View 定位原话、Target/Lock/Ask Bolt、Ctrl+S 自动构建）；[agents](https://support.bolt.new/building/using-bolt/agents)（Standard/Max 两档、Bolt handles model selection 原话）；bolt.diy 源码（`message-parser.ts` 的 boltArtifact 标签常量+StreamingMessageParser+onActionStream；`action-runner.ts` 的 `isStreaming && action.type !== 'file'` 门控）。

**关键来源索引**：

- Lovable（一手为主）：[docs.lovable.dev](https://docs.lovable.dev)（chat/agent-mode/history/preview/preview-toolbar/code-mode/browser-testing/subagents/glossary）；[lovable.dev/blog](https://lovable.dev/blog)（agent-mode-beta、agent/$100M ARR、anthropic-sonnet-3-7-lovable-diff-viewer、introducing-visual-edits、from-python-to-go、versioning-with-lovable-two-point-zero、we-gave-our-agent-a-vent-tool、routing-billions-of-tokens）。
- Bolt（一手为主）：[support.bolt.new](https://support.bolt.new/release-notes)（release-notes、code-view、rollback-backup、chat-tools、agents、Safari 支持文）；[webcontainers.io/api](https://webcontainers.io/api)；bolt.diy main 分支源码（2026-09-03）：`app/lib/runtime/{message-parser,action-runner}.ts`、`app/lib/stores/{workbench,previews}.ts`、`app/components/chat/Artifact.tsx`、`app/components/workbench/{Workbench.client,Preview,DiffView}.tsx`、`app/routes/api.chat.ts`、`app/lib/common/prompts/prompts.ts`；二手：PostHog 采访 CTO（2025-09，高）。
- v0（一手为主）：[v0.app/docs](https://v0.app/docs)（quickstart、versions、sandbox、agentic-features、code-editing、terminal-commands、text-prompting）+ [API v1/v2 参考](https://v0.app/docs/api/v2)（send-message-streaming、resuming-streams、accessing-previews、handling-agent-interactions、custom-chat-interface、get-version）；[vercel.com/blog](https://vercel.com/blog)（v0-app 2025-08-11、v0-platform-api 2025-07-23、introducing-the-new-v0-api 2026-08-05）；[github.com/vercel/v0-sdk](https://github.com/vercel/v0-sdk)；v0.app/changelog（2025-10-31 Work Details、2026-06-26 沙箱不超时、2026-07-07 进度线+Plan agent、2026-05-15 自测截图、2026-06-19 Annotations 等）。
