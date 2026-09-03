# 对标拆解：Replit Agent、扣子编程（Coze）、Kimi 网页——过程可见性与支撑能力（2026-09-03）

> 票 [#61](https://github.com/ZhangColin/aiplatform/issues/61)（参照对标）；与主对标票 [#60](https://github.com/ZhangColin/aiplatform/issues/60)（Lovable / Bolt.new / v0）同构：四维度同名同序，按「能力 → 呈现效果」因果链组织，供 [#63](https://github.com/ZhangColin/aiplatform/issues/63)（概念裁决：指令区与成果区）、[#64](https://github.com/ZhangColin/aiplatform/issues/64)（概念裁决：直播）作证据。
> 口径（map [#59](https://github.com/ZhangColin/aiplatform/issues/59)）：只看线上 SaaS；判断尺 = 行业同构物。
> 来源标注：一手 = 官方文档 / 官方 blog / 官方产品页（中文生态产品用中文官方源）；二手均标注可信度。查不到一手证据处如实记「未证实」。产品状态截至 2026-09-03。

## 0. 产品框定与两处校正（调研前置发现）

1. **Replit Agent**：replit.com 线上 Agent 产品。代际：Agent 3（2025-09-10 [博客](https://replit.com/blog/introducing-agent-3-our-most-autonomous-agent-yet)）→ **Agent 4（2026-03-11/13，当前最新、新项目默认**，[博客](https://replit.com/blog/introducing-agent-4-built-for-creativity)、[changelog](https://docs.replit.com/updates/2026/03/13/changelog)）。本文以 Agent 4 为主、Agent 3 作对照；不涉 CLI/自托管。
2. **Coze 的 vibe coding =「扣子编程」（code.coze.cn）**，非拖拽做 bot。品牌沿革（一手）：原「扣子空间」（space.coze.cn）与 coze.cn 开发平台于 2026-01 升级合并为「扣子」（AI 办公平台）与「扣子编程」，官方公告见 [docs.coze.cn](https://docs.coze.cn/guides_20260119_coze_premium_upgraded)；入口双形态——扣子 App「+ 新建编程项目」或直登 code.coze.cn。海外版 coze.com 文档登录墙，**过程可见性细节未证实**（仅确认 `project-ide` 路由存在）。
3. **「Kimi Code 在线版」不存在**（一手）：Kimi Code（昵称 KFC）专指终端/IDE 编程 Agent（CLI + VS Code/Cursor 插件，[帮助中心定位](https://www.kimi.ai/zh-hans/help/category/agent)）；[kimi.com/code](https://kimi.com/code) 页面主体是安装命令与订阅价目。与用户描述「表述想法、一边更新系统一边看效果」匹配的真实产品是 **Kimi 网页（Websites）／Agent 模式**（kimi.com 侧栏：PPT / 网站 / 文档 / 深度研究 / 表格 / Agent 集群）——2025-09-26 以「OK Computer」模式上线，现由 K3 驱动（2026-07-16，[官方发布文](https://www.kimi.com/news/kimi-k3)）。本文以「Kimi 网页/Agent 模式」为对标对象，OK Computer 时期作历史对照。

---

## 1. Replit Agent

### 过程呈现形态

- **三层粒度的「边做边出」**：① 聊天流进度解说（streaming progress updates，plan → 分步执行）；② 文件实时被编辑（编辑器可见）；③ Webview 面板随写码自动刷新的应用中间态。官方文档原文："Follow along as Agent plans and builds, **streaming progress updates** and **editing files in real time**"；"You can watch your app update in real time through the **Webview panel**, which refreshes automatically as Agent writes code... you can test the app **while it's still being built**"（[Agent 3 文档快照](https://docs.replit.com/llms-full.txt)）。
- **自测即过程直播（Agent 3 起招牌）**：Agent 面板内嵌真实浏览器预览，"**Watch Agent's cursor as it clicks around your app**, testing functions... entering mock data"；测后返回测试摘要，且提供"**Interactive video replay**"（点击视频回放整场测试，底部滑块跳转分段）（[App Testing 文档](https://docs.replit.com/core-concepts/agent/app-testing)，一手）。
- **任务清单 → 看板**：Plan mode 产出有序任务列表供审阅（[Plan mode](https://docs.replit.com/core-concepts/agent/plan-mode)）；Agent 4 升级为常驻 **Kanban 看板**（Drafts / Active / Ready / Done 四列，任务卡左→右流动）——"All tasks appear on a shared **Kanban board**... gives every collaborator a real-time view of what's being worked on"（[Agent 3→4 对比博客](https://replit.com/blog/whats-changed-agent3-to-agent4)，一手）。
- **预览刷新驱动**：Agent 在沙盒内改代码，dev server 常驻并挂公网临时 URL（`*.replit.dev`）；官方人员确认"Web previews are served from the **same workspace** where the agent edits files... the running web server and database **update automatically**"（[ProductHunt 发布帖内 Replit 团队评论](https://www.producthunt.com/posts/replit-agent-3)，一手·官方人员）。**边写边刷新、不等 build**；底层 HMR 还是整页 reload **未证实**。
- **跨端过程可见**：移动 App Live Activities（锁屏/灵动岛实时跟进度）+ 三类通知（Agent needs your help / done / 计费）（[Mobile 文档](https://docs.replit.com/platforms/mobile-app)，一手）；200 分钟级自主运行时主页有实时进度视图（Agent 3 博客）。

### 信息架构

- **双栏**：Agent pane（聊天）+ Preview（原 Webview）；其他面板：Console / Shell / Editor / Files / Git / Canvas（[Project Editor](https://docs.replit.com/core-concepts/project-editor)、[Preview](https://docs.replit.com/core-concepts/project-editor/editor-and-tools/preview)）。
- **模式切换不在「Agent vs IDE」，在 Agent 输入框内**：左下角 Plan / Build 选择器；Agent 设置档 Lite / Economy / Power（+自测、代码优化、Turbo 开关）（[Plan mode](https://docs.replit.com/core-concepts/agent/plan-mode)）。
- **对非技术用户：代码可降级为不可见，而非不存在**。官方 FAQ："Do I need to know how to code? **No.**"；代码藏在 Tool dock 的 Editor/Files 里（需主动打开），默认主视角 = 聊天 + 预览。Agent 4 新增 Design Canvas：运行中的应用以**活预览帧**钉在无限画布上（"live, interactive previews right on the Canvas. You can click around and use them"），设计修改直落生产代码（[Canvas 文档](https://docs.replit.com/replitai/canvas)、[Agent 4 博客](https://replit.com/blog/introducing-agent-4-built-for-creativity)）。

### 迭代变更呈现

- **「这轮改了什么」= 三轨**（[Checkpoints 文档](https://docs.replit.com/core-concepts/agent/checkpoints-and-rollbacks)，一手）：
  1. **Checkpoint 轨**：功能完成/里程碑/修错前自动打点，每个 checkpoint 带 AI 生成描述 + 时间戳 + **变更范围（改了哪些文件/特性）** + 该段计费；入口在 Agent tab、Git pane、聊天内时间线；
  2. **Git 轨**：每个 checkpoint 自动生成对应 commit，可同步 GitHub；File History 支持单文件级版本流 + 行内 diff + 「像看电影一样回放文件演变」（[File History](https://docs.replit.com/core-concepts/project-editor/version-control/file-history)）；
  3. **摘要轨**：每轮结束聊天内变更摘要、自测后的测试摘要。
- **回滚双向且全状态**：一键恢复 workspace + 对话上下文 + agent 记忆 +（可选）数据库，还能 roll forward（"Checkpoints... work bidirectionally"）；部分界面支持回滚前非破坏性预览 checkpoint 状态。
- **中途提意见的并入方式**：**Message Queue**——运行中发的消息进队列抽屉，"processed in order **after every completion of an Agent work loop**"，可编辑/删除/拖拽排序；紧急用状态栏 **Pause** 硬打断（[Message Queue 文档](https://docs.replit.com/core-concepts/agent/message-queue)）。**Take over**：自测卡在人工步骤（如登录）时弹出接管入口，用户在那个正在直播的浏览器会话里亲手完成再交还（App Testing 文档）。Agent 4：意见 → Drafts 任务卡 → Ready 列 → 人工 review → apply/dismiss。

### 支撑能力

- **自测 = 独立子 agent + notebook 式持久执行环境**（[工程博客](https://replit.com/blog/automated-self-testing)，2025-12，一手）：以代码驱动 Playwright（浏览器会话/变量持久，"context stays in code, not in tokens"），注入增强 DOM（ARIA/test 属性）、数据库只读查询、增量日志；主↔子 agent 只传高层计划与测试摘要（主上下文常达 80k-100k tokens，隔离防污染）——这是用户能看到「真浏览器 + 光标直播 + 回放视频」的服务端基础；多步测试中位成本 ~$0.20/场。何时自测由 agent 自判（"intelligently determines when testing would be most valuable"）。
- **全状态 checkpoint**：不止代码——含对话上下文、agent 记忆、（可选）数据库 → 回滚后对话连续不断片。
- **隔离副本 + fork 合并**：Agent 4 任务在项目精确副本中并行，文件冲突由专门 sub-agent 解（看板 Ready 列敢做人工审批门的前提 = 变更从未直接落主版本）；大任务自动拆 fork 并行再合并。
- **常驻运行时与内置服务**：每 App 自带 auth/database/hosting 零配置；`*.replit.dev` 公网可达 → webview 刷新与 agent 自测共用同一运行时。
- **编排循环**：Plan（可审阅）→ Build（直播+实时编辑）→ 自测（子 agent）→ checkpoint 收口 → 下一循环；Message Queue 以 work loop 为并入边界。Agent 4 把 plan-then-build 改为 **plan-while-building**。

**因果链（condensed）**：沙盒常驻 dev server + 公网 URL → webview 边写边刷（不等 build）｜自测子 agent 代码驱动真浏览器 → 过程可直播（内嵌浏览器+可见光标）+ 回放视频｜全状态 checkpoint + 自动 git commit → 变更有三轨可看、回滚后上下文连续｜任务跑隔离副本、Ready 才 apply → 变更呈现升级为看板任务卡+审批门｜Message Queue 以 work loop 为边界 → 插话不打断直播、按序并入。

**未证实**：预览刷新底层机制（HMR/reload）；聊天与文件编辑的流式粒度；移动端能否看自测回放；Autonomy Selector 四级细节（论坛正文不可得，二手）；Agent 1（2024-09）原始形态未取证。

---

## 2. 扣子编程（Coze，code.coze.cn）

### 过程呈现形态

- **边做边出的「解说 + 操作卡」直播，不是整段憋完**（一手·官方文档配图分析）：AI 回复 = 分阶段文字解说（「现在创建新闻检索 API：」「现在构建项目：」「问题出在新闻检索 API 集成上，让我检查并修复」）+ 穿插**可折叠操作卡片**：创建/阅读/编辑文件、执行命令（含完整 shell 命令行）、**更新计划**、「思考过程」、「搜索文件 **/*.py」；顶部有「已收起所有步骤 ▾」折叠控件。**无进度条/百分比，无勾选式 TODO**（[网页应用指南](https://docs.coze.cn/guides_vibe_coding_web_app)、[环境指南](https://docs.coze.cn/guides_vibe_coding_environment)）。
- **计划模式**：「实施步骤」卡片为纯文字有序列表（阶段一~六，标注将涉及的文件路径），后跟选择卡「计划文档已生成，你希望如何继续？」（确认进入执行 / 输入修改意见）+ 提交按钮；确认后自动切 Agent 模式。运行模式两级：「运行所有指令」自动执行；「危险操作需确认」在删文件/大重构/改核心逻辑时暂停请求确认（[FAQ](https://docs.coze.cn/guides_vibe_coding_faq)）。
- **预览出现时机**：官方口径「代码生成完毕后，自动构建并启动服务，以提供可视化界面供你预览」——**首轮是整段代码→构建→起服务→预览出结果，预览区不逐文件渐进刷新**；过程中的中间态靠对话流卡片直播。修复轮由 Agent 重启服务刷新预览；预览区右上角有刷新/重启/新标签页控件（[网页应用指南](https://docs.coze.cn/guides_vibe_coding_web_app)）。
- **分阶段验收点**：「初步生成后端代码后，编程 Agent 会自动生成测试用例并完成一轮单元测试。测试通过后……提供后端代码的预览，同时提醒你对后端开发部分进行验收」（[扣子封装文档](https://docs.coze.cn/cozespace_vibe_coding_web_app)）。
- **收口帧（每轮事务的渲染）**：结构化总结（实现功能/技术架构/API 验证结果）+ **自检清单**（绿色 ✅ 逐项：脚本位置、可执行权限、HTTP 200、构建产物、功能完整性）+ **版本卡片**（短 SHA + commit message + 「回到该版本」「查看修改记录」）+ 点赞/点踩/重新生成 + 本轮积分消耗。
- **故障呈现**：对话区出失败气泡 + 诊断叙述（「新闻 API 实际上是工作的，问题可能在前端的调用上」）+ 操作卡流；预览区内渲染异常直接可见。

### 信息架构

- **三栏布局**（官方配图证实）：① AI 对话区 ② 文件树 ③ 工作区（代码编辑/预览/终端集成）；底部面板四页签：**终端 / 控制台 / 输出 / 运行记录** + 常驻沙箱资源占用（「0.0 Core | 3.4 GB」）与「释放容量」（[环境指南](https://docs.coze.cn/guides_vibe_coding_environment)）。
- 工作区顶部标签页：**预览 / 集成管理 / 数据库 / +新标签页**（预览是工作区的一个标签页，与代码文件平级）；右上角协作、部署（红色主按钮）、分享。
- **模式切换**：Agent / 问答（只讨论不动代码）/ 计划 三模式；输入框旁「Auto ▾」下拉 +「技能 22」按钮 +「设计引导」开关（先选风格→生成原型→确认后按原型出代码）（FAQ）。
- **代码渐进披露**：文件树默认收起（Option+B 呼出），代码编辑器藏在文件树后；**移动端完全没有代码区**（单栏对话流 +「预览」按钮 + 扫码预览）。官方口径「将复杂的代码逻辑转变为清晰可见的图形界面……**无需感知扣子编程生成的全代码**，零编程基础的用户也能实时调试与预览应用」（[欢迎页](https://docs.coze.cn/guides_welcome)）。
- **Git 面板嵌在文件树区**：文件 U/A/D/M 状态、右键「打开变更」看 diff、放弃更改；「**Agent 对文件的修改会自动提交**，你手动修改的文件需在 Git 面板手动提交」。
- **会话 vs 分支**（多会话/多分支均在左侧列表）：会话 = 文件共享、上下文隔离（最多 100 会话，**共用同一份代码与同一个预览**）；分支 = 独立 worktree（独立目录+独立预览，最多 100 分支）（[多会话](https://docs.coze.cn/cozespace_vibe_coding_multi_session)、[多分支](https://docs.coze.cn/vibe-coding-multi-branch)）。

### 迭代变更呈现

- **版本卡片内嵌对话流**（每轮收口）：短 SHA + 多行 commit message（「feat: 完成每日信息差生成器网站开发 — 创建 Next.js 项目基础架构 — 实现新闻检索 API 集成…」）+「回到该版本」「查看修改记录」。
- **版本历史面板**：对话区顶部图标 → 找版本 → 回滚；**编辑已发历史消息重发 = 版本记录里另起一个分支**——对话树与版本树同构。
- **Diff 视图**：文档承诺「AI 修改代码文件，也会（在编辑器）高亮展示编辑前后的差异（Diff）」；Git 面板右键「打开变更」；分支列表可「查看 Diff」对比新旧分支。**注意：官方 Diff 配图经图像分析实为普通编辑器视图，未见红绿高亮——Diff 视觉形态未证实，以文档文字为准。**
- **合并报告（多分支）**：Agent 调技能自动冲突检查并合并（--no-ff + verify），产出结构化表格（源/目标分支、冲突 ✅ clean、verify 结果、commit SHA）+ 一句话变更摘要。
- **部署历史**：仅部署成功记录可回滚；回滚 = 基于历史版本**新建部署记录**（原始记录不删）；回滚确认框展示环境变量差异；**数据库不随回滚退**（[部署历史](https://docs.coze.cn/guides_deployment_history)、[限制](https://docs.coze.cn/guides_vibe_coding_limit)）。
- **「这轮改了什么」没有独立摘要卡，是三通道分流**：小白 = 预览直接看效果 + 文字总结；进阶 = 文件树 M/U 角标 + 编辑器 diff；开发者 = Git 面板/版本历史/分支 diff。

### 支撑能力

- **每项目独立云端沙箱**：含 OS/依赖/资源，支持实时预览；磁盘限额（网页/移动 3GB，工作流/智能体 1GB），1 小时无操作自动回收，每用户同时开 10 个预览（[限制](https://docs.coze.cn/guides_vibe_coding_limit)）。
- **任务边界 = 一轮对话**：「从提交指令到 AI 完成任务并返回结果的完整过程」，计费与补偿均以轮为单元；积分 = LLM + 虚拟机（文件处理、**浏览器自动化**、代码执行）+ 第三方 API。
- **运行时反馈回灌**：前端控制台日志与服务端输出落盘「以供扣子 AI 读取并自动修复问题」；预览报错可复制进对话；资源占用高时「释放容量」自动加载诊断技能。
- **Agent 自验链路**（官方配图证实）：自动写测试用例 + 一轮单元测试；构建脚本 → nohup 启动 → sleep 5 → curl 自检 → curl 打 API 验证——结束帧「自检清单 ✅」即这些自检结果的渲染。
- **git 全程承载状态**：每轮 auto-commit → 版本卡片/版本历史/编辑重发另起分支「免费获得」；worktree 实现多分支独立预览；合并由 Agent 调技能执行。
- **技能系统按需加载**（界面显示可加载 22~23 个：原型设计 design-canvas、开发环境问题诊断等）+ 用户可传技能包；Agent 决定何时加载。
- **部署供给**：一键部署 `*.coze.site`（可改前缀即时生效）；默认 1C2G×2 实例×100 并发、可缩容至 0；部署页实时看进展与日志、可取消。

**因果链（condensed）**：服务端把 agent 循环的每个工具调用推成独立事件 → 「解说+操作卡」混排直播而非黑盒等待｜常驻沙箱 + 自验可自动执行（单测/构建/curl）→ 收口帧能带「自检清单 ✅」信任帧 + 分阶段验收点｜每轮 auto-commit → 版本卡片/回滚内嵌对话流，对话树与版本树同构｜轮级计费 → 服务端以「轮」为事务收口，收口帧（总结+自检+版本卡+积分）即事务渲染｜日志落盘供 AI 读取 → 「自动识别故障+一键修复」有数据来源。

**未证实**：海外版（coze.com）编程项目过程可见性（登录墙）；Diff 视图视觉形态（文档有承诺、配图未证实）；浏览器自动化是否实际用于生成后的 UI 自检（仅计费因素提及）。

---

## 3. Kimi 网页 / Agent 模式（月之暗面）

### 过程呈现形态

- **官方工作循环五步**：任务规划（拆子任务）→ 工具调用（20+ 种）→ 自主执行 → 错误处理（「无需用户介入即可自行纠错」）→ 交付；使用引导「清晰描述任务，**查看执行进度**，然后下载或分享结果」（[Agent 概览](https://www.kimi.ai/zh-hans/help/agent/agent-overview)，一手）。
- **建站由 webapp-building SKILL（多轮代码生成 Agent）六阶段自主完成**：需求解析→任务规划→技术方案→素材生成→代码构建→**多轮优化：「根据预览结果自主调整，直到网站完成」**（[Kimi 网页概览](https://www.kimi.ai/zh-hans/help/websites/websites-overview)，一手）。
- **OK Computer 时期的过程粒度**（二手·可信度高，量子位/智东西实测有截图）：开工先列 **Todo List**（「'x' 表示已完成，'-' 表示正在进行，可以供用户查看任务进程」）；「Kimi 会列出其进行的每一步操作，包括使用数据源、使用文件、使用 iPython、使用部署工具」；布局左聊天框 + 右「虚拟电脑」（[量子位](https://www.qbitai.com/2025/09/337099.html)、[智东西](https://m.zhidx.com/p/506749.html)）。
- **K2.6/K3 时期**（二手·中高，腾讯新闻转载知乎实测）：「执行过程中，可以看到 Agent 对任务的拆解、页面结构的规划、前后端模块的推进，以及问题修复和验证的完整过程」；官方宣称能力「能直接落到**可预览的页面、可查看的数据库和可追踪的执行过程**上」。
- **Agent 自检时用户看什么**：K3 自检机制为「**在代码与实时截图之间无缝迭代，实现真正的视觉闭环（vision in the loop）——即时看见输出，即刻优化**」（[K3 发布文](https://www.kimi.com/news/kimi-k3)，一手）——agent 侧用截图自评；用户侧看步骤/进度 + 每版本一张版本卡片（见下）。
- **未证实**：预览是否逐文件/逐帧热刷新——官方口径「网站生成后，右侧会打开预览面板」，呈**按版本切换**而非流式刷新；刷新频率/延迟无公开资料。

### 信息架构

- **左对话 + 右预览双栏**；OK Computer 时期右栏是「虚拟电脑」画面，现为预览面板：「网站生成后，右侧会打开预览面板，对话中也会出现版本卡片」（帮助中心，一手）。
- **预览面板工具栏**（官方表格，从左到右）：**预览/代码**（可视化预览 ↔ 源代码/文件树切换）、编辑、发布、分享、全屏预览、**切换预览模式**（桌面/移动视图）、刷新、用户反馈、关闭。
- **模式切换**：kimi.com 侧栏「新建会话 → PPT/网站/文档/深度研究/表格/Agent 集群」+ 独立入口 Kimi Code、Kimi Claw；K2.5 起四模式统一（快速/思考/Agent/Agent 集群）（[量子位](https://www.qbitai.com/2026/01/373117.html)，二手）。
- **对非技术用户暴露度**：默认语言是「把导航栏改成深色风格」式自然语言 + 可视化圈选标注；代码/文件树是「预览/代码」一键切换的**可选层**；帮助中心明示「非技术用户：不需要懂代码」；数据库有可视化面板（查看/编辑/删除）。

### 迭代变更呈现

- **版本卡片**（一手）：对话中出现「网站名称、版本号（如 V2）和 URL。点击**预览**可打开对应版本；点击**恢复**可回滚到该版本」；「全部文件」卡片可查看/下载完整项目文件；「多轮编辑：支持通过多轮对话持续修改，**并可对比不同版本**」「按版本预览：点击不同部署版本，并下载对应版本的文件」——对比的具体形态（并排截图？diff？）**未证实**。
- **版本底层**：「AI 会自动创建版本提交（git commit），并支持回滚到任意历史版本」——版本卡片即 git 快照的 UI 化。发布后分享面板「有新版本时可点击**更新发布**」。
- **可视化编辑（visual edit，K2.5 起主打，一手）**：标注模式（点网页任意处 + 自然语言评论）/ 选择模式（选中元素加评论），配矩形/箭头/画笔工具；「在多个位置添加标注后，将它们加入对话，**一次性把所有建议发送给 Kimi**」。实测（二手·量子位）：截图圈出播放器主体 →「把这部分放到左下角」→ 2 分钟出修改，其余不变——「像在用绘图软件涂改一样直观」。

### 支撑能力

- **编排**：webapp-building SKILL 六阶段多轮循环；Agent 通用循环（规划→工具→自纠错→交付）。**视觉闭环自检**：K3 原生视觉 + 截图工具，自检不依赖外部 VLM。
- **沙箱/环境**：「全栈预览：支持完整的前后端预览；**部分场景需要手动启动沙盒环境**」（按需启动）；平台托管云数据库与 Kimi 账号登录（导出代码不含这两个托管件）；一键发布 `*.ok.kimi.link`。
- **版本/快照**：每轮自动 git commit + 回滚 + 按版本下载。
- **多智能体（Agent Swarm，一手）**：orchestrator 调度最多 300 子 Agent、单任务 4000+ 工具调用、提速约 4.5 倍；**上下文分片**——子 Agent 各记「笔记本」，仅关键结论上报 orchestrator（[Agent Swarm 帮助页](https://www.kimi.ai/zh-hans/help/agent/agent-swarm)、[官方博客](https://www.kimi.com/blog/agent-swarm)）。
- **模型-框架协议约束（对事件协议设计的直接参考）**：K3 要求「后训练全程使用思考历史保留模式，agent 框架未按要求回传全部历史思考内容……可能引发上下文干扰」——harness 必须回传完整思考史（K3 发布文「局限性」）。
- 长程执行：K2.6 实测连续 12-13 小时、4000+ 次工具调用（[钛媒体](https://www.tmtpost.com/7960805.html)，二手）。

**因果链（condensed）**：端到端 RL 的原生 agentic 能力（20+ 工具）→ 每步工具调用成为可渲染事件 → 「步骤列表 + Todo 打勾 + 可追踪的执行过程」｜K3 原生视觉 → agent 自己「看」截图迭代（vision in the loop）→ 用户看到「多轮优化/自主纠错」过程条目而非黑盒等待｜沙箱全栈环境 + 托管数据库 → 预览是真站点（才有桌面/移动切换、全屏、分享这套浏览器式工具栏）｜每轮自动 git commit → 变更单位是「版本」而非 diff hunk，回滚 = git revert 产品化｜原生视觉 + 元素定位 → 「圈选 + 一句话」取代文字描述差异，降低用户表达变更的成本。

**未证实**：前端事件协议粒度（SSE/WebSocket 事件类型）；预览渐进刷新实现方式（HMR？每版本重建？）；行级 diff 视图是否存在；「对比不同版本」的具体 UI。

---

## 4. 横向速览（与主对标票同构）

| 维度 | Replit Agent（Agent 4） | 扣子编程（code.coze.cn） | Kimi 网页（Agent 模式） |
|---|---|---|---|
| **过程呈现形态** | 解说流 + 文件实时编辑 + webview 边写边刷；自测=内嵌真浏览器光标直播+可回放录像；看板任务卡 | 解说流 + 可折叠操作卡（命令原文/计划/思考）；无进度条无 TODO 勾选；首轮整段构建后出预览，过程靠对话卡直播；收口带自检清单 ✅ | 步骤列表 + Todo 打勾（OK Computer 起）；K3 截图自检闭环；预览按版本切换出（非流式刷新，未证实） |
| **信息架构** | 双栏 Agent pane + Preview；模式在输入框（Plan/Build）；代码藏 Tool dock 默认不见 | 三栏对话/文件树/工作区；预览=工作区标签页；Agent/问答/计划三模式；移动端零代码区 | 左对话右预览；预览/代码一键切换可选层；侧栏六模式；可视化数据库面板 |
| **迭代变更呈现** | checkpoint（AI 描述+变更范围）+ 自动 git commit + File History 回放；回滚双向全状态 | 版本卡片内嵌对话流（SHA+commit msg+回跳）；版本历史面板；diff 文档承诺但形态未证实；对话树=版本树 | 版本卡片（版本号+URL+预览/恢复）；可对比版本（形态未证实）；圈选标注+一句话批量提需求 |
| **支撑能力** | 自测子 agent（Playwright+notebook 环境）；全状态 checkpoint；隔离副本+fork 合并；plan-while-building | 每项目沙箱；轮=事务边界（计费/收口帧）；日志落盘回灌 AI 自动修复；git 全程承载；技能按需加载 | webapp-building SKILL 六阶段；K3 vision-in-the-loop 截图自检；自动 git commit；Swarm 上下文分片 |

---

## 5. 对照本平台（现状基线，供 #63/#64 裁决票取用）

**我们现有**（正本：`aiplatform-server/docs/spec/SSE事件清单.md`，#41 文档归位后以新路径为准）：`live-text/live-action/live-step` 解说流（智能体自述为主 + 工具动作人话模板，**思考与 diff 不播**）；`dispatch-stage` 阶段状态条；`preview-updated` 渐进预览（刷新单元 = live-step 步骤边界、平台探活通过才发、前端节流重载）；问答卡挂起/续跑；直播收起即逝、无历史。

**三家同构物（判断尺：行业都有 → 低配标配该升级）**：

1. **边做边出的过程帧**：三家都有（对话流内过程直播）——我们有，但形态最轻：纯文字解说流 vs Replit 看板任务卡 / 扣子可折叠操作卡（含命令原文、计划更新）/ Kimi 步骤+Todo 勾选。
2. **预览 = 沙箱内真实应用**：三家一致——我们一致。差异在刷新时机光谱：Replit 边写边刷（同一 workspace 常驻 dev server）> 我们（步骤边界探活刷新，介于 Replit 与扣子之间）> 扣子（首轮整段构建后才出）> Kimi（按版本切换）。
3. **「这轮改了什么」有版本层**：Replit checkpoint 三轨、扣子版本卡片+版本历史、Kimi 版本卡片——**三家全部寄生在每轮自动 git commit 之上**，且都把「回到该版本」做进对话流。我们完全没有（无版本卡、无回滚入口、无 diff 呈现）。这是三家最一致的缺口对照。
4. **Agent 自检且把自检结果播给用户**：Replit 浏览器光标直播+测试摘要+回放、扣子自检清单 ✅+分阶段验收点、Kimi 截图视觉闭环+「问题修复和验证的完整过程」——三家都有「agent 自己验、用户看得见验了什么」。我们无自检环节。
5. **代码渐进披露**：三家一致「可降级为不可见，而非删除」（Replit 藏 Tool dock / 扣子文件树默认收起+移动端零代码 / Kimi 一键切换可选层）。
6. **中途意见并入不打断过程**：Replit Message Queue（work loop 边界、可排序、Pause 硬打断）、我们已有排队合并（同款思想）、Kimi 批量标注一次发送、扣子编辑历史消息重发（版本树另起分支）。

**判断尺的应用**（升级 vs 质疑概念）留给裁决票 #63/#64；本票只供证据。

---

## 6. 信源索引与可信度

- **Replit**：全部一手（docs.replit.com 各页 + replit.com/blog + changelog，见文内链接）+ ProductHunt 官方人员评论（一手·官方人员）；论坛 Autonomy Selector 帖正文不可得（二手转述，已标注）。
- **扣子编程**：docs.coze.cn 官方文档原文与官方配图图像分析（一手）；产品沿革经二手交叉验证（已标注）。
- **Kimi**：kimi.com / kimi.ai 帮助中心 / K3 官方发布文（一手）；OK Computer 与 K2.5/K2.6 过程细节为媒体实测（量子位、智东西、腾讯转载知乎、钛媒体——二手，可信度中高、多有截图佐证）。
- 三份取证原始报告（本快照的底稿）：`/tmp/wf-relay/replit-agent.md`、`/tmp/wf-relay/coze-kouzi.md`、`/tmp/wf-relay/kimi-web.md`（临时转发件，不入库）。
