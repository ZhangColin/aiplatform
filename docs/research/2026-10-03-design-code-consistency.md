# 调研快照：设计→代码一致性机制——规范提炼与生成遵守的业界做法（2026-10-03）

> 结论关联：wayfinder #271 调研票，供 #277（裁决：设计稿→系统一致性——设计规范结构与遵守链路）；开图背景 map #267（桥深度已定**规范级起步＝v1 硬要求**、转译级桥出局备案——本快照只收证据不动裁决）。转译级备案触发器的事实面（stitch 导出代码质量是否成熟）由 §1 承载。
> 口径：只看「设计稿→系统长得一致」的机制事实；一手来源（官方 docs/changelog/blog/规范原文/开源源码）为主，二手标注可信度；查无证据判「未证实」，不推测。
> 方法注记：四课题并行取证报告综合而成（其中两课题因调度事故各跑两路，报告互为印证，矛盾处经落稿人源码仲裁）；落稿人对 20+ 处承重引文做了逐字/逐行复核（复核清单见 §9），全部命中。调研日 2026-10-03。

## 结论速览

1. **桥的业界主线已是「规范级」，转译级未成熟且被最强玩家自己放弃**：Stitch 导出＝屏幕级单文件 HTML＋Tailwind、无组件化官方导出，口碑共识「原型起点」（"production-ready is still years away"、"usable but not production-grade"）；Google 2026 起叙事整体转向「**DESIGN.md 规范文件＋MCP/agent 重写**」。map #267 转译级备案触发器两条件（导出质量成熟＋用户真要骨架复用）**均未成立**。
2. **两家同基座玩家的样式机制都是双通道，规范级桥对应「规范通道」**：编辑通道（v0 Design tab／Lovable preview toolbar＝点选消歧＋一轮生成，不承担跨轮约束）与规范通道（v0 Design Systems 2.0／Lovable design systems）分立；v0 官方 FAQ 明文分工 "adjust visuals in Design Mode... **apply your design system for consistent branding**"。
3. **规范通道的共通架构＝「物化＋每轮读＋接地＋扫描纠偏」四件**：规范物化成工作区文件（Lovable `design-system.json`＋rendered rules；v0 skill＋`v0.json`＋starter app），每轮生成必读；接地规则（"If a component, prop, or token **cannot be verified from the sources, v0 should not use it**"）；Lovable 独有实锤＝**每轮 adherence 扫描**（raw color literals／one-off values／inline styles／本地重复实现四类违规）**发现即自动重试**。**业界没有一家只靠提示词做规范级一致**。
4. **token 载体的事实标准＝shadcn 语义 CSS 变量，我方基座已内置该形态**：v0 训练级口径（"specifically trained on the default implementations of the shadcn/ui components"）＋Lovable 检查面（拦截 raw color literals）＋shadcn 官方（"We use and recommend CSS variables for theming"、OKLCH、`@theme inline`）三方一致；基座 `globals.css` 与 shadcn 官方脚手架逐行同构——**桥的落点零新建**。
5. **规范结构的业界双件共识**：机器可读 token（可校验）＋人读规则（组件风格/用法）分离——Google DESIGN.md（YAML front matter＋prose）已开源并配套 `lint`（WCAG 对比度）＋`diff`（token 级回归）CLI，Lovable 是 schema＋rendered rules，v0 是指令蒸馏＋starter；「规范文件自带校验工具」出现生态收敛信号。
6. **「图片→结构化规范」无现成件＝桥中最大空档**：设计稿提炼 token 的工具链全部假定设计住在 Figma/Tokens Studio（variables/DTCG JSON）；截图通道业界直接跳到生成代码（v0 官方免责 "does not guarantee that the style of an attached image will be perfectly replicated"）。我方设计产物是图片态（map #267），**提炼机制需自研**（#277 核心决策点）。
7. **生成侧遵守＝三机制组合使用、无横向量化对比证据**：提示词注入普遍但有官方自认漂移上限（Lovable "may not always be followed consistently"，官方指引＝短条目 bullet）；结构物化（starter/templates；v0 `locked` 是 API 级硬约束，开源 bolt 的文件锁实为提示词级注入）；后处理校验有官方件——**@shadcn/lint（agent-first linter，Tailwind v4 即用、shadcn 非必需，规则面与 Lovable adherence 同款）正对我们基座**；Tailwind v4 另有官方调色板收窄机制（`--color-*: initial` 只暴露语义色工具类）。arbitrary values 无官方禁用开关，lint 补位。
8. **偏差判定分两半**：token 合规（文本 lint 扫描）不依赖浏览器、可先行；「像不像」的自动评分正源＝Design2Code 五维（CLIP 高层相似度＋block/text/position/color 细粒度四维，与人评排序一致）——但产品化止步于 v0 agent-browser 截图自测＋回放呈现（无分数；Lovable 官方自认浏览器自测 "not reliable for evaluating subtle visual design details or color differences"）；像素/感知视觉回归（Playwright/Chromatic/Percy/Applitools）成熟但职责是「同一 UI 前后回归」非语义像不像；**相似度分数 UI 无产品先例**、偏差叠加呈现的成熟形态在 Chromatic Diff Inspector。
9. **模型短板定位支持「结构化喂入」**：Design2Code 实测模型主要输在「**视觉元素召回＋版式生成**」，text-augmented prompting（附结构化文本元素）自动指标显著提升——把设计稿信息结构化再喂生成链路正是打这个短板（输入模态维度的间接证据，非机制横比）。

## 1. Stitch：导出代码的质量口碑与一致性机制

（过程模型/多稿/渐进呈现归 #268，此处只看导出侧。）

### 1.1 导出形态：屏幕级单文件 HTML＋Tailwind，无组件化、无 React/Flutter 官方导出

- 官方 FAQ（2025-06 存档）："**Stitch currently uses HTML and Tailwind CSS for web interfaces. For mobile interfaces, Stitch uses HTMX and Tailwind.**"；官方 SDK（`@google/stitch-sdk` README）对象模型即证据——project → screens → `screen.getHtml()`（每屏一个 HTML）＋`screen.getImage()`（截图），**无组件抽取 API**：'Generate UI screens from text prompts and extract their **HTML and screenshots** programmatically.'。[一手：Wayback 存档 FAQ＋github.com/google-labs-code/stitch-sdk]
- React/Vue/Flutter 导出：全部官方渠道（FAQ/SDK/四篇公告/官方技能库 stitch-skills）**从未提及**；社区反向佐证——`yshaish1/stitch-to-nextjs`（"Pixel-perfect Google Stitch designs to Next.js + Tailwind components - **Claude Code skill**"）这类三方转换件的存在，恰说明官方导出非组件化。第三方教程散见的 "export in React or Vue" 说法判转述失真（见 §8）。
- 粒度＝屏幕级：官方口径 "Watch as your changes update designs in real time…agent streams its work straight to the canvas"（2026-05-19 I/O 2026 公告）同为屏幕级叙事；「导出代码做组件去重/复用」的官方说明查无。

### 1.2 一致性机制的本质：同源渲染（设计＝同一份 HTML 的画布投影），不是转换保真

- 官方 FAQ（2025-06 存档）： "**Since code export keeps the design and code in sync, any visual inconsistencies will be corrected once the changes are applied in the code editor.**"——一致性来自「设计稿与代码是同一份 HTML 的两个视图」＋Code view 双向同步（2026-05 公告："you can edit… Code view, **with live edits synced back to the canvas**"）。**Stitch 没有「独立设计稿→独立代码产物」的转换保真问题，因为根本不做两份产物**——这与我方「设计稿→喂独立生成链路」的桥是不同问题域。
- **theme 落进导出代码的形态（CSS 变量？Tailwind config？写死 class？）：官方从未说明，判未证实**；社区观察指向内联 utility class（Reddit r/GoogleGeminiAI 2025-08："It generates a stream of **'class' soup** and nothing else"，二手中可信）。

### 1.3 theme 机制三阶段演进：Material 选择器 → URL 提取＋DESIGN.md → DESIGN.md 开源规范

- 阶段一（2025 发布）：theme selectors，"Stitch's themes are based on **Material Design**"（官方 FAQ），发布文原话已核（webReader 逐字）："we made sure Stitch offers flexibility… **theme selectors, and a paste to Figma function**"。[一手 developers.googleblog.com/en/stitch-a-new-way-to-design-uis/，已复核]
- 阶段二（2026-03-18 vibe design 公告，已复核）： "**You can easily extract a design system from any URL, or use the new DESIGN.md — an agent-friendly markdown file — to export or import your design rules to or from other design and coding tools.**"＋"Using the recently released **Stitch MCP server and SDK**, you can leverage Stitch's capabilities via skills and tools. Or **export your designs to developer tools like AI Studio and Antigravity**."[一手 blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design/]
- 阶段三（2026-04-21 开源，google-labs-code/design.md，README 已复核）： **DESIGN.md＝YAML front matter（机器可读 tokens：colors/typography/rounded/spacing/component tokens，支持 `{colors.primary}` 式引用）＋markdown prose（设计理由）**，官方口径 "The tokens are the normative values. The prose provides context for how to apply them."；配套 CLI **`npx @google/design.md lint`**（"Validate a DESIGN.md against the spec, catch broken token references, **check WCAG contrast ratios**, and surface structural findings — **all as structured JSON that agents can act on**"）与 **`diff`**（token 级回归检测）；官方发布语："Instead of guessing intent, **AI agents can know exactly what a color is for**, and can validate their choices against WCAG accessibility rules." [一手 github.com/google-labs-code/design.md＋blog.google …/stitch-design-md/]
- **判读：业界最强设计侧玩家（Google Labs）把 theme 的承载物从「应用内选择器」升级为「面向 AI agent 的设计 token 文件＋lint/diff 工具链」——规范提炼侧的路线收敛信号，与我们「规范级桥」同向。**

### 1.4 导出能力演进时间线（全一手）

| 时间 | 事件 |
|---|---|
| 2025-05-20 | 发布（Google Labs 实验、Gemini 2.5 Pro）：theme selectors＋paste to Figma；"**Stitch generates clean, functional front-end code based on your design, so you have a fully functional UI ready to go.**"（已复核） |
| 2025-06（FAQ 存档时点） | HTML+Tailwind（web）/HTMX＋Tailwind（mobile）；"production-ready HTML/Tailwind output"（存档件，见 §8 标注） |
| 2025-11-20 | 官方 MCP 公告 "Translate any design into code with MCP" |
| 2025-12-10 | Gemini 3 in Stitch（"higher quality UI generation"）＋Prototypes |
| 2026-03-18 | vibe design 改版；DESIGN.md 首亮相＋extract design system from any URL；MCP/SDK/skills 生态；export to AI Studio & Antigravity |
| 2026-04-21 | DESIGN.md 草案规范开源＋lint/diff CLI |
| 2026-05-19 | I/O 2026 实时协作：agent 流式直画 canvas；Code view 双向同步 |

### 1.5 代码质量口碑（二手为主，逐条标注）

- **HN（匿名社区·中）**：2026-02 横评帖——"the code is basically **vanilla HTML and tailwind**, which is fun to see but **nothing you couldn't do with Tailwind directly**"；"Stitch always creates 5 screens per project whether you need them or not. And the **'Edit Code' mode just dumps everything into one giant unindexable file**"；"Tried Stitch… **layouts get mangled and texts overlap**"。2026-06 Ask HN："they're **all awful** in my experience"。正面单点（2025-11）集中在**设计视觉质量**（"the designs are quite good"），非导出代码质量。
- **Reddit（DDG 索引转引·中，直连不可达）**：r/GoogleGeminiAI 2025-08——"The built-in editor's HTML+Tailwind view is impressive, but **production-ready is still years away**"；"a stream of 'class' soup"。
- **技术评测（转引·中）**：LogRocket 2025-06（中高）："The code is **just a plain HTML file** with styling done using Tailwind CSS"；buildmvpfast 2026-04：export-to-code "**useful but limited**"；ai-expert.co.uk 2026-06："**usable but not production-grade**"；ai.joaoqueiros.com 2026-07：production usability/accessibility/code quality/rights 仍是待解项。
- **共识画像：导出代码＝原型起点**；主流工作流是 Stitch 出设计 →（Figma 精修或）→ MCP/AI Studio/Antigravity/Cursor 等编码代理**重写**为真代码；「直接把导出 HTML 用于生产」的评价基本缺位。

### 1.6 官方定位口径：曾称 production-ready（2025-06 存档），2026 起叙事整体转向 agent 消费

- 2025-06 FAQ 存档："Are Stitch exports production-ready? **Yes — production-ready HTML/Tailwind output.**"——注意其论证方式是「设计↔代码同源渲染所以不一致会被纠正」，**不是**「代码架构/可维护性达标」。[一手存档件，落稿人本机不可达未能二次复核，见 §8]
- 2025-12 公告仍自称 "our **experimental** AI-powered design tool"；**三篇 2026 公告原文均无 production-ready/production-grade 表述**；官方叙事重心整体迁移——从「导出代码给你」转向「**设计交给 AI agent 消费**」（DESIGN.md agent-friendly、MCP/SDK、skills、export to AI Studio/Antigravity）；官方推荐实战路径（developer diary＋官方技能库 stitch-skills 的存在）即「Stitch 出设计→编码代理重写」。
- **对 map #267 转译级备案触发器的读数：截至 2026-10，「stitch 式导出代码质量成熟」未成立**——口碑未成熟（§1.5），且 Google 自己把重心转向「**规范文件（DESIGN.md）＋agent 重写**」：业界事实上也在走规范级桥而非转译级桥。

## 2. v0：Design tab 是编辑通道，一致性靠「Design Systems 规范通道」

### 2.1 Design tab（现名 Design mode）：改样式单点，不是规范载体

- 正式文档页 `/docs/design-mode`：两通道合一——可视化面板细调（Typography/Color/Background/Layout/Border/Appearance/Content 七组控件）＋自然语言指令（复杂/结构性改动，**自动附选中元素截图**："v0 automatically attaches a screenshot of the selected element along with your instructions"）。[一手，已复核]
- 面板是 **Tailwind/token 感知**的，值域由项目已定义面决定（关键原话）："Design mode detects when your app is using Tailwind CSS and will surface Tailwind-compatible values where appropriate. **Class and token coverage depends on what's defined in your project.**"[一手，已复核]
- 写回机制：Apply → 序列化 → 发进 chat → **生成新版本**（"v0 serializes your edits (and any instructions / screenshots), sends them to the chat, and generates an updated version of your project that reflects the changes in your source code... you can review the diff, keep iterating, or revert just like any other v0 edit"）；改动 pending 暂存，支持 Undo/Redo/Before-after。[一手，已复核]
- 发布口径（2025-06-12，官方社区公告）：Design Mode「Tailwind and shadcn native」、发布时免费不走 LLM（"No need to spend credits or wait for an LLM"）；现行文档已演进为「Apply 产出版本＝普通生成轮」。[一手，community.vercel.com/t/introducing-design-mode-on-v0/13225]
- **定位判定：编辑通道**——只改「这个元素的这些样式」，不产出可复用规范。官方 FAQ 对各通道分工原话："**Iterate**: Add features, adjust visuals in Design Mode, edit code, or **apply your design system for consistent branding**."——一致性职责明给 design system。[一手，/docs/faqs]

### 2.2 一致性底座：固定默认栈＋对 shadcn 默认实现的专门训练

- 默认栈官方口径（多篇一致）："New v0 chats start with a Next.js app that uses `shadcn/ui` and Tailwind CSS."；"using **Next.js**, **Tailwind CSS**, and **shadcn/ui** will help it generate the better results." [一手 /docs/design-systems-2、/docs/screenshots]
- **训练级口径**（legacy registry 文档）："v0 is **specifically trained on the default implementations of the `shadcn/ui` components** and may struggle with any customizations."——「基座固定栈＝一致性底座」的官方自证。[一手 /docs/design-systems]

### 2.3 规范通道现行形态：Design Systems 2.0（规范物化成 skill＋starter app）

「把规范结构化喂给生成器并持续遵守」的 v0 现行官方形态（[一手，/docs/design-systems-2，已复核](https://v0.app/docs/design-systems-2)）：

- 定义："Design Systems 2.0 lets you **teach v0 your design system once**, so chats can build with your real components, tokens, and conventions."；skill 的本质："A design system skill is **not a copy of your docs; it is an adapter** that tells v0 where your source lives, **which components, props, and tokens are safe to use**, and how to wire the system into new apps."
- 导入输入：npm 包（私有可配 NPM_TOKEN）/GitHub 源/**真实消费者应用**（"Often the most valuable source because it shows providers, global styles, fonts, theme setup, and correct component usage"）/Storybook 与 docs 链接/**Figma frames**（"v0 imports a screenshot and extracted frame context so it can use the design as reference"）/截图与 ZIP 附件。
- **验证式导入**："v0 reads them, **builds a starter app to verify it understands your system**, and saves a skill in the current scope"＋人工审核后才保存（四步：discover→starter app→pause for review→save after approve）。
- 落地三件：① `v0.json` 的 referenceWorkspace.sources（只读参考源，上限 3 个）＋ `starter` 字段（`skill-directory`/`v0-default`/`empty` 三种起点；"the file is the **source of truth** for what gets applied when the skill is used"）；② **starter app**（"**Future chats start from this app**, so the agent can work on your prompt without using time and tokens to repeat the setup"——规范以「起手工程」物化，即基座模板化路线；官方反面强调 "A correct starter means every future app begins from a working foundation instead of inheriting the same issue each time"）；③ skill 指令（Figma/docs/附件**蒸馏进指令**而非长期源）。
- **保真硬口径**："v0 grounds itself in the real source. **If a component, prop, or token cannot be verified from the sources, v0 should not use it.**"
- 更新与分发：源变更→更新 skill 并"re-checks the starter app so it doesn't regress"＋Revision History（文件/作者/时间/diff）；可设 team default（"The default is used for new chats when no design system is selected"）；已生成项目不自动迁移（"Existing chats and projects don't automatically rewrite code they've already generated"）。v2 API 同面——design system 以 **skill 形态**随请求传入：`skills: [{ type: 'memory', scope: 'team', skillName: 'acme-ui' }]`（/docs/api/v2/guides/design-systems，含 update 端点）。

### 2.4 前代规范通道：shadcn registry（token 注入的官方标准）

[一手，/docs/design-systems（现标 legacy），引文见 §4.3](https://v0.app/docs/design-systems)。

### 2.5 按图/按稿生成的保真机制（v0 Figma）——「设计稿→系统」的直接同构物

[一手，/docs/figma，已复核](https://v0.app/docs/figma)：贴 Figma 链接 →"v0 can read the full file, find the right pages and frames, and build them as one flow. **It uses your tokens, layout, text, and assets in the code.**" 保真是**三层结构化数据直读**而非只看图：

- **Design tokens and colors**: "v0 uses the **variables and styles** set in your file."（token 直读）
- **Layout and text**: "v0 reads **auto layout, spacing, size, and the exact text** in each frame."（结构数据直读）
- **Frame images**: "v0 **compares the app with each frame as it builds**."（边建边与帧图比对——视觉回归式自校）；另有 Components and styles（读名保持屏间一致）、Assets（图标/图片导出复用）、Dev resources（Dev Mode 链接的代码/Storybook/docs/specs）。
- 截图通道保真弱于 Figma（官方明示："If it's a screenshot, v0 will analyze the layout, colors, and components in the image, then generate code that closely replicates the design."＋保真建议里指向 "Consider using a Figma file for better results"；FAQ 宣传语 "pixel-perfect accuracy" 属营销口径，与 Figma 文档的机制描述对读时按后者采信）。[一手 /docs/screenshots、/docs/faqs]
- 用户级规则通道（Instructions）：可复用提示词规则（"Instructions are reusable prompts you can save to your account and apply on-demand"），属用户侧辅助层，非平台一致性机制；**v0 官方系统提示词未公开**（见 §8）。[一手 /docs/instructions]

## 3. Lovable：visual edits 演进为预览工具条，一致性靠 knowledge＋design systems 双层

### 3.1 visual edits 的身世与演进（时间线校准）

- **发布 2025-02-12**（博客自署，与 changelog 同日条目互证；此前我方 2026-09-03 快照记的 2025-03 有误，此处勘正）：官方口径「Instant Edits: Change text, sizes, and styling on the fly. No Prompt Needed... **Full Control: Apply custom Tailwind classes** for deeper customization."；CEO 原话 "combining AI-powered development with **precise Tailwind-native visual control**"——visual edits 改的是 **style/Tailwind class 层**。[一手 lovable.dev/blog/introducing-visual-edits]
- 演进五步（changelog 全一手）：2025-06-26 面板改版（浮动组合控件）；2025-08-11 v2 增「选中元素**跳到代码位置**」（"jump to selected elements in code... tightens the loop between design and implementation"——编辑与代码位置有映射）；2025-11-26 收进 Design view（"provides a dedicated panel... make your edits directly in the left-hand panel"，控制面与 v0 Design panel 几乎同构：margins/padding/borders/shadows/colors/icons）；**2026-04-02 转向 AI 化**（"Visual edits are now **fully AI-powered** and work across your entire app... You can select and update any UI element visually, without writing code, **even while the agent is running**"）；**2026-06-10 被预览工具条取代**（"The new preview toolbar **replaces Visual edits**... pick a mode on the toolbar and point at what you want to change"）。旧文档页已 404，glossary 明言 "Preview toolbar... **Replaces the older Visual edits panel**."
- 现行形态＝预览工具条四模式：Select elements（点选（可 Cmd 多选）→"attaches to the project chat input as a **reference**"→自然语言描述改动——**点选是给 agent 的上下文引用，改动由 agent 生成**，计 build credits；官方最佳实践 "Lovable already knows what you picked. 'Make this the primary color' is enough."）/ Edit text inline（唯一保留「直接改」的模式，改文案免 prompt）/ Draw annotation（手绘标注清洗后随消息附截图）/ Add a comment。**定位判定：编辑通道**，非规范通道。[一手 docs.lovable.dev/features/preview-toolbar]

### 3.2 一致性第一层：knowledge（纯文本指令，always in context）

[一手，docs.lovable.dev/features/knowledge，已复核](https://docs.lovable.dev/features/knowledge)：

- 两级纯文本（workspace 10k 字符＋project 10k 字符）；"**Knowledge is always included in context**"（对比 skills 按需加载）；仓内指令文件兜底："root-level `AGENTS.md` files are **always read by the Lovable agent** regardless of session length."
- 官方建议纳入面即样式规范，workspace 示例原文含 "Styling - Use Tailwind CSS for styling. - Do not inline styles. - Do not use CSS modules."、"Libraries - **Use shadcn/ui components when possible**."；project 示例含 "Design guidelines - **Follow the existing color palette and spacing scale.** - Use consistent spacing and layout patterns across pages. - Prefer shadcn/ui components when available."
- **官方承认的漂移边界**："in very long conversations with a lot of context, **instructions may not always be followed consistently**."——纯提示词层的诚实上限。
- prompting 官方指南把「风格沉入 knowledge 保多轮一致」写成推荐做法："Once you settle on a style, save its keywords to your project knowledge. Lovable then applies your design direction to every prompt without you restating it, **and new sections stay consistent with what you already built**."；另有 20 个「complete style spec」prompt library（named fonts/hex colors/spacing 规则）——**官方把结构化样式 spec 本身产品化成了 prompt 素材库**。[一手 docs.lovable.dev/prompting/prompting-one]

### 3.3 一致性第二层：design systems（结构化规范＋每轮扫描纠偏——全快照最重的机制级证据）

[一手，docs.lovable.dev/features/design-systems，已复核](https://docs.lovable.dev/features/design-systems)：

- 形态：design system 本身是一个 Lovable 项目，release 生成 `.lovable/` 文件族——`design-system.json`（"the standardized, **machine-readable schema**, including tokens, component catalog (variants, props, examples), and stack constraints. **This is the source of truth.**"）＋`rules/{components,design-tokens,library-guidelines}.md`（由 schema 渲染）＋`system.md`（高层安装说明与设计哲学，人工可编）。
- 应用方式＝file-copy attach：组件拷入 `src/design-system/<slug>/`；"The design system's `.lovable` knowledge files (schema, rendered rules, and `system.md`) are copied to `.lovable/rules/libraries/<slug>/. **Lovable reads them on every generation.**"——**规范物化成工作区文件、每轮生成必读**。
- **Adherence enforcement（生成后扫描＋自动重试，官方原文）**："While you're working in a connected project, **every generation is scanned for violations** of the design system: **Raw color literals** where a design token should be used; **One-off values** that bypass the design system's tokens or scale; **Inline styles** that override the design system's defaults; **Local component implementations** for things that should come from the design system. **When violations are found, Lovable automatically retries to correct them before finishing the generation.**"＋attach 后 setup verification（查 build config/CSS imports/theme providers/依赖并自动修）。
- **token 发现规则**（对桥设计直接可抄的口径）："Tokens are discovered from **top-level styling files**: CSS custom properties in files like `src/index.css` or `src/styles/tokens.css`, a `tailwind.config`, or a `theme`/`tokens` source file."——扫描面＝顶层样式文件的 CSS 自定义属性＋Tailwind config＋theme/tokens 源文件。
- 生命周期：一项目一连；更新走「Update available」确认后重放 attach；摘除后文件留用但"the agent **no longer enforces its rules**"——**遵守是 attach 关系的活语义，不是文件躺在那就算**。2026-08-19 起全付费计划可用；2026-09-21 增 group 级默认。
- **官方自认的局限**（如实）："New projects created from a design system currently scaffold on a **TanStack template**... **Deterministic scaffolding and styling setup are known limitations** being improved."——Lovable 的 design system 起手目前不是确定性脚手架，正是其已知短板。
- 另一条「基座模板化」路线：**Design templates**（Business/Enterprise）——"Templates **copy the full codebase**, including design, components, and structure, **to maintain consistency**"，可设 workspace 默认模板。[一手 docs.lovable.dev/features/business/design-templates]
- 与我方技能线的词汇撞名说明：Lovable「design system skill」与我方「技能」不同物——它是对接外部规范库的适配层，分发走平台 own 的 attach 机制。

### 3.4 前置规范生成器：design guidance（意向→结构化 brief→当 spec 用）

[一手，docs.lovable.dev/features/design-guidance，已复核](https://docs.lovable.dev/features/design-guidance)：新项目起手出三条「design directions」（轻量 HTML+Tailwind 预览）或 design questions 引导选择；**关键机制原话**："After you submit, **Lovable turns your choices into a detailed design brief with named fonts, hex colors, layout approach, and uses it as the spec for the build**."——「从意向提炼结构化规范（具名字体＋hex 色值＋版式方案）再喂构建」的产品化先例；自定义 token/组件仍指向 design systems（"To use custom tokens, components, or guidelines, see Design systems"）；有模板/design system 时自动跳过（"builds using the existing tokens, components, and visual rules from the template or design system"）。

### 3.5 Themes 面板的生死（集中式主题面板路线的证伪数据点）

2025-11-26 上线（"Themes — Set your brand standards once: colors, typography, spacing. Apply them to any project."）；**2026-03-16 changelog Removed 节**："*Themes* have been removed from the *Design* view **due to low usage and performance issues**."——「集中式品牌主题面板」这条独立路线 Lovable 官方试过并撤了，现行一致性职责收敛到 knowledge＋design systems。[一手 changelog]

### 3.6 按图/按稿生成的保真机制（Lovable Figma 三通道）

[一手，docs.lovable.dev/integrations/figma](https://docs.lovable.dev/integrations/figma)：Figma plugin（导出帧/组件，2026-08-21）／Figma MCP（desktop 本地实时读，"inspect component properties, and use that context while building"）／**.fig 文件上传＝token 直读**（"Lovable extracts your **variable collections, colors, typography, and frame structure**... Works well for **passing design system tokens into a project**"，且 "work best when your Figma file uses **local variables**"）。演进注记：2025-01 曾借 Builder.io 做 Figma 导入、2025-11-26 因质量问题下线（"Figma Import has been removed due to quality issues"）、2026 年三通道重建——**「设计稿直转」这条路 Lovable 也翻过车又重来，现行的重心放在 token/结构直读而非像素转译**。另有 Build with URL（"Lovable fetches the page and uses it as a reference for layout, content, and styling"）。

## 4. design token / theme 管线：从设计稿提炼结构化规范的工具与 shadcn/Tailwind 主题化约束

### 4.1 提炼工具链现状：DTCG JSON 是行业数据形态，两侧工具均已支持

- **Tokens Studio for Figma**（活跃：plugin 2.12.1，2026-09-23 发布）：双 token 格式（legacy 默认／**W3C DTCG**——`$value`/`$type`/`$description`）；**GitHub 双向 sync**："We support two-way sync... **the Design Tokens living in code are the source of truth** for our design decisions"；到代码走 `@tokens-studio/sd-transforms`＋Style Dictionary。[一手 docs.tokens.studio]
- **Figma Variables＋Dev Mode**：变量直接产出 CSS 变量引用（示例 `font-weight: var(--Thin-100, 100);`，变量名自动规范化为合法 CSS 自定义属性）；Variables REST API（查询/创建/更新/删除，CI 集成，**仅 Enterprise org**）；**官方 Figma MCP server**："Extract design context: **Pull in variables, components, and layout data directly into your IDE**"，官方点名客户端 VS Code/Cursor/Windsurf/Claude Code/Codex。[一手 help.figma.com＋developers.figma.com]
- **Style Dictionary**（活跃：v5.5.5，2026-09-20；仓库已迁 style-dictionary org，Tokens Studio 团队 2023 起共同维护）：token JSON → 多平台产物；**DTCG 一等支持自 v4**（最新格式 2025.10 支持在 v5 仍是 work in progress）；CSS 输出官方 format＝`css/variables`；无官方 Tailwind 专用 format（Tailwind 侧消费走 CSS 变量，见 §4.2）。[一手 styledictionary.com＋GitHub]
- **W3C Design Tokens 规范**：**仍是社区组草案非 W3C 标准**（"It is not a W3C Standard nor is it on the W3C Standards Track"）；格式快照 2025.10；`.tokens.json`＋媒体类型 `application/design-tokens+json`；基础类型 color/dimension/fontFamily/...＋复合类型 shadow/typography/...。[一手 design-tokens.github.io/community-group]
- 商业一体化：**Supernova 活跃**且叙事已 Agent 化（"Get your design system ready for or built by the agents"，Figma variables 同步、token 变更自动开 PR、MCP servers）；**Specify 已转型**（现为 GitHub 开发者文档自动化，离开 token 赛道）。[一手官网]

### 4.2 shadcn/Tailwind 主题化：官方机制与我们基座逐项对上

**shadcn theming**（[一手 ui.shadcn.com/docs/theming，已复核](https://ui.shadcn.com/docs/theming)）：

- 官方口径＝CSS 变量： "**We use and recommend CSS variables for theming.** This gives you **semantic theme tokens** like `background`, `foreground`, and `primary` that components use by default. Override those tokens in your CSS to change the look of your app **without rewriting component classes**."
- 语义 token 成对约定（primary↔primary-foreground 等）；现行值形态＝**OKLCH**（`--primary: oklch(0.205 0 0);`）；token 住 `:root` 与 `.dark`；**Tailwind v4 映射＝`@theme inline`**（官方脚手架 `@import "tailwindcss"; @import "shadcn/tailwind.css"; @custom-variant dark...; @theme inline { --color-background: var(--background); ... }`）——**与我方基座 globals.css 逐行同构**（基座现状见 §7）。
- 加 token 三步：`:root`/`.dark` 定义 → `@theme inline { --color-warning: var(--warning); }` 暴露 → 用 `bg-warning`；备选 `cssVariables: false`（安装期选择，切换需重装）；baseColor 走 components.json 的 `tailwind.baseColor`（现行集合 Neutral/Stone/Zinc/Mauve/Olive/Mist/Taupe）；官方可视化工具 **shadcn/create**（预览 colors/radius/fonts 生成 preset）。v3 的 `hsl(var(--background))` 属 legacy 对照（迁移文档口径 "Use the OKLCH color format"、"`@theme inline` was introduced in Tailwind CSS v4"）。

**Tailwind v4 约束机制**（[一手 tailwindcss.com/docs/theme v4.3，已复核](https://tailwindcss.com/docs/theme)）：

- `@theme` 本质： "**Theme variables aren't just CSS variables — they also instruct Tailwind to create new utility classes.**"；分工原话： "**Use @theme when you want a design token to map directly to a utility class, and use :root for defining regular CSS variables that shouldn't have corresponding utility classes.**"——shadcn 语义变量放 `:root`、`@theme inline` 只做映射，正是该分工的标准实践。
- **「限制调色板到 token 集」有官方机制＝namespace 覆写**：`@theme { --color-*: initial; --color-midnight: #121063; }` →"**all of the default utilities that use that namespace (like `bg-red-500`) will be removed, and only your custom values (like `bg-midnight`) will be available.**"；更激进 `--*: initial;` 可整体关默认主题——**「只暴露语义色」是 Tailwind v4 一行配置级可行**（配 shadcn：清掉默认 `--color-*` 后只剩 `@theme inline` 暴露的语义色工具类）。
- **arbitrary values：官方定位是逃生舱、无官方禁用开关**（"once in a while you need to break out of those constraints"）；强制约束只有 lint 层（@shadcn/lint／eslint-plugin-tailwindcss，见 §5.3）。

### 4.3 shadcn registry：「规范结构化喂给 AI 生成器」的业界标准通道

[一手 ui.shadcn.com/docs/registry](https://ui.shadcn.com/docs/registry)：

- 定义（v0 legacy 文档的表述最直接）： "**A registry is a distribution specification designed to pass context from your design system to AI Models.**"；"It lets v0 generate prototypes that **match your design system, without manual overrides**."
- registry-item.json schema：`type` 枚举含 **`registry:theme`/`registry:style`**/ui/block/lib/hook 等；`tailwind.config` 字段可内嵌 Tailwind 配置——**主题/配置随 registry 件结构化分发**。
- **Open in v0**：registry 件一键送进 v0（URL 携 registry 与 item 名），官方用途："this opens a v0 chat with the custom theme information prefilled, **allowing AI tools to generate components using your custom theme**"。
- **MCP server**："works out of the box with any shadcn-compatible registry"——AI 客户端（Claude Code 等）经 MCP 搜索/拉取/安装 registry 件；另有官方 Skills（`pnpm dlx skills add shadcn/ui`，"Deep shadcn/ui knowledge for AI assistants like Claude Code"，含 theming/registry authoring 章节）与 llms.txt（自述 "a code distribution platform... **AI-Ready**"）。
- Figma 侧：官方 Figma kit 均为社区贡献（含 "Native Figma variables for all eight shadcn/ui styles" 的免费 kit）——Figma 变量↔shadcn 主题的桥接目前住在社区层。

### 4.4 「设计稿→结构化规范」的提炼实践与空档

- 责任分界成熟口径（一手互证）：**token JSON 落代码库为唯一事实源**（Tokens Studio "source of truth"／Figma API "Sync design system source of truth to and from Figma"）；链条＝Figma（设计侧编辑）→JSON（DTCG）→代码库→Style Dictionary/构建（多平台产物）。消费侧责任原则＝v0 的接地口径（"cannot be verified from the sources → should not use it"，§2.3）。
- **「从图片/截图自动提炼 design token（输出结构化 token 文件）」的专用工具：未证实存在**——已证实的最接近能力是 v0 截图→直接生成代码（非 token 输出，且官方免责 "v0 does not guarantee that the style of an attached image will be perfectly replicated"）；主题侧已知的是可视化编辑器（官方 shadcn/create、社区 tweakcn——v0 文档官方点名 "utilize free, third-party tools such as tweakcn.com"）。**这正是「设计稿→规范」桥里业界尚无现成件的一段**。
- 设计系统知识侧：zeroheight 活跃（"Design System Platform for Teams **and AI Agents**"，MCP 口径 "gives agents current design system guidance as they work"）。[一手官网]

### 4.5 AI 生成侧消费规范的官方做法（v0/shadcn 之外，全部官方 docs）

- **Cursor**（cursor.com/docs/context/rules）：四类规则（.cursor/rules/*.mdc／User／Team／AGENTS.md），"rule contents are **included at the start of the model context**"，应用模式 always/智能/glob/手动；最佳实践 "**Reference files instead of copying their contents**"。
- **Claude Code**（code.claude.com/docs）：CLAUDE.md（project/user/org）＋`.claude/rules/`＋AGENTS.md；skills＝SKILL.md 按需加载——官方鼓励把大块参考从常驻记忆拆成 skill（"a skill's body loads only when it's used, so long reference material **costs almost nothing until you need it**"）。
- **Windsurf/Devin**（docs.windsurf.com）：`.devin/rules/*.md`（frontmatter `trigger: always_on/model_decision/glob/manual`）；工作区规则限 **12,000 字符/文件**；AGENTS.md root＝always-on。
- **GitHub Copilot/VS Code**（code.visualstudio.com docs）：copilot-instructions.md/AGENTS.md/CLAUDE.md 常驻＋`.github/instructions/*.md` 按 glob 应用——官方 monorepo 示例即 `.github/instructions/style.instructions.md`。
- **Figma MCP→Claude Code**：官方 help 有专文设置指南——设计工具与编码 agent 的 token 级直连已是官方产品面。

## 5. 生成侧遵守三机制：提示词注入 vs 基座模板化 vs 后处理校验

### 5.1 机制一：提示词注入（规范文本常驻上下文）——业界普遍采用、效果有官方自认的上限

- **Lovable knowledge**（详见 §3.2）：always in context＋官方示例即样式规范句；**官方写法指引**："Short instructions work better than long paragraphs. Prefer bullet lists and direct rules."（反例 "Write clean code."）；**官方自认漂移**："in very long conversations with a lot of context, instructions may not always be followed consistently."——提示词层效果上限的罕见官方自证。[一手，已复核]
- **v0 Instructions**：形态不同——用户级可复用规则、按需勾选（"Checked instructions will be applied to your messages until you uncheck them"），**非恒定注入**；v0 官方 prompting 指南只把设计偏好定性为 prompt 内容问题，无「每轮强制遵守」声明。[一手 /docs/instructions、/docs/text-prompting]
- **bolt.diy 系统提示词（一手源码，本快照唯一的生成器提示词全文证据，已逐行复核）**：`app/lib/common/prompts/prompts.ts` 内含 `<design_instructions>` 段——默认层是通用设计约束（"Color system with a primary, secondary and accent, plus success, warning, and error states"、"Ensure consistency in design language and interactions throughout."）；**结构化注入层**是 `<user_provided_design>` 段："**ALWAYS use the user provided design scheme when creating designs**... FONT: ${JSON.stringify(designScheme?.font)} COLOR PALETTE: ${JSON.stringify(designScheme?.palette)} FEATURES: ${JSON.stringify(designScheme?.features)}"，配套 `app/types/design-scheme.ts` 的 `DesignScheme = { palette（语义角色键 primary/secondary/accent/background/surface/text/border/success/warning/error，每键带用途说明）, features[], font[] }`——**「设计 token 结构化后注入系统提示词」的开源产品级先例**；另移动端段有美学形容词指令（"All apps must be visually stunning... not cookie cutter"）。[一手源码，已复核]
- **判读**：无外部 designScheme 输入时，提示词只剩通用色彩学＋美学形容词——形容词无法保证与具体设计稿一致；bolt 的答案是「用户可选的结构化 scheme 注入」。规范级桥要补的正是把设计稿变成那个 scheme。
- v0/Lovable 官方系统提示词均未公开；流传的 v0 泄露版恰好含「**禁任意值、禁裸色、一切走 token**」条款（"Prefer the Tailwind spacing scale instead of arbitrary values: YES `p-4`, NO `p-[16px]`"、"DO NOT use direct colors like text-white, bg-white... Everything must be themed via the design tokens in the tailwind.config.ts and globals.css"、"ALWAYS use exactly 3-5 colors total"、"limit to maximum 2 font families"）——**泄漏存档件、官方未确认，二手中等可信，本快照仅备案不采信为证据**（见 §8）。

### 5.2 机制二：基座模板化＋结构性锁定——业界已产品化且零件齐全

- **v0 Design Systems 2.0 的 starter app**（详见 §2.3）：规范物化成起手工程（"Future chats start from this app"），`v0.json` 的 starter 三型（`skill-directory`/`v0-default`/`empty`）；API 侧官方自分两档——严格强制用 `skills` 参数（`type: 'memory'`），"**If strict enforcement is not needed, omit `skills` and mention the design system in `message`**"。[一手 /docs/api/v2/guides/design-systems]
- **v0 File Locks（API 级结构硬约束）**：文件 schema 带 `locked: boolean`（注释原文 `locked: true, // AI cannot modify this file`）、repo/zip 导入有 `lockAllFiles`；官方建议用法即"**Good: Always lock configuration files**"（package.json/tsconfig/vite.config）。社区有锁体验毛刺帖（Vercel Community 2025，二手中可信）。[一手 /docs/api/v1/guides/lock-files-from-ai-changes，已复核字段原文]
- **bolt.diy 文件锁（一手源码，已逐行复核——执行层证据两代理报告矛盾，落稿人源码仲裁）**：锁状态在 `app/utils/fileLocks.ts`＋`lockedFiles.ts`（localStorage 持久化）＋`LockManager.tsx`（UI）；**LLM 侧执行是提示词级注入**——`app/lib/.server/llm/stream-text.ts` 在系统提示词尾部追加："IMPORTANT: The following files are locked and **MUST NOT be modified in any way**... DO NOT make any changes to these files specifically"；而 `app/lib/stores/workbench.ts` 的 AI 写入路径**没有锁拦截**（源码注释自认："For scoped locks, we would need to implement diff checking here... a more complex feature that would be implemented in a future update"）。**判读：开源 bolt 的锁是「提示词软约束＋UI 状态」，不是写路径硬阻断**——与 v0 API 的 `locked` 字段（平台侧结构性执行）形成对照；bolt.new 商业版 Lock file 的官方文档页未找到（二手教程口径，见 §8）。
- **Lovable Design templates＋基座模板**："Templates **copy the full codebase**, including design, components, and structure, **to maintain consistency**"，可设 workspace 默认模板（Business/Enterprise）；且 Lovable 本身全项目从统一基座模板起步（React+Vite → TanStack Start，"It became the default for new projects on May 13, 2026"）——与我方「基座工程」同型的官方先例。[一手 docs.lovable.dev/features/business/design-templates、/features/upgrade-to-tanstack-start]
- **「主题文件锁定＋代码只引用 token」的整装先例：无**。零件全部成熟（v0 的 locked 文件、DS 2.0 的 starter＋tokens skill、@shadcn/lint 的 token 白名单规则），但无任何产品以该组合为名做官方口径——v0 lock 指南的示例全在配置文件保护，未把「锁 theme/tokens 文件」列为用例。**判「机制零件成熟、整装组合属空档」**。

### 5.3 机制三：后处理校验（lint/静态扫描）——现成件齐全，官方产品化接入刚起步

- **@shadcn/lint（一手 README/npm，已复核——与 Lovable adherence 检查面几乎逐条对上的官方件，且正对我们基座）**：官方定位 "**Write design system rules that agents can verify.**"——"an **agent-first linter** for Tailwind design systems"；"You define what's allowed. When an agent breaks a rule, **the error explains what's wrong and suggests a fix based on your components, variants, and theme**"；**Tailwind v4 项目即可用（shadcn/ui 非必需）、ESLint 与 Oxlint 双通道**；Quickstart 甚至直接给「交给 coding agent 装配」的提示词（"Read .../SETUP.md and set up @shadcn/lint in this project"）。规则表＝`no-restyle`（className 重塑组件）／`no-raw-colors`（裸色 `bg-pink-500`）／`no-arbitrary-values`（任意值 `p-[13px]`）／`no-inline-styles`／`no-unknown-classes`／`require-static-classes`——**与 Lovable adherence 检查的四类违规（raw color literals/one-off values/inline styles/本地重复实现）同面**；开发口径 "developed these rules by studying production design systems and **testing them with coding agents**"。[一手 github.com/shadcn-ui/lint，npm @shadcn/lint 0.2.0]
- **eslint-plugin-tailwindcss（一手 README，已复核）**：社区件同级能力：`no-arbitrary-value`（"forbid using arbitrary values in classnames (turned off by default)"）、`no-custom-classname`（"only allow classnames from Tailwind CSS and the values from the `whitelist` option"——**白名单 lint**）、`no-unnecessary-arbitrary-value`（任意值等价于既有 token 即报错）。stylelint 为通用 CSS linter（可配自定义规则），无 AI 场景特殊口径。
- **Lovable design systems 的 adherence 扫描（§3.3）是「样式合规扫描接进生成循环」的唯一官方产品化口径**——检测项正是 lint 语义，且**发现违规即自动重试**（"automatically retries to correct them before finishing the generation"）；FAQ 再证："The adherence scanner catches most violations automatically. If it misses something, point it out in the project chat."
- v0 官方「Automatic error fixing」口径（"When v0 detects errors in your project, it can diagnose and fix them automatically **as part of the generation loop**"）覆盖语法/格式/运行时错误，**未点名样式/token 合规**。[一手 /docs/agentic-features]
- Lovable 内置 lint 接入生成循环：官方全文档 grep 仅命中 knowledge 页**建议用户自己写**的两句示例（"Code quality or linting rules"、"Run the linter after significant changes"）——**判未证实**（搜索摘要里的「Lovable 自动跑 ESLint」无官方页面支撑，疑 AI 摘要推断）。

### 5.4 三机制效果对比的公开证据

- **横向量化对比（提示词注入 vs 模板化 vs 后处理校验）：未证实**——无 benchmark、无工程博客受控实验；各家官方效果宣称全为定性（"maintain consistency"、"working foundation"），无百分比无基线。
- 最接近的公开证据是 **Design2Code 的 prompting 消融**（输入模态维度）：直接截图 vs text-augmented（附抽取的文本元素）——"Text-augmented prompting successfully increases the block-match score and text similarity score on both GPT-4V and Gemini Pro Vision"；self-revision 仅 "minor improvement"。即「**喂结构化素材优于纯图**」有自动指标级证据，「三机制谁强」没有。[一手 arXiv 2403.03163]

## 6. 偏差判定：「像不像」的自动评判与呈现

### 6.1 视觉回归工业标准（成熟，但职责是「同 UI 前后对比」）

四家均为多年商业运营的成熟件（各官网一手定位）：**Playwright** `toHaveScreenshot`（截图断言＋基线管理，生态默认件）、**Chromatic**（"cloud-based toolchain for visual testing, reviewing, and documenting Storybook components"）、**Percy**（"All-in-one visual testing and review platform"）、**Applitools**（Visual AI 感知级比对）。**成熟度结论**：pixel/感知级回归是成熟工业领域，但它们解决「同一 UI 改动前后」，**「生成物 vs 设计稿」的语义偏差判定不在其原生职责内**。

### 6.2 学术侧：Design2Code——「像不像」自动评分的开山基准

[一手，arXiv 2403.03163（Stanford，NAACL 2025），摘要与正文已复核](https://arxiv.org/abs/2403.03163)：

- 任务＝截图→HTML/CSS/JS 还原；484 真实网页；配套**五维自动指标**：高层视觉相似度用 **CLIP embedding 相似度**（CLIP-ViT-B/32），低层元素匹配四维＝**Block-Match 块匹配／Text 文本（字符级 Sørensen-Dice）／Position 位置／Color 颜色**；自动指标用人评校验排序（"We also complement automatic metrics with comprehensive human evaluations to validate the performance ranking"）。
- 结果要点：GPT-4V 最佳；人评「生成页可在 49% 案例替代原页、64% 案例被认为比原页更好」；**模型短板定位**："models mostly lag in **recalling visual elements** from the input webpages and in **generating correct layout designs**"——视觉元素召回与版式是还原瓶颈（对「规范提炼」的价值判断直接相关：把元素/版式信息结构化喂入正是打这个短板）。
- 意义：证明「自动评分判像不像」可行且与人评排序一致；block-match/text/position/color＋CLIP 五维是可直接借用的偏差判定骨架。
- 后续：**「Design2Code-2M」未证实**（查无以此为名的独立论文；2M 规模数据集是 WebSight〔HuggingFace 合成 HTML-截图对〕，另有 UICoder/DesignBench/Sketch2Code 等后续线索未逐一核原文）。

### 6.3 业界侧：截图对比做进生成闭环——v0 口径最硬

- **v0 视觉自评行为（官方明说）**："v0 can open the apps it builds, use them, **critique designs**, debug complex flows, and fix things proactively. While it works, v0 **sends you screenshots of what it sees**. It can also visit external URLs to capture visual references or inspect a page's layout before recreating it."；`agent-browser` 无头浏览器进工具面（"launch a headless browser session against your live preview to verify user flows and capture errors"）；2026-05-15 changelog："v0 can now **show the browser screenshots the agent captures while testing** your preview."。[一手 /docs/agentic-features、/docs/terminal-commands、changelog]
- **v0 Figma 的边建边比对**（§2.5）："Frame images: v0 compares the app with each frame as it builds"——最接近「生成物 vs 设计稿」闭环的官方口径，**但对比是否由 VLM 打分、有无分数输出官方未说明**。
- **Lovable browser testing**："interact with your app in a real browser running in a virtual environment... verify real user behavior **with screenshots instead of relying on code alone**"，触发条件含 "Visual or UI issues can't be pinpointed through logs or code inspection"——定位是行为/可用性验证，**无对比设计稿评分口径**；**官方自认的能力边界**："**It is not reliable for evaluating subtle visual design details or color differences.**"——对「浏览器自测能否评视觉还原」的直接官方证词。[一手 docs.lovable.dev/features/browser-testing]
- v0 Figma 集成 2026 年升级口径："Connect a Figma file and v0 **reads its structure and styles directly while building**, instead of the older import-and-screenshot step"——从「导入＋截图」进化到「构建中直读结构」，与 §2.5 的 token/结构直读口径一致。[一手 changelog]
- **screenshot-to-code（abi，一手 README）**：同类开源星数最高之一；内置 agent 自动闭环自查——"**Screenshot preview** (optional) lets the agent render its own generated page in a headless browser and **visually check its work**"；对外评测却是人眼并排（Examples 节 "Original vs Replica" 双列截图），**无自动相似度分数**。
- **「AI 生成产品官方用 VLM 评视觉还原度并给分」：未证实**——最接近的是 v0 的 compares/critique 措辞，打分机制无官方口径。

### 6.4 呈现面：偏差可视化有成熟先例（在视觉回归工具域）

- **Chromatic Diff Inspector（一手 docs，最成熟的偏差叠加呈现）**："Changed areas are automatically highlighted in neon green"；三视图＝**Unified 1-Up（变更直接叠加在基线上）/Split 2-Up（并排）/diff strobing（基线与新图快速闪切）**＋autofocus（微小像素变化自动聚焦）＋可配阈值 `diffThreshold`。[一手 chromatic.com/docs/diff-inspector]
- v0 的 agent 自测截图回放（§6.3）是 AI 生成产品把「它看到的样子」呈现给用户的先例——**无 diff 叠加、无分数**。
- **「与设计稿的相似度分数 UI」：未证实**——无产品把相似度分数作为用户可见指标呈现；视觉回归工具呈现的是差异区域而非分数。

## 7. 对本平台的映射

自家现状事实（落稿人本仓核实）：①基座工作区已内置 Tailwind 4＋shadcn 语义 token 主题——`aiplatform-server/src/main/resources/docker/workspace/baseline/src/app/globals.css` 与 shadcn 官方脚手架逐行同构（`@import "tailwindcss"; @import "shadcn/tailwind.css"; @theme inline` 映射＋`:root` OKLCH 语义变量集，注释明言「执行体在基座上增量实现时直接改这些语义变量即可换肤」）；②run 执行体系统提示词（`AgentProfile.EXECUTOR`）与工作区 `AGENTS.md` 平台约定均只有技术栈约定（"shadcn（Tailwind 4）...不换栈"），**无任何样式/设计一致性条款**；③设计规范无载体（PRD 是需求正本，无设计章节位）；④模型栈纯文本、沙箱无 headless 浏览器/截图基建（map #267 Notes 2026-10-03 探查，零基建）。

以下为**证据映射**（裁决归 #277，只标代价档）：

1. **token 载体：现成，零新建**——业界事实标准＝shadcn CSS 变量（v0 训练级口径＋Lovable 检查面＋shadcn 官方推荐三方一致），我方基座 globals.css **已是该形态**；「规范提炼」的产物可直接落成该文件的 `:root` 值集（色/字/radius），规范级桥不需要发明新载体。**现能力可做**。
2. **规范结构：业界双件共识可参照**——机器可读 token（可校验）＋人读规则（组件风格/用法约束）两件分离：Google DESIGN.md（YAML front matter＋prose＋lint/diff CLI）、Lovable design-system.json（schema＋rendered rules）、v0 skill（指令蒸馏＋starter）。我方规范形态裁决（色/字/版式 vs 组件风格分层）有同构物可对表。**现能力可做**（载体形态裁决归 #277）。
3. **生成侧遵守＝三机制组合**：提示词注入（Lovable knowledge 同构位＝我方 AGENTS.md 平台约定/执行体协议，官方自认长会话漂移→须短条目）；结构物化（token 文件落基座＋执行体只引用）；后处理扫描（Lovable「每轮扫描违规即自动重试」是唯一产品化实锤）。我方现状三件全缺（协议无样式条款）。**现能力可做**（协议与规范文件注入），扫描纠偏见 4。
4. **结构性约束件全部现成**：@shadcn/lint（官方 agent-first linter，Tailwind v4 即用，规则面＝Lovable adherence 同款）＋Tailwind v4 `--color-*: initial` 调色板收窄（一行配置只暴露语义色）＋arbitrary values lint 禁用——「代码只引用 token」的静态强制零自研。**需动工作区基建**（基座装 lint 依赖＋执行体收口步骤加扫描轮，属轻量服务端/镜像改动）。
5. **偏差判定分两半**：文本 token 合规（lint 扫描）**不依赖浏览器可先行**（同 4）；「像不像」的视觉判定（Design2Code 五维骨架/v0 agent-browser 截图自测）受沙箱零截图基建所限——**需动服务端/镜像**（headless 浏览器与截图管道，map #267 已列零基建现状），v1 扩展点级；且业界无「相似度分数」产品化先例、Lovable 官方自认浏览器自测不可靠评视觉细节——呈现形态（截图回放 vs 分数 vs diff 叠加）裁决时按此对表。**裁决归 #277**。
6. **转译级备案触发器读数：未触发**——stitch 式导出代码质量口碑未成熟（§1.5），且 Google 官方叙事已转向「DESIGN.md 规范＋agent 重写」（§1.3/§1.6）；map #267 的备案触发器（导出质量成熟＋用户真要骨架复用）两条件均不成立。**维持出局备案，无需动作**。
7. **「图片→结构化规范」是桥中最大空档**（§4.4 业界无现成件）：我方设计过程产物是图片态（多稿/挑选，map #267），从被选设计稿提炼规范这一步没有可买的工具——业界最接近的是 v0 截图→直接生成（跳过规范层）与 Figma variables（要求设计住在 Figma）。**提炼机制需自研或半自研（模型读图提炼/人工挑选+模板填值），属裁决票核心决策点**。

## 8. 未证实事项备案（如实，不推测）

**Stitch**：
- 当前（2026-10）官网 FAQ 是否仍保留 "production-ready" 表述——当前 FAQ 为 SPA 且本机不可达；所引系 2025-06-06 Wayback 存档副本（取证代理读得原文），**落稿人本机多通道不可达、未能二次复核**，采信时按存档时点口径。
- 官方 React/Vue/Flutter 导出——全官方渠道从未提及；第三方散见说法判转述失真，倾向不存在。
- theme 在导出代码中的落地形态（CSS 变量/Tailwind config/写死 class）——官方无说明；社区观察指向内联 class（"class soup"，二手中可信）。
- 官方 X 账号公告内容（x.com 不可达）；export to Figma 从 paste 升级为正式功能的精确上线日。

**v0**：
- 官方系统提示词未公开——流传泄露版（含「禁任意值/禁裸色/3-5 色上限/2 字体上限」条款）为社区存档件、官方未确认，**二手中等可信、本快照仅备案不采信**。
- Design mode 面板改动落码粒度（改 Tailwind class 串还是别的）——官方只到 "surface Tailwind-compatible values / Class and token coverage depends on what's defined in your project"，更细无披露。
- Design mode / Design Systems 2.0 的官方 changelog 上线条目（客户端渲染不可直读，上线日期仅二手口径）。
- Figma「compares the app with each frame as it builds」的比对是否经 VLM 打分、有无分数输出——无官方口径。

**Lovable**：
- 官方系统提示词未公开（knowledge/AGENTS.md 注入方式亦未披露）；内置 lint/自动 ESLint 检查接进生成循环——官方全文档无此口径（仅 knowledge 示例建议用户自写），判未证实。
- visual edits 旧文档页（404，archive 不可达）关于改动直写代码的原话；Themes 面板的实现细节（是否写 CSS 变量）；「Sections」特性（现行 docs/词条查无，判现行不存在）。
- knowledge 曾支持「附 20 个文件」的旧口径（现行＝纯文本两级各 10k 字符；旧口径仅搜索摘要级二手）。

**生成侧/偏差判定**：
- 三机制（提示词注入/模板化/后处理校验）的横向量化效果对比——无 benchmark 无受控实验；各家官方效果宣称全为定性。最接近证据是 Design2Code 的 text-augmented prompting 消融（输入模态维度，非机制维度）。
- 「Design2Code-2M」——查无此名论文（2M 数据集是 WebSight）；后续线 WAFFLE 等仅题录。
- AI 生成产品官方用 VLM 评「视觉还原度」并给分、以及把相似度分数呈现给用户——均无先例（v0 到 critique designs＋截图呈现为止；Lovable 自认浏览器自测不可靠评视觉细节）。
- bolt.new（商业版）Lock file 的官方文档页未找到（证据＝bolt.diy 一手源码＋二手教程）；bolt.diy 系统提示词为开源快照（2026-10 main 分支），商业版 V2 后是否同构未证实。
- 「pixel diff＋VLM 评判」组合的成体系工程文章——未找到（碎片证据：v0 critique＋screenshot-to-code 自查）。

**token 管线**：
- 专用「图片/截图→结构化 design token」工具——未证实存在（业界空档，§4.4）。
- Tailwind 官方「禁用 arbitrary values」配置——不存在（官方定位 escape hatch）；社区 eslint-plugin-tailwindcss 自述 "as is"/重写期（维护状态注意）。
- Style Dictionary 对 DTCG 2025.10 的完整支持——官方写 "work in progress in v5"；官方 Tailwind 专用 format 不存在。
- Nathan Curtis 原文（medium 本机不可达）、Supernova/Specify 版本节奏（仅核产品定位）。

二手来源可信度档：LogRocket 实测＝中高；HN/Reddit/DDG 索引转引＝中；官方论坛用户帖＝中；技术评测博客（buildmvpfast/ai-expert/ai.joaoqueiros）＝中；v0 泄露提示词存档＝中（不采信）；Ben's Bipes/mediajunction/alexop.dev＝中低（摘要级）。

## 9. 来源与复核记录

**落稿人逐字/逐行复核清单（2026-10-03，curl .md 端点＋GitHub raw＋webReader，全部命中）**：

- Lovable：design-systems.md（adherence「every generation is scanned...automatically retries」逐字、design-system.json schema 行、token 发现规则行）；design-guidance.md（"uses it as the spec for the build" 逐字）；knowledge.md（"may not always be followed consistently"、"always included in context" 逐字）。
- v0：design-systems-2.md（"teach v0 your design system once"、"cannot be verified...should not use it"、starter 四步、Revision History）；figma.md（"It uses your tokens, layout, text, and assets"、"Frame images: v0 compares the app with each frame as it builds"、五项读取面）；design-mode.md（"Class and token coverage depends on what's defined in your project"、"serializes your edits"）；api/v1 lock-files 指南（`locked: true, // AI cannot modify this file` 字段原文）。
- bolt.diy 源码（GitHub raw，main 分支）：`prompts.ts` L394-437（`<design_instructions>`＋`<user_provided_design>` 的 FONT/COLOR PALETTE/FEATURES JSON 注入）；`.server/llm/stream-text.ts` L212-217（锁文件提示词注入原文）；`stores/workbench.ts`（两处 "scoped locks...future update" 注释——AI 写路径无锁拦截）。**两代理报告在 bolt.diy 锁执行层矛盾，落稿人源码仲裁：提示词级执行属实。**
- shadcn：ui.shadcn.com/docs/theming.md（"We use and recommend CSS variables for theming"＋语义 token 句＋oklch 值逐字）；github.com/shadcn-ui/lint README（agent-first 定位句、规则表六条、Quickstart 提示词）；npm registry（@shadcn/lint 0.2.0 在册）。
- Tailwind：tailwindcss.com/docs/theme（webReader v4.3——"Theme variables aren't just CSS variables"、"Use @theme when..."、`--color-*: initial` 段逐字）。
- Stitch：blog.google stitch-ai-ui-design（"extract a design system from any URL, or use the new DESIGN.md" 命中）；developers.googleblog.com 发布文（webReader——"theme selectors, and a paste to Figma function"＋"clean, functional front-end code...ready to go" 逐字）；github.com/google-labs-code/design.md README（YAML tokens＋prose 定位句、lint/diff CLI 与 WCAG 结构化 JSON 输出逐字）。
- 论文：arXiv 2403.03163 摘要（自动指标＋人评校验＋模型短板句）与 HTML 正文（CLIP-ViT-B/32、Sørensen-Dice 命中）。

**关键来源索引**：

- Stitch（一手为主）：developers.googleblog.com/en/stitch-a-new-way-to-design-uis/（2025-05-20）；官方 FAQ Wayback 2025-06-06 存档；blog.google …/stitch-gemini-3/（2025-12-10）、…/stitch-ai-ui-design/（2026-03-18）、…/stitch-design-md/（2026-04-21）、…/stitch-real-time-design/（2026-05-19）；discuss.ai.google.dev MCP 公告（2025-11-20）；github.com/google-labs-code/{stitch-sdk,stitch-skills,design.md}。二手：HN 43222424/48670254/46077749、Reddit r/GoogleGeminiAI（DDG 转引）、LogRocket 2025-06-11、buildmvpfast/ai-expert/ai.joaoqueiros 评测。
- v0（一手）：v0.app/docs（design-mode、design-systems-2、design-systems(-legacy)、figma、screenshots、instructions、text-prompting、faqs、agentic-features、terminal-commands）＋ API（v2 guides/design-systems、v1 guides/lock-files-from-ai-changes）＋ v0.app/changelog；community.vercel.com Design Mode 公告（2025-06-12）。
- Lovable（一手）：docs.lovable.dev（features/{knowledge,design-systems,design-guidance,preview-toolbar,code-mode,browser-testing,skills,business/design-templates,upgrade-to-tanstack-start}、integrations/{figma,build-with-url}、prompting/prompting-one、glossary、changelog）＋ lovable.dev/blog/introducing-visual-edits（2025-02-12）。
- bolt.diy（一手源码，main 分支 2026-10-03）：app/lib/common/prompts/{prompts,optimized}.ts、app/types/design-scheme.ts、app/lib/.server/llm/stream-text.ts、app/lib/stores/workbench.ts、app/utils/fileLocks.ts、app/lib/persistence/lockedFiles.ts。
- shadcn/Tailwind/token 管线（一手）：ui.shadcn.com/docs/{theming,tailwind-v4,registry,registry/registry-item-json,registry/open-in-v0,registry/mcp,figma}＋ui.shadcn.com/llms.txt；github.com/shadcn-ui/lint＋npm @shadcn/lint；tailwindcss.com/docs/{theme,adding-custom-styles}（v4.3）；docs.tokens.studio；help.figma.com（Variables in Dev Mode、Figma MCP server）；developers.figma.com/docs/rest-api/variables；styledictionary.com＋github.com/style-dictionary/style-dictionary；design-tokens.github.io/community-group/format；supernova.io；zeroheight.com；cursor.com/docs/context/rules；code.claude.com/docs（memory/skills）；docs.windsurf.com/windsurf/cascade/memories；code.visualstudio.com/docs/copilot/copilot-customization。
- 偏差判定：playwright.dev/docs/test-assertions；chromatic.com/docs/{visual-tests,diff-inspector}；percy.io；applitools.com；arxiv.org/abs/2403.03163（HTML 全文）；github.com/abi/screenshot-to-code；francoismassart/eslint-plugin-tailwindcss。
