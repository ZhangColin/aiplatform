# 调研报告：AI app builder 的「访谈-构建模式」——Lovable / Replit / v0 / Bolt / Create（Anything）

> 票 #447（wayfinder 决策票调研），2026-11-11。
> 方法：以官方文档 / 官方博客 / 官方 help center 为一手来源逐产品取证（访问日期均为 2026-11-11）；
> 每个 claim 标注 URL；找不到一手证据处明确标注「未找到一手证据」并声明是推断。
> 注：Create 已更名 Anything（create.xyz → anything.com），本报告以 Anything 指代，URL 用新域。

## 结论速览

| 产品 | 一句话→构建：问几个问题？ | 推荐答案形态 | 未确认信息处理 | PRD/规格位置 | 首版可交互目标 | 等待耐心设计 |
|---|---|---|---|---|---|---|
| Lovable | **默认零问题直接开建**；设计方向不明时插一张问题卡（**≤4 题**），可逐题/全部跳过 | 选项卡：色板/字体样张/布局线框图，**预选第一项**＋自由文本框 | 问题卡「instead of guessing」；跳过＝按合理缺省做**且不做额外工作**；plan 显式列 assumptions | Plan mode 产结构化 plan（Plan view 文档），可编辑/逐段批注/Approve/Skip；默认路径不产 PRD | Quick start 口径「**约 10 分钟**从首 prompt 到可分享 URL」 | 问题一次给全有上限；选项可点选免打字；三模式（Chat/Plan/Build）随时切换 |
| Replit Agent | 自适应连续追问（「questions will vary with your idea」），无固定题数；Plan mode 可选 | 对话式提问为主，未见选项卡形态一手证据 | Agent 自主补基础设施（库/鉴权）；教用户写 boundaries 推迟范围；**「I'll assume X」式显式假设声明未找到一手证据** | Plan mode 产**有序任务清单**（非长文 PRD）；Chats 可产可编辑 workbook | 官方教程口径「**10 分钟**建成并改进小应用」；Free Mode 首构建「a few minutes」 | 构建中可插队 steer / queue messages；checkpoint 随时回滚；边构建边聊 |
| v0 | **无访谈门**：简单项目直接生成，复杂项目建议先让它写 PRD；问题数不适用 | 无问题推荐形态；有 prompting 模板/「Specify and make v0 clarify」建议 | 沙箱自主执行＋自动修错；显式假设声明未找到一手证据 | PRD 是**迭代产物**（起草→评审→修订→定稿），非必经确认门 | 未找到分钟数承诺一手证据（营销语「from prompt to production」） | 构建中实时呈现：进度指示、浏览器截图、工具执行卡、stop/auto-continue；版本 diff/restore |
| Bolt | **默认直接 Build**（首页 prompt → Build now）；Plan Mode 可开局先谈后建 | **Quick action 按钮**（Implement this plan 一键切 Build / Show an example / Refine this idea）；Enhance prompt＝答几题生成推荐 prompt | 从首 prompt **静默补齐**（自动识别并建数据库）；省 token 是 plan-first 的动机包装 | Plan Mode 在聊天框给计划文本（非独立 PRD 文档），可在聊内改 | Quick start 口径「**几分钟出可用应用**」，含数据库约 **5 分钟** | Plan-first 被包装成「省 token、避免返工」的经济激励 |
| Anything（Create） | **首 prompt 立即开建**；Plan/Discussion 为用户自选模式，无固定题数 | 无选项卡一手证据；模式选择器（Thinking/Fast/Discussion/Plan/Max） | Discussion 模式聊不改码；「Say what, not how」；显式假设声明未找到一手证据 | Plan mode 产 step-by-step 实施计划后再动手（建议有外部 spec 时别全贴、先进 Plan） | 「From idea to working app in one conversation」，未找到分钟数一手证据 | 每改动皆版本、点任意消息即回滚；live activity 指示 agent 在看什么；构建中可开新 thread 并行 |

**总判断**：五家没有一个采用「固定题数的一次性表单访谈」。共同范式是——**默认快路径直接构建，深度澄清是可选模式（Plan/Chat/Discussion）**；在必须问的地方（Lovable 设计问题），做法是**一次给全、题量有上限、选项可点选且带预选缺省、可整体跳过，并明确「跳过≠批准额外工作」**。PRD/spec 在主流产品里都不是强制确认门，而是可选的规划产物（Lovable/Replit 的 plan 是最接近「PRD 确认」的形态，且都可跳过）。所有产品的「等待」都由构建中的实时呈现（预览逐帧成形、进度卡、截图、checkpoint/版本回滚）消化，而不是靠压缩提问时间。

---

## 一、一句话需求 → 开始构建：问几个问题就动手？固定、自适应还是可跳过？

**结论**：除 Lovable 的设计问题卡外，各家在默认路径上基本不问——一句话进去直接开建。需要澄清时全部是**自适应**（题数不固定、随需求模糊度变化），且全部可跳过或可选。没有任何一家用固定题数问卷。

| 产品 | 证据 | 出处 |
|---|---|---|
| Lovable | 「By default, Lovable still jumps straight into building from your initial prompt」（2025-12-05 官方博客）；设计方向不明时出 question card，**up to four questions**；可「Skip（one question）or **Skip all**」；对话也可反转成访谈（「Before you build anything, ask me the five questions you would need answered…」） | https://lovable.dev/blog/chat-mode-and-questions ；https://docs.lovable.dev/features/projects/chat.md ；https://docs.lovable.dev/tips-tricks/from-idea-to-app |
| Replit | 「Replit asks questions when it needs more context. **The questions will vary with your idea.**」——自适应、无固定题数；「Answer the questions that matter most」暗示可挑答；Plan mode 是可选项（「Enable Plan mode to brainstorm, ask questions…before Agent changes any code or data」） | https://docs.replit.com/home/describe-your-idea ；https://docs.replit.com/features/agent/overview |
| v0 | 无访谈门；官方建议二分：「Simple Projects (Direct Implementation)…works well for single-purpose tools, landing pages, or simple CRUD applications」vs「Complex projects (planning first)」——按项目复杂度选路径，不是按问题清单推进 | https://v0.dev/docs/text-prompting |
| Bolt | 默认首页 prompt →「Build now」直接构建；Plan Mode 为显式开关（「You can begin your Bolt project in Plan Mode right from the first prompt」），可开局先谈后建，也可全程不用 | https://support.bolt.new/get-started/quickstart.md ；https://support.bolt.new/prompting/plan-mode.md |
| Anything | 「The agent starts building **immediately**」（first-app 指南原文）；Discussion/Plan 是用户自选模式，新项目默认 Thinking 模式直接建 | https://www.anything.com/docs/first-app.md ；https://www.anything.com/docs/builder/agent.md |

## 二、是否给推荐答案/默认选项？以什么形态呈现？

**结论**：真正做「推荐答案」的只有 Lovable（选择题卡）和 Bolt（快捷动作按钮 + Enhance prompt）。推荐形态的共同点是**可一键接受**：预选缺省项 / 一键执行按钮。Lovable 是五家中唯一把「推荐」做成结构化选项卡的，且选项用可视化样张（色板、字体样张、线框图）而非纯文字。

| 产品 | 证据 | 出处 |
|---|---|---|
| Lovable | 问题卡「each with answer options, and you can pick an option or **write your own answer**」；设计问题三项各「up to four options」：**Color palette shown as color swatches / Typography shown in font samples / Layout shown as wireframes**；「**Lovable preselects the first option in each question**」（预填推荐）；每个问题下有自由文本框 | https://docs.lovable.dev/features/design-guidance ；https://docs.lovable.dev/features/projects/chat.md |
| Bolt | 「Bolt will likely generate **quick action buttons** at the end of its response: **Implement this plan** (auto-switches to Build Mode to apply changes), **Show an example**, **Refine this idea**」；Enhance prompt：「answer each question…Bolt generates a **recommended prompt, which you can edit**」 | https://support.bolt.new/prompting/plan-mode.md ；https://support.bolt.new/prompting/prompting-effectively.md |
| Replit | 对话式提问，未找到选项卡/预选答案的一手证据（推断：靠对话往返，无结构化推荐形态） | https://docs.replit.com/home/describe-your-idea |
| v0 | 未找到生成前推荐答案形态；提供的是 prompting 模板与示例 prompt 库（「Prompt templates Use these templates as starting points」） | https://v0.dev/docs/text-prompting |
| Anything | 未找到推荐答案一手证据；可点选的是模式/模型选择器，非答案推荐 | https://www.anything.com/docs/builder/agent.md |

## 三、未确认的信息怎么处理：显式假设声明、静默补齐，还是边构建边问？

**结论**：主流是「**边构建边问 + 合理缺省**」，静默补齐集中在基础设施层（数据库/鉴权/部署），业务层语义各家都在避免静默扩展范围。最接近「显式假设声明」的是 Lovable：plan 文档显式列「Key decisions, **assumptions**, and constraints」，且跳过问题时明确「按合理缺省做、**不把跳过当作对建议中额外工作的批准**」。「I'll assume X, tell me if wrong」式内联话术未在任何一家文档中找到一手证据。

| 产品 | 证据 | 出处 |
|---|---|---|
| Lovable | question card 是「pause and show a question card **instead of guessing**」；Skip 语义：「Lovable then continues with **reasonable defaults** for the work you already asked for. It does **not** treat the skip as approval for additional work it suggested」；plan 必含「Key decisions, **assumptions**, and constraints」区块 | https://docs.lovable.dev/features/projects/chat.md ；https://docs.lovable.dev/features/design-guidance ；https://docs.lovable.dev/features/plan-mode |
| Replit | Agent 自动补基础设施：「Agent handles…writing code, **setting up infrastructure, configuring databases**」；首构建教程教用户**自己写 boundaries**（「postpones login, data, payments, and integrations」）把未确认范围显式推迟；「I'll assume…」式声明未找到一手证据 | https://docs.replit.com/features/agent/overview ；https://docs.replit.com/build/your-first-app |
| v0 | 沙箱内自主执行 +「Automatic error fixing…diagnoses and fixes them automatically as part of the generation loop」；假设声明未找到一手证据（推断：以自动修复与迭代兜底，不靠事前声明） | https://v0.dev/docs/agentic-features |
| Bolt | 静默补齐实证：「From your first prompt, Bolt **recognized that your app needed databases and created them for you**」；假设声明未找到一手证据 | https://support.bolt.new/get-started/quickstart.md |
| Anything | 未确认信息靠 Discussion 模式（「talk to the agent without it touching your code」）与「one feature per message」步进式收窄；假设声明未找到一手证据 | https://www.anything.com/docs/builder/agent.md ；https://www.anything.com/docs/prompting.md |

## 四、需求文档/规格（PRD/spec/plan）在流程中的位置

**结论**：五家全部**不强制** PRD 确认门。规格产物的光谱：Lovable（结构化 plan 文档，可编辑可批注，最接近 PRD 确认）→ Replit（有序任务清单）→ Bolt（聊内计划文本）→ v0/Anything（PRD 是可选的迭代规划产物）。规格给用户自己看、给协作者看，但**都可以跳过直接进入构建**。没有任何一家把「用户确认 PRD」作为构建的前置硬门。

| 产品 | 证据 | 出处 |
|---|---|---|
| Lovable | Plan mode「writes a structured plan you can review, edit, and **approve before any code is written**」；plan 含「high-level overview / key decisions, assumptions, constraints / components, data models, APIs / step-by-step sequencing / optional diagrams」；可整篇编辑、选中局部批注修订、版本历史对比；「Select **Skip** to move on **without approving** the plan」；「Give collaborators a written plan to review before the build starts」 | https://docs.lovable.dev/features/plan-mode |
| Replit | Plan mode「generate an ordered **task list**」；计划卡操作：Review / **Revise** / Cancel / **Build here**（批准）/ **Build in background**；首个 checkpoint 之前批准按钮是「Start building」；另：Chats 可产「editable **launch workbook**」（12 个月模型、预算、清单的文档） | https://docs.replit.com/features/agent/plan-mode ；https://docs.replit.com/home/describe-your-idea |
| v0 | PRD 指南定位是「**bridges the gap between product ideas and technical implementation**」的协作草稿：v0 可分解 feature、起草 API spec、设计 DB schema（mermaid ER 图）；推荐 workflow 五步（start broad → generate → review/refine → prompt updates → finalize）——是迭代产物，无「确认后才构建」环节；text prompting 页建议「Build the MVP based on the PRD, starting with core features」 | https://v0.dev/docs/prd-design（经 https://v0.dev/docs/llms.txt 取证，访问 2026-11-11）；https://v0.dev/docs/text-prompting |
| Bolt | Plan Mode「shares a plan **in the chatbox**. You can review this plan, suggest changes, and continue building step by step」——聊内文本，无独立规格视图；quick action「Implement this plan」一键从计划跳到构建 | https://support.bolt.new/prompting/plan-mode.md |
| Anything | 「**Plan** explores your codebase and creates a step-by-step implementation plan **before making any changes**. Good for complex features where you want to review the approach first」；Prompting 指南对已有外部 spec 的建议反直觉：「Have a detailed spec from ChatGPT or Claude? **Don't paste it all in.** Use Plan mode to work out the approach」 | https://www.anything.com/docs/builder/agent.md ；https://www.anything.com/docs/prompting.md |

## 五、第一版可交互系统的目标时间与构建中的体验包装

**结论**：有分钟数口径的三家（Lovable 约 10 分钟、Replit 10 分钟教程/首构建几分钟、Bolt 约 5 分钟）都把「能点到东西」定义在**分钟级**。构建中呈现的标准件：实时预览逐帧成形 + 进度/活动指示 + 截图或 diff + 版本/checkpoint 可回滚。没有任何一家让用户对着黑屏等最终结果。

| 产品 | 时间口径 | 构建中给用户看什么 | 出处 |
|---|---|---|---|
| Lovable | 「Build and publish your first Lovable app in **about ten minutes**, from your first prompt to a live URL」；「The first version takes **a few minutes**, and you can **watch it take shape** as Lovable works」 | 左聊右预览实时运行；每改动存为可 restore 的版本；编辑中可 undo/revert；构建中可发 follow-up 插队（「send follow-ups to steer Lovable while it works」，通常数秒内被拾起） | https://docs.lovable.dev/introduction/getting-started ；https://docs.lovable.dev/features/projects/chat.md |
| Replit | 教程「⏰ Estimated time: **10 minutes**」；「Free Mode may take **a few minutes** for a complete first build」 | 「Follow progress in chat」；Agent 自测 + **checkpoints 随时回滚**；Preview 即开；「Steer or queue messages」构建中插队 | https://docs.replit.com/build/your-first-app ；https://docs.replit.com/features/agent/overview |
| v0 | 未找到分钟数一手证据 | 「**Real-time feedback**: Progress indicators / **Browser screenshots** / Citation links / **Tool execution cards**」；控制：Stop / **Auto-continue** / 每步可见；版本 diff/restore（「inspect a version, view its **diff**, or restore」） | https://v0.dev/docs/agentic-features ；https://v0.dev/docs/versions |
| Bolt | 「you'll have a working app in **just a few minutes**」；含登录/数据库的示例「Wait approximately **five minutes**」 | Preview 窗口即建即点；token 用量可显示在聊内（把成本透明化当作呈现件）；版本历史自动备份可 restore | https://support.bolt.new/get-started/quickstart.md |
| Anything | 「From idea to **working app in one conversation**」，无分钟数 | 中央 preview 沙箱实时运行（移动端带机框 + QR 扫码真机试）；**live activity indicator**（「showing what it's looking at」）；thinking 可逐步展开；Logs 底栏；每消息即版本可点回 | https://www.anything.com/docs/first-app.md ；https://www.anything.com/docs/builder/controls.md ；https://www.anything.com/docs/builder/agent.md |

## 六、如何让用户对「问题什么时候问完」有耐心（或不在意）

**结论**：三条路线并存——(a) **把问题一次给全且有硬上限**（Lovable ≤4 题一张卡）；(b) **先构建后改**，用分钟级的首版兑现耐心（Bolt/v0/Anything 默认路径，问题几乎被绕开）；(c) **构建过程本身可介入、可回滚**，等待被实时呈现稀释（Replit steer + checkpoints）。没有任何一家用「一次性表单」做需求收集；Lovable 的博客明确把这表述为范式转移：「from instant generation to **collaborative creation**」。

| 产品 | 证据 | 出处 |
|---|---|---|
| Lovable | 问题卡一次呈现 ≤4 题、可回头改答案、可整体 Skip；「**Chat before you build**…Think of it as having a planning session with a technical co-founder before diving into development」；「These features represent a shift…from instant generation to collaborative creation」；访谈可自我封顶（「ask me the five questions…」用户自己定题数） | https://lovable.dev/blog/chat-mode-and-questions ；https://docs.lovable.dev/features/projects/chat.md ；https://docs.lovable.dev/tips-tricks/from-idea-to-app |
| Replit | 连续对话式追问但**自适应**（题随 idea 变，不问无关的）；等待期靠 steer/queue（「You don't have to wait…send follow-ups to steer」类能力在 Lovable 也有，Replit 单列 Steer or queue messages 页）+ checkpoint 兜底，把「答错/答漏」的成本降到可忽略 | https://docs.replit.com/home/describe-your-idea ；https://docs.replit.com/features/agent/overview |
| v0 | 绕开问题：按复杂度二分（简单=直接实现），把耐心问题转成「先出东西再聊」；等待期有 progress indicators/screenshots/tool cards 持续供给信息 | https://v0.dev/docs/text-prompting ；https://v0.dev/docs/agentic-features |
| Bolt | Plan-first 的耐心包装是**经济激励**：「save tokens by avoiding unnecessary rework」；生命周期官方口径四阶段「design and plan → first prompt → iterate → deploy」，把「计划」定位成省钱手段而非门槛 | https://support.bolt.new/prompting/plan-mode.md ；https://support.bolt.new/get-started/project-lifecycle.md |
| Anything | 绕开问题：首 prompt 立即建；「One change at a time…You'll move faster and catch problems earlier」把耐心转成步进式小胜利；等待期可新开 thread 并行做别的功能 | https://www.anything.com/docs/first-app.md ；https://www.anything.com/docs/prompting.md |

---

## 对本项目的借鉴

背景：本项目当前旅程为「用户一句话 → 问答卡访谈 → PRD 确认 → 首构建」，面试墙钟约 2 分钟、首构建约 90 分钟。以下 5 条按借鉴价值排序：

1. **把问答卡做成「Lovable 式问题卡」：题量有硬上限、一次给全、每题预选推荐项、可整体跳过，且把「跳过」语义写死为「按合理缺省做、不做额外出范围的工作」。** Lovable 的实证是：问题卡 ≤4 题、每题预选第一项、色板/字体/布局用可视化样张而非纯文字、Skip all 合法且语义明确（skip ≠ 批准额外工作）。本项目问答卡已有「选项 97% 覆盖」（#437 复评），下一步是把覆盖升级为**预选缺省**——用户从「必须作答」变「检查并改」，2 分钟面试墙钟还有压缩空间，同时跳过路径必须显式收窄 scope（对齐 ADR-0036 收口语义，防止跳过被解读为对建议功能的默许）。

2. **深度访谈降级为可选模式，默认走「PRD 草案 → 边构建边确认」的快路径。** 五家无一做强制访谈门；Lovable 明确「默认直接开建、Chat/Plan 留给想要规划时间的用户」，v0 按复杂度二分直接实现 vs 先规划。本项目「问答卡访谈 → PRD 确认」是两个串行硬门，竞品证据支持把它改成：**一句话 → 直接产 PRD 草案卡（含显式 assumptions 区块）→ 用户可一键批准 / 改 / 跳过 → 首构建**；访谈卡作为「我想先聊清楚」的入口存在，而不是唯一入口。这同时呼应 #437 已落地的「提问必须经 ask_user」纠偏链——问题仍走 ask_user，但触发面收窄到「高模糊度且不在 PRD 草案里已声明假设」的情形。

3. **PRD 产物向 Lovable Plan view 看齐：可读文档 + 显式「Key decisions, assumptions, and constraints」区块 + 逐段批注修订 + 版本历史。** 本项目 PRD 确认卡当前是整卡确认/拒绝的粒度；Lovable 的证据是用户最常做的是**选中计划的某一段**说改这里，而不是重写整篇。逐段修订比整篇重派省 token、也比整篇拒绝重访省墙钟——对本项目「首构建 90 分钟里最怕建错方向」的风险，plan 段的局部确认是性价比最高的介入点。这也给 ADR-0032 悬空播报之外的「正常收口」路径提供了 UI 参照。

4. **首构建的等待必须用「构建中呈现」消化，且把首版目标包装成「分钟级先能点」。** 竞品口径：Lovable「watch it take shape / 首版几分钟」、Bolt 约 5 分钟、Replit Free Mode 几分钟；呈现件是标准组合——实时预览 + 活动/进度指示 + 版本可回滚 + 构建中可插队 steer。本项目首构建 90 分钟，用户在「PRD 确认 → 预览 200」之间有 ~90 分钟空窗；参照竞品，至少应保证**骨架预览尽早可点、逐片点亮、每片有 checkpoint 可回**（当前已有分段 run，缺的是面向用户的进度叙事与逐片可点预览），把「什么时候能玩到」从黑盒变成可见的倒计时叙事。

5. **「问题什么时候问完」的耐心不需要靠对话连续追问来换，靠「可跳过 + 可回滚」双兜底。** Replit 靠 checkpoints、Lovable 靠 Skip-all+版本、Anything 靠「点任意消息即回滚」——共同点是**把答错/答漏的事后成本降到接近零**，于是事前问多问少都不再焦虑。本项目已连续追问 9 轮实测（锚项目 11 次 LLM 调用收口），若叠加借鉴 1 的题量上限与预选缺省、借鉴 4 的逐片可回滚，面试可以从「问答卡轮次竞争」转向「一次给全 + 缺省兜底」，与 #445 主档纠偏链（失格重派封顶两轮）在 token 经济上也自洽。

---

### 附：本次取证的一手来源清单（全部访问于 2026-11-11）

- Lovable: https://docs.lovable.dev/features/plan-mode ；https://docs.lovable.dev/features/chat-mode ；https://docs.lovable.dev/features/projects/chat.md ；https://docs.lovable.dev/features/design-guidance ；https://docs.lovable.dev/tips-tricks/from-idea-to-app ；https://docs.lovable.dev/introduction/getting-started ；https://lovable.dev/blog/chat-mode-and-questions
- Replit: https://docs.replit.com/features/agent/overview ；https://docs.replit.com/features/agent/plan-mode ；https://docs.replit.com/home/describe-your-idea ；https://docs.replit.com/build/your-first-app （blog.replit.com 因地域封锁不可达，Agent 3/4 发布细节未取证，以官方 docs 为准）
- v0: https://v0.dev/docs/what-is-v0 ；https://v0.dev/docs/text-prompting ；https://v0.dev/docs/agentic-features ；https://v0.dev/docs/quickstart ；https://v0.dev/docs/versions ；PRD design 页经 https://v0.dev/docs/llms.txt（lastUpdated 2026-11-10）取证，页面直链 /docs/prd-design 返回 404
- Bolt: https://support.bolt.new/get-started/quickstart.md ；https://support.bolt.new/get-started/project-lifecycle.md ；https://support.bolt.new/prompting/plan-mode.md ；https://support.bolt.new/prompting/prompting-effectively.md
- Anything（原 Create）: https://www.anything.com/docs/welcome ；https://www.anything.com/docs/essentials.md ；https://www.anything.com/docs/prompting.md ；https://www.anything.com/docs/first-app.md ；https://www.anything.com/docs/builder/agent.md ；https://www.anything.com/docs/builder/controls.md
