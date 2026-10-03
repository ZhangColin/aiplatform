# 调研快照：六家对标设计能力面——Replit design / Lovable 图片生成 / v0 Design tab（2026-10-03）

> 结论关联：wayfinder #269（本票）、map #267（设计能力决定集：一过程双终点）；直接服务 #272（定位与交易语义：词汇谱系、终点一交付物谱系）与 #273（项目载体：设计先行路线、设计稿→系统一致性）；证据面另供 #274（设计过程形态）、#277（一致性）参照——正本先例（stitch 过程模型、一致性业界做法）分别在 #268/#271 专票，本快照不抢。
> 口径：**只摸设计能力面**——设计稿/设计过程/图片生成/可视化样式编辑与「设计→系统」关系。既有两份对标（`2026-09-03-benchmark-ai-app-builders.md` 过程可见性与支撑能力：Lovable/Bolt/v0；`2026-09-03-benchmark-replit-coze-kimi.md`：Replit/扣子/Kimi）已覆盖过程可见性面，本文不重复，仅在必要处一句话引用作底。只看线上 SaaS（扣子编程=code.coze.cn、Kimi=kimi.com 网页，产品框定沿用前件）。一手来源（官方 docs/changelog/blog/API 文档/官方发布文）为主，二手标注可信度；查无证据判「未证实」如实备案，不推测。
> 方法注记：五路并行取证报告综合而成（Replit 正主一路做深、Lovable/v0/Bolt/扣子+Kimi 各一路）；落稿人对承重引文做逐字复核（复核清单见文末）。调研日 2026-10-03，产品状态以当日为准。

## 结论速览

1. **Replit design 正主已完全成型，与 #267「同项目双终点」正选直接对位**：设计已是与 Build 并列的独立套件 Replit Design（2026-07 发布；演进四段=Visual Editor 2025-03→Design Mode 2025-11→Agent 4 Design Canvas 2026-03→独立套件），同项目双入口（"They open the same project, so nothing you design is stranded in a design tool"）；设计帧被官方钉死为「**A design frame is never just a picture, it's the spec for real software**」——设计稿不是图、是真软件的规格书；转正两路=Build（按稿新建应用）/ Apply（**Agent rewrites the Artifact's code to match the design**，稿留画布作参照），反向 restyle 亦通，Apply 前 checkpoint 兜底。**#273 可直接引为同构正本**。
2. **图片生成是行业标配底座（5/6 家），全部外采头部图像模型**：GPT Image 2（Lovable 双线）、Nano Banana/Pro（v0/Bolt/Replit 画布）、Imagen 4（Replit agent 内生）、豆包 Seedream 4.5/5.0（扣子集成）——**业界无一家自建生图模型**（唯 Kimi 自研多模态顺带出图，属模型禀赋）；产物去向标配三件套=直落项目资产＋文件区画廊＋可下载；计费全部并入平台 credit/token、**无一家按张独立标价**。对 #270/#275：外采是行业统一答案；Replit 另有 LLM 直写 SVG 的 Vector graphic 路线（logo 明列）是扩散模型外第二条路。
3. **设计先行是少数派（3/6）但强度成谱系**：Replit 全链（双入口常驻）＞Lovable 轻量版（首条 UI 消息默认出 **3 个 design directions**=HTML+Tailwind 轻量预览，并排比＋单方向 refine ≤6 次＋整组重出，Submit 锁定后构建）＞扣子黑箱版（设计引导开关=选预设风格→生成原型→按原型出代码，原型形态与对齐机制未公开）。**v0 是反向样本**：初代三稿竞选（"3 variations A/B/C"）已退役、只活在 SDK "Classic v0" 复刻里——多稿主链有维护成本，行业有人做了又收。判断尺：设计先行不构成「标配缺口」，做不做取决于双终点定位而非行业压力。
4. **设计稿的行业主形态=可交互轻量实现，六家无一以位图为设计稿主形态**（Replit mockup 帧、Lovable HTML+Tailwind 预览、扣子原型挂 PRD 链）——对 #273 设计 run 产物形态是关键证据：设计稿与系统在基建上同源（都是 Web 产物），与位图管道（#276）是两条线。
5. **一致性行业正解收敛于「机器可读规范文件＋agent 看稿重写」，四家四件**：Replit `tokens.json`＋`DESIGN.md`（"readable by you, usable by Agent"）、Lovable `.lovable/design-system.json`（构建中 "catches raw colors… deviations" 强制遵守）、v0 design system 存 skill＋starter app、Bolt 承接 Stitch `DESIGN.md`（"open-source file format designed for AI tools to read"）。**无人承诺像素级还原**——Replit 最诚实的口径："translate cleanly into the real product"＋"keep chatting to adjust anything that didn't translate"。**与 #267 桥深度=规范级起步拍板互证（#277 直采）**；且「规范文件喂 agent」路线不需要视觉输入——**与我纯文本模型栈兼容**（Replit 式看稿重写则依赖视觉能力，我当前没有）。
6. **可视化编辑 4/6 家，共同分界=「确定性层免费、生成层计费」**：Replit Visual Editor 确定性直改源码（"without consuming AI credits"，复杂自动交 agent）、Bolt visual edits 编辑免费落码耗 token（"Making visual edits in the preview is free. Bolt uses tokens only when you save your changes."）、v0 Design mode 全走生成（Apply=序列化面板编辑＋指令＋元素截图进 chat 生成新版本——**本质是给 agent 喂结构化改稿请求的输入面，与我「圈注=对话输入增强」同构**）。对 #275 计费档位有直接参考。
7. **Bolt 设计面并非零**（2026 密集补齐：图片生成＋Nano Banana 图片编辑＋visual edits＋Anima 引擎 Figma 导入＋Stitch 一键导出＋Storybook 化 design systems），但**无设计稿先行主链——mockup 生成外包给 Stitch**；官方从不使用 "Design Mode" 一词，bolt.diy「曾有 Design Mode」传闻经全历史查证证伪。扣子编程生图=应用内集成定位（Seedream 给用户应用用），平台级海报/设计交付线在 coze.cn 办公侧不在编程侧——**同一生态两产品分层**。
8. **Kimi 设计面在产品化但无「设计→系统」线**：图片生成仅 Agent 模式自动触发（会员门槛、自研多模态）；K2.6 博客章节名即 **"Coding-Driven Design"**（代码直出＋图像视频工具出 visually coherent assets）；Kimi Design 一键海报/信息图/研究图表（showcases 官方口径）＋Slides **一次 2-4 个方案缩略图挑选**（六家中最明确的「一次多稿网格挑选」形态，但在 PPT 线不在建站线）。注：Kimi 帮助中心多页 2026-10 已失效，相关单源引述已降级标注（§10）。
9. **词汇裁决要点（#272）**：六家无一家用「Demo」指设计产物；「原型」仅扣子中文一例（挂 PRD 链非贬义，但与我「系统」词条 avoid 面撞词）；行业高频词=design directions（Lovable）/ Design frame＋mockup（Replit）/ 方案（Kimi）/ 风格（扣子）；**"artwork""brand kit" 六家无一用作功能词**；退役词样本=Design Mode（Replit 模式词）、Themes（Lovable 已移除）、three-generation workflow（v0 已退役）——词汇谱系裁决时可引作「行业已弃」证据。
10. **两枚反例样本供备案**：Lovable Themes（品牌级主题套件）上线 4 个月因 "low usage and performance issues" 移除——品牌级主题作为独立功能被市场证伪；v0 三稿退役——多稿主链有维护成本。未来增强池（#279）与「现在做/备案+触发器」话语可对表。

## 1. 判断尺总表

（#267 判断尺沿用 #59：行业都有=标配该升级；行业都没有=才质疑概念。维度=设计能力面）

| 维度 | Replit | Lovable | v0 | Bolt.new | 扣子编程 | Kimi 网页 | 判断尺读数 |
|---|---|---|---|---|---|---|---|
| 设计稿/设计先行 | **有（正主）**：Design/Build 同项目双入口，mockup 帧先行，Build/Apply 收口 | **有（轻量）**：首条 UI 消息默认出 3 个 design directions，Submit 锁定后构建 | **无现行**（Classic v0 三稿已退役）；设计稿靠 Figma/Paper/截图导入 | **无内置**（外包 Stitch；Figma 经 Anima） | **有（黑箱浅形态）**：设计引导=选预设风格→生成原型→按原型出代码 | **基本无**（Coding-Driven Design 直接出码） | 3/6 有；非全员同构，但最强同构物 Replit 与我双终点构想完全对位 |
| 图片/logo 生成 | **有**：Imagen 4 内生＋Canvas Generate 多模型面板（Nano Banana 系/ChatGPT Images 2.0/Veo/LLM 写 SVG） | **有**：GPT Image 2 两档，聊天指令，产物直落资产＋standalone 可下载 | **有**：generate_image agent-action，Nano Banana (Pro) via AI Gateway | **有**：付费＋Add-on 默认关；编辑点名 Nano Banana；生成模型未披露 | **集成定位**：豆包 Seedream 4.5/5.0 给应用接入用，非平台设计资产线（海报线在 coze.cn 办公侧） | **有**：Agent 模式自动触发，自研多模态，会员门槛 | 5/6 有＝**标配底座**；模型全外采头部（唯 Kimi 自研）；无一家按张独立标价 |
| 可视化样式编辑 | Visual Editor（确定性直改源码零 credit，复杂自动交 agent） | preview toolbar 三模式（Select/Edit text/Draw） | Design mode（面板＋指令双通道，Apply 序列化进 chat 生成新版本） | Visual edits（batch 后 Save changes 落码；编辑免费、落码耗 token） | 查无独立样式面板（圈注标注为前件已覆盖） | 建站可视化编辑器＋Slides 画布模式 | 4/6 有；三家共同分界＝「确定性编辑 vs agent 生成」分开 |
| 多稿探索/挑选 | 帧积累：suggestions/换模型/"Create five different designs" 并排落帧 | 三方向并排＋单方向 refine 最多 6 次＋整组重出 | 现行无（三稿退役存 SDK 复刻） | 无（Stitch 侧多稿） | 预设风格库选择（浅多稿）；原型多稿未证实 | Slides 一次 2-4 个方案缩略图挑选（PPT 线；建站无） | 多稿在「设计先行」家都有；共同形态＝**并排比＋挑一张＋继续** |
| 设计→系统一致性 | 三层：Agent 看稿重写＋design system（tokens.json＋DESIGN.md）＋确定性编辑旁路；checkpoint 兜底；"translate cleanly" 非像素承诺 | Design systems（design-system.json 机器可读 schema，构建中强制遵守 "catches… deviations"） | Design Systems 2.0 存 skill＋starter 血统；Figma 逐帧比对（"compares the app with each frame as it builds"） | design system Storybook 化参考；Stitch DESIGN.md；Figma 走 Anima（Code 路不感知 design system，官方 Warning） | 机制未公开（「根据此原型生成最终代码」黑箱） | 无系统性一致性面（素材层 visually coherent assets） | **四家收敛于「机器可读规范文件」**；无人承诺像素级还原——直接支持规范级桥拍板 |

## 2. Replit（正主：同项目共生的设计→系统先例）

### 2.1 形态：与 Build 并列的独立设计套件「Replit Design」，同项目双入口

- 演进四段（全部 changelog/blog 一手锚定）：**Visual Editor**（2025-03-21，"Agent just got eyes - click. describe. done."）→ **Design Mode**（2025-11-21，"Powered by Google's Gemini 3, Design Mode creates beautiful interactive designs and static websites in under 2 minutes… convert your design to a full app with one click"）→ **Agent 4 Design Canvas**（2026-03-11，四支柱第一支柱 Design Freely："**Design is no longer a separate mode, it's something you can do continuously**"）→ **Replit Design 独立设计套件**（2026-07-29/31 正式发布："a new creative suite"；官方钉死承继关系 "For existing Canvas users, Replit Design is its next evolution"）。文档路径随之收敛：`/replitai/canvas` 308→`/design/canvas`。
- 官方定义（[what-is-replit-design](https://docs.replit.com/design/what-is-replit-design)，已逐字复核）："**Replit Design is a design suite to generate design frames from prompts, explore the AI's suggestions, and keep everything on brand with design systems.**"
- 项目结构（[design-vs-build](https://docs.replit.com/design/design-vs-build)，已复核）："**Every Replit project has two entry points, side by side in the header: Design and Build.** Design is where you shape how your software looks; Build is where Agent makes it work. **They open the same project, so nothing you design is stranded in a design tool.**"——**同项目双终点（设计↔系统）的先例正主，与 #267「同项目双终点」正选直接同构**。
- 七组件（[core-components](https://docs.replit.com/design/core-components)）：Canvas（无限平移缩放画板）、Frames（六种 frame kinds：Artifact / Mobile artifact / **Design（mockup）** / Image / Video / Vector graphic）、Elements（帧内元素）、Chat（选中 frame 自动作为 snapshot 附到下一条消息）、Ambient intelligence（=Suggestions，选中帧旁一键建议）、Toolbar（Interact/Pan/Chat/Draw/Edit/Generate 六工具）、Library panel（Templates / **Inspiration（Mobbin 策展真品 UI 截图，落画布为静态参考）** / Library）。**Draw 工具**："Agent reads your markup, so you can circle a problem area and ask for a change"——画布标注即指令。

### 2.2 设计稿与生成系统的关系：设计帧=「真软件的 spec」，Build/Apply 两路收口

- 关系定性（[build-your-design](https://docs.replit.com/design/build-your-design)，已复核）："**A design frame is never just a picture, it's the spec for real software.**"；"You describe what you want, and Replit Design generates **interactive mockups** you can explore and refine. When one is right, it becomes a working app **in the same project**."。mockup 边界（design-vs-build Info 框）："Design mockups look and feel like real pages, but they don't store data or connect to external services yet."——**mockup 是高保真可交互设计帧，不是运行中系统；两者同项目共生、一键单向转**。
- **「按稿生成系统」两路落地**（[frames](https://docs.replit.com/design/frames) Build… 菜单，已复核）：①**Build this design**——从帧起新 artifact，"Agent builds the layout, styling, and assets into a live, running app"；②**Apply to an existing Artifact**——"**Agent rewrites the Artifact's code to match the design**, leaving the Design frame on the Canvas for reference."（设计稿留在画布作参照，代码被重写对齐）。反向亦通（"get an app working in Build, then restyle it in Design"）；被动触发（给 mockup 加数据库时 Agent 提示转正）。安全网："Agent makes a checkpoint before it starts… you can always roll back"。计费边界："Building a new app from a Design frame requires Core or Pro; every other Design capability works on every plan."

### 2.3 一致性怎么保：三层机制、无像素级还原承诺

1. **Agent 看稿重写**：Agent 4 blog 承诺 "designs that don't just look good in isolation—they **translate cleanly into the real product**"（不承诺像素还原，承诺「干净转译」）；Build 后官方预期管理直白："keep chatting to adjust **anything that didn't translate**"——**一致性以「转译＋事后校准」而非「保证还原」为口径，值得 #277 对表**。
2. **Design system（规范级对齐，供 #277 正参）**：定位 "A design system is your brand, **made machine-usable**."；内容=tokens（colors/spacing/radii）＋带角色 Colors＋Typography＋Example components＋**Logos and brand assets**；存储双表示（[design-md](https://docs.replit.com/design/design-md)，已复核）："tokens in `tokens.json` feed the CSS variables (e.g. `src/index.css`) that your components consume. **`DESIGN.md` is the document representation — readable by you, usable by Agent.**"；生成两路=品牌问卷从零 / 从 mockup **Extract design system**（"Agent distills the mockup's tokens, colors, and typography into a living style guide"）；应用到存量="**a real rebuild** — you'll see a time and credits warning… Agent is re-generating the artifact's styling layer, not swapping a stylesheet"（诚实披露重操作）；治理=存 workspace、按项目可选可换、2026-09-25 起与 skills 同套权限规则。
3. **Visual Editor 确定性直改**（旁路）："Simple edits — text changes, color adjustments, spacing tweaks — **update the source code directly without consuming AI credits**. For changes that involve hidden complexity, the Visual Editor hands off to Agent automatically."——「设计修改直落生产代码」在最表层由这个确定性旁路实现。
- 图片类资产一致性：生成媒体可附画布元素为 **visual references**（"Agent uses these references to guide the result so it matches the look of your project"）。

### 2.4 多稿探索与挑选：「帧积累」模型，三轴并行

- 探索哲学（[explore](https://docs.replit.com/design/explore)）："**Go wide before you commit**: follow suggestions, pull in templates and inspiration, and switch models, **all landing as new frames**." / "Every result lands as a new frame on the canvas, **so you never lose a direction**."
- 三轴：①**Suggestions**（Ambient intelligence，选中帧旁一键建议：A new look / An extension / A new context；chips 官方实例 "Explore different approaches, Try different layouts, Explore different vibes, **Surprise me**"）；②**换模型**："Suggestions explore directions with the same designer; **switching models changes the designer**."（官方模型清单 Claude, GPT-5, Gemini, Kimi, GLM）；③**Templates+Inspiration**（模板落为可编辑活帧；Mobbin 截图静态参考）。
- **一次多稿官方实证**（mobile-design 教程官方示例 prompt）："Create five different designs for the game menu…" ——"**Agent lays them out side by side on the canvas, so you judge them as whole screens rather than one at a time.**"——**多稿=帧并排，挑选=人眼比帧，收口=Build/Apply**。
- 历史形态注意：Agent 4 时代曾有 `/design/versions`（三历史版本帧）与 `/design/convert`（"Select the frames to compare, then convert the winner"）两页，Replit Design 重构后 404（实测）——「版本/赢家转换」收进 suggestions＋Build 菜单模型。

### 2.5 图片/logo/品牌资产生成（多通道、产物直落项目文件）

- **Agent 内生出图**（[image-generation](https://docs.replit.com/features/agent/image-generation)，已复核）："built into Agent — there's nothing to toggle on"；用例表明确含 **"App icons and logos"**（示例 "Generate a modern logo for my fitness tracking app"）、透明底；产物去向 "**Generated images are saved directly to your project files**… Agent updates your code to reference the new image files"；底层 "Replit's image generation is **powered by Google's Imagen 4**"。
- **Canvas Generate media 面板**（toolbar 页官方模型表，自注 "model lineup changes frequently"）：Image=**Nano Banana / Nano Banana 2 / Nano Banana Pro / ChatGPT Images 2.0**（aspect ratio＋1K/2K/4K）；Video=**Veo 3.1 / Seedance 2.0**；**Vector graphic=SVG**（"icons, logos, diagrams, stylized illustrations"）由 **Claude 4.6 Opus / Gemini 3.1 Pro Preview**（LLM 直写 SVG）；Image frame 动作含 Crop 与 Generate video（图生视频）；media frames 共有 Replace media / **Download** / Alt text；帧可导出 PNG（2026-05-15）。
- 外围：音频（music/SFX/speech）、3D 模型（Tripo3D connector）、FLUX 图像（Black Forest Labs MCP）、SVG 上传、Library sidebar 复用已生成图。
- **logo 结论**：双通道——LLM 写 SVG（Vector graphic 明列 logos）＋扩散模型出 PNG（含透明底）；design system 层还可将用户品牌 logo 作为资产入库保一致。**「设计即交付」的图片出口齐全：帧导出 PNG、media Download、资产入 design system**。

### 2.6 词汇（Replit 官方原词）

用户面/文档正词：**Replit Design**（产品名）、**Design / Build**（双入口）、**Canvas**、**frame**（frame kinds 含 **Design**=mockup 帧）、**mockup / interactive mockups**、**Interactive frames**（能力开关）、**Suggestions / Ambient intelligence**、**Visual Editor**、**Draw**、**Generate media**、**Build… / Build this design / Apply to an existing Artifact**、**design system / DESIGN.md / tokens.json / living style guide / Extract design system**、**Templates / Inspiration**、**Import Figma / Import URL / Recreate screenshot**、**Restyle**、历史词 **Design Mode / Design Canvas / versions / convert the winner**。

## 3. Lovable（logo/图片生成）

Lovable 有**两条官方明确区分的图片生成线**（[integrations/ai](https://docs.lovable.dev/integrations/ai)："These AI features run inside your app. They are separate from the Lovable agent that helps you build and edit your project."）——构建线（agent 帮用户做项目出图，含 logo）与 app 内线（用户应用的终端用户出图，Lovable AI connector）。设计先行面则是 **Design guidance**（三方向设计稿挑选），与图片生成是两件事。

### 3.1 构建线图片生成：扩散模型出图、聊天指令入口、credit 计费

- **机制**：agent 调图像模型生成位图（非代码/SVG 出图）；官方不披露完整路由表，只披露「agent 自动选模型与质量档」——[chat docs](https://docs.lovable.dev/features/projects/chat)："**Lovable picks a quality level for each image.** Ask for higher quality when detail or readable text matters, which uses more credits."（已逐字复核）。模型演进（changelog 锚定）：2026-03-16 "Image generation updates"（agent 自动选模型＋realistic app UI mockups）→ 2026-05-18 "GPT-Image-2 for premium image generation" → 2026-06-22 "standard quality… now uses GPT Image 2"。2026-10 现状：premium/standard 均 GPT Image 系，构建线完整路由未公开（未证实）。
- **入口**：纯聊天指令（"Generate a hero image of a mountain at sunrise."），无专门按钮；编辑走 preview toolbar（Select elements 选中替换 / Draw annotation 圈注后描述）。
- **计费**：吃 build credits 不按张标价——FAQ："**Generated images use build credits and appear as Image generation in your usage details.**"；历史口径：2025-11-26 上线时免费、现已并入 credit；2026-09-23 起用量明细单列。
- **AI 标注**：2026-07-08 起生成/编辑图内嵌 IPTC provenance metadata（Google/社交平台可读出并标 AI-generated）。

### 3.2 多稿挑选形态：图片无多稿网格；多稿在设计方向层

- **图片生成无「一次多张网格挑选」**：全站文档（官方 llms-full.txt 镜像全文检索）查无；交互语义是单张生成＋follow-up 迭代。唯一间接痕迹 2026-07-08 changelog 一句 "image variants created in the visual editor"，交互形态无文档（未证实）。
- **平台内真正的多稿挑选＝Design guidance**（2026-05-18 上线，[docs](https://docs.lovable.dev/features/design-guidance)，已逐字复核）：首条含 UI 的消息默认出 **三个 design directions**——"Design directions generate three different visual approaches for your project **before Lovable starts the full build**"；"Each direction is rendered as a **lightweight HTML and Tailwind preview** so you can compare layouts, typography, color, spacing, and overall visual tone side by side"；可并排比/全屏/缩略图切换；整组不满意可 "generate another set"；单方向可 refine **"up to six times total"**（上限后对话框禁用再 refine）；Submit 锁定后开始正式构建。局部亦可要三变体（"Show me three options for the hero section"）。另有 **design questions**（问排版/配色/布局偏好）与 prompt library 的 design-and-style 词汇表。**注意：设计方向预览是 HTML+Tailwind 轻量实现而非位图**。
- app 内线（用户应用终端用户出图）有模型表与档位抽象："fast, standard, or premium. Lovable picks the model for each level"——GPT Image 2 / GPT Image 2.5 Flare/Sunburst / Gemini 3 系（Nano Banana 系）在列（[integrations/ai](https://docs.lovable.dev/integrations/ai)）。

### 3.3 产物去向：直接进项目、Files tab 画廊、standalone 可下载、发布链自动消费

- **自动入项目资产**；2026-05-28 起 "Asset storage… Images, videos, and other large files… are now stored **outside the project itself**"；2026-07-09 Files tab 图片画廊（真实比例网格＋Reference/download/copy/delete）。
- **standalone 图片（logo/海报）可下载**——chat docs 原话（已复核）："**Lovable can also create standalone images that are not part of your app, like a logo or a poster. Ask for one and download it from the chat or the Files tab.**"
- **发布链自动消费**：2026-01-16 "generate logos, favicons, and Open Graph images by prompting the agent… These assets are then used automatically during publishing"；favicon 自动裁切转 `.ico`（2026-02-23）；建站时自动生成 title/description/icon（2026-08-19）。
- **版权/商用**（FAQ 转引 terms）："Your apps, code, and the content you create with Lovable are yours… You can modify them, **use them commercially**"；图带 IPTC AI 生成元数据但归属不受影响。

### 3.4 其他设计能力面（逐项，一手）

1. **Design guidance / design directions / design questions**：有，见 §3.2——**设计稿先行＋挑选＋锁定后构建的完整形态，六家最接近「设计服务系统」入口的家**。
2. **Design systems（共享组件库）**：有（all paid plans）。design system 本身是一个专门项目，发布时在 `.lovable/` 生成 **`design-system.json`**（tokens/组件目录/约束的机器可读 schema）；连接项目 file-copy attach＋`lovable.toml` 记 `[[libraries]]`；日常构建中**强制遵守**："Lovable catches raw colors, custom or customized components, and other deviations from your design system"（[design-systems](https://docs.lovable.dev/features/design-systems)）——**这是「设计规范→生成遵守」的结构化一致性先例**（供 #277）。
3. **Design templates / Lovable templates**：项目级模板（Business/Enterprise，整 codebase 拷入新项目）与公开模板库（lovable.dev/templates）。
4. **Themes（品牌级颜色/字体/间距）**：2025-11-26 上线、**2026-03-16 已移除**（"removed from the Design view due to low usage and performance issues"）——品牌级主题作为独立功能被市场证伪的样本。
5. **Visual edits→preview toolbar**：无代码可视化编辑持续演进，现为三模式 Select elements / Edit text inline / Draw annotation；每人 24h 免费 100 次。
6. **Figma 设计稿先行**：三代——Figma import（2025-01 经 Builder.io 上线、2025-11-26 移除）→ 现行三路（Figma 插件 / Figma MCP / `.fig` 文件附件，2026 年陆续上线）；`.fig` 路径官方口径："uses these to match your design's style, **not to rebuild every screen in the file**"。
7. **Brand kit**：无此功能与官方用词（最接近的 Canva 连接器资产留在 Canva 侧；Logo.dev 是让用户 app 显示第三方公司 logo 的 API，非设计工具）。
8. **app 内 AI 线（Lovable AI）**：与构建线严格分离；自动生成 `LOVABLE_API_KEY`、credit 计费。

### 3.5 词汇（Lovable 官方原词）

用户面/文档正词：**image generation**（用量明细行名）、**standalone images**（logo/海报类）、**design directions / design questions / Design guidance**、**Design systems**、**Design templates / Lovable templates**、**Visual edits**（历史词，现为 preview toolbar）、**Themes**（已移除的历史词）、**fast/standard/premium**（质量档位）、**Select elements / Edit text inline / Draw annotation**、**Figma designs**（chat actions 项）、**Lovable AI / built-in AI connector**。无 "brand kit"、"artwork" 功能词。

## 4. v0（Design tab）

### 4.1 Design mode 形态：点选元素＋样式面板＋自然语言双通道，Apply 序列化进 chat 生成新版本

- 官方功能名 **Design mode**，入口 preview toolbar 的 **Design** tab（[docs/design-mode](https://v0.app/docs/design-mode)，已逐字复核）；编辑器四模式官方枚举 "Preview, Design, Code, and Database"（changelog 2026-08-28，已复核）。定位："a visual way to refine your app's user interface… select any element in the live preview, tweak its styles with a visual panel (and/or natural-language instructions), and then apply those edits back to your source code."。Free 档即可用（pricing 页 "Edit visually with Design Mode"）。
- **能改什么**（design panel 官方控件全表）：Typography（family/size/weight/line height/letter spacing/alignment/decoration）、Color、Background、Layout（四边 margin/padding）、Border、Appearance（opacity/corner radius）、Shadow、Content（直接改文案）——**颜色/字体/间距/边框/阴影全覆盖，不含布局结构变更**；**Tailwind-aware**："design mode surfaces Tailwind-compatible values where possible"。
- **双通道**：①面板精调——"Tweaks made in the panel are applied live in the preview… **They're held as pending edits until you apply them**"（已复核），配套 Undo/Redo/Reset/**Before/after preview**；②Instructions 文本框——自然语言（官方示例 "make this a three-column grid"），**v0 自动附上所选元素的截图**（changelog 2026-03-20 "element screenshot attachment for design mode element selection"）。2026-06-08 加浮动工具条＋图层面板＋**间距测量 overlay**＋删除元素。
- **Apply 机制（承重原文，已逐字复核）**："click **Apply**… **v0 serializes your edits (and any instructions / screenshots), sends them to the chat, and generates an updated version of your project that reflects the changes in your source code.** Because Apply produces a new chat version, you can review the diff, keep iterating, or revert just like any other v0 edit."——**不是纯前端改 CSS**：面板微调＋指令＋元素截图被序列化成一条 chat 消息，由 agent 跑一轮生成写回源码，产物与普通聊天编辑同权（diff/回滚/版本史同一套）。**Design tab 本质是给 agent 喂结构化视觉改稿请求的输入面，不是旁路编辑器**——与我平台「圈注＝对话输入的增强不是替代」（CONTEXT.md）同构。

### 4.2 与聊天生成的关系

- 设计编辑物理上就是聊天消息（Apply=serialize→send to chat→generate new version）；版本系统同一套（versions 文档：每条消息产一个版本、线性历史）。quickstart 官方工作流：生成后迭代三选——Code tab 选中行加评论 / Design mode 点选微调后 Apply / 继续聊天。pending edits 在面板间切换保留。

### 4.3 图片生成：generate_image 是 agent 动作，非独立功能页

- **协议面**（v2 API schema，一手）：`generate_image` 是 **agent-action part 的 name 之一**（"Stable identifier for the action (e.g. \"generate_image\", \"manage_todos\", \"diagnostics\")"）——图片生成被建模为 agent 的一个动作；**官方文档无独立「图片生成」产品页**（/docs/images-and-videos 讲的是上传输入）。
- 入口三处：对话内让 agent 出图；**Plan agent 也能出图**（changelog 2026-03-16 "Added image generation capability to the Plan agent"）；**图片编辑挂 Design mode**——changelog 2025-10-30（已复核）："You can now edit images using Nano Banana in v0. **Turn on design mode, select an image, and prompt your changes.**"。
- 模型：官方只用品牌名——**Nano Banana**（2025-10-30 起）/ **Nano Banana Pro**（2025-11-20 "now available in v0 via Vercel's AI Gateway"）；底层是否 Gemini 系官方未点名（未证实）。
- 计费与去向：**独立吃 v0 credits**（"Image generation, editing, and regeneration" 列在计费面）；产物以**项目内相对路径**引用（changelog 2026-08-28 "Generated images referenced by relative paths render in responses"），具体落盘目录未文档化（未证实）。2026-06-08 "v0 can now generate SVGs with a built-in skill"。

### 4.4 其他设计能力面

1. **Design Systems 2.0**（现行主形态，[docs/design-systems-2](https://v0.app/docs/design-systems-2)）：**设计系统存为 skill**——"A design system is saved as a **skill** in your current scope… it is an adapter that tells v0 where your source lives, which components, props, and tokens are safe to use"；导入源=npm 包/GitHub 仓库/真实应用/Storybook/**Figma frame**；导入流程专属 chat 里 **v0 搭 starter app 供人审、批准后才存 skill**、此后每个用该系统的 chat 从 starter 起步；载体文件 `v0.json`；可设团队默认；每次修改进 Revision History。旧版（legacy）= shadcn registry＋tokens.css，主题靠 ui.shadcn.com/themes 或第三方 tweakcn——**v0 本体无内置主题编辑器**。
2. **设计稿输入三源＋截图**：Figma（付费，贴链接即用，**"v0 compares the app with each frame as it builds"** 逐帧比对——一致性机制供 #277）；Paper（2026-08 新源）；截图/mockup 上传（FAQ："Upload screenshots, mockups, or Figma designs. v0 converts them into working applications"）。
3. **多稿**：**现行产品无「一次多方案」**（全量文档＋一年 changelog 均无）；**历史有且官方留了 SDK 复刻**——v0 SDK 示例 "Classic v0 — A faithful recreation of the original v0.dev interface" 明写 "**three-generation workflow**"、"**Creates 3 different variations (A, B, C) for each prompt**"、"Click between different generations to compare and choose"——初代三稿竞选已从主产品退役。设计探索现役替代=Design mode Before/after＋版本回滚。

### 4.5 词汇（v0 官方原词）

**Design mode / the Design tab**（preview toolbar）、**design panel / pending edits / Apply / Before/after preview**、**Tailwind-aware**、**agent-action / generate_image**、**Nano Banana (Pro) via Vercel's AI Gateway**、**Design Systems 2.0 / design system skill / starter / v0.json / Set as Default / Revision History**、**Import from Figma / Import from Paper**、历史词 **Classic v0 / three-generation workflow**。无 theme editor（查无此能力面）。

## 5. Bolt.new

Bolt 本体设计能力面**并非零**（与印象相反，2026 年密集补齐）；开源 bolt.diy 设计能力面≈零。**官方从不使用 "Design Mode" 一词**（sitemap/llms.txt/release notes 全文零命中；二手站把 visual edits 称 Design Mode 属讹传）。

### 5.1 有则形态（全部官方 docs 一手）

1. **图片生成 "Generate AI images"**（2026-02~03 上线，[images docs](https://support.bolt.new/building/images)）：chatbox 文字提示出图，"ready-to-use image for your project, complete with **transparent background support and automatic WebP conversion**"；**默认关闭**——Settings → Account → **Add-on features** 开 "Image Generation" 开关；付费计划限定；默认项目仍用 "stock images"（官方图库），生成图比 stock 图耗更多 token；**生成用模型未披露**（未证实）。
2. **AI 图片编辑 "Edit images with AI"**（2026-01 上线）：chatbox Select 选中图 → Edit with AI → 描述改动 → **前后对比滑块** → "Use this image"；官方点名 "**Nano Banana model**. Bolt updates only the part of the image you describe while keeping the rest of the design intact."；付费限定。
3. **可视化样式编辑 "Visual edits"**（2026-08~09 上线，[visual-edits docs](https://support.bolt.new/building/visual-edits)，已逐字复核）：Select 点选元素后三种改法——双击改文字（CMD+B 格式化）/ **toolbar 属性面板**（Alt text / Background color / Font size 8-96px / Opacity / Text alignment 等全属性表，色板含项目已用色＋取色器）/ prompt 描述。**批处理语义**：每次编辑先入 preview 上方 batch（"3 visual changes"），可逐条删/附注，点 **Save changes** 才一次性落码。计费（官方原话，已复核）："**Making visual edits in the preview is free. Bolt uses tokens only when you save your changes.**" 全计划可用；边界=Select 激活期间不能页内导航、单批保存后无单独撤销（靠 version history）。2026-09 扩展到 Bolt Slides。
4. **Figma 集成 "Figma for design"**（[figma docs](https://support.bolt.new/integrations/figma)，已复核）：转换引擎为第三方——"**Bolt uses Anima to handle turning Figma designs into apps.**"（非自研 design-to-code）；homepage/chatbox 双入口；**与 design system 联动的官方 Warning**："The **Code** option uses a tool that isn't aware of your design system"（Anima Code 输出不感知 design system，须选 Screenshot 走视觉重建）。
5. **Google Stitch 集成**（2026-05 上线，[google-stitch docs](https://support.bolt.new/integrations/google-stitch)）：Stitch 内 Export → Bolt 一键导出，"attaches screenshots and page HTML and populates a prompt"；**Stitch design system 可下载为 DESIGN.md**（官方描述 "an open-source file format designed for AI tools like Bolt to read"）→ 附到项目根目录＋Project Knowledge 规则引用。**Bolt 不内置 mockup 生成器——设计稿先行外包给 Stitch**。
6. **Design systems**（2026-03~04 上线，五页文档）：上传自有组件库/文档源 → "compiles your design system from your own sources... generate a browsable **Storybook** inside Bolt and uses it as a reference when building UIs"（"built from your actual components, not stand-in code"）；预载系统（Chakra/MUI/shadcn 等）全员只读，自有系统付费 Team；sync 产生版本历史可回滚；2026-04 起可对既有项目 attach/detach/切换。官网产线佐证：首页 chunk 含 designSystems/useDesignSystems/import-figma 等 js 资产。
7. **bolt.diy**：无图片生成（provider 层全 LLM，grep dall/flux/stable-diff/replicate 零命中）、无可视化编辑、无 Figma、无 design system；唯一图像能力是 README "Attach images to prompts"（上传作上下文）。仓库 2026-02 后低维护，本体 2026 设计能力均未回流。

### 5.2 词汇（Bolt 官方原词）

**Work with images / Generate AI images / Image Generation**（开关名）/ **Edit images with AI / Nano Banana** / **stock images** / **visual edits / Make visual edits / Select tool / Save changes / batch** / **Figma for design / Import from Figma / Anima / Screenshot vs Code** / **design system / pre-loaded design systems / Storybook / sync** / **DESIGN.md**（Stitch 格式）。检索零命中词（支撑「无」）：**Design Mode**、mockup（本体无独立能力仅转述 Stitch）、logo（无专门页）。

## 6. 扣子编程（code.coze.cn）

### 6.1 设计引导：技能驱动的前置原型对齐（选风格→生成原型→按原型出代码）

官方 FAQ 三步流程（[guides_vibe_coding_faq](https://docs.coze.cn/guides_vibe_coding_faq)，已逐字复核）："当你在开发网页应用、移动应用、小程序时，如果开启**设计引导**功能，扣子 AI 会引导你先选择设计风格，产出原型，再开发项目"——①**选择风格**："扣子 AI 会加载**原型设计**技能，并提供多种**预设的设计风格**供你选择"；②**生成原型**：选定风格后继续加载原型设计技能生成原型；③**开发项目**："确认原型设计符合你的预期后，扣子 AI 将**根据此原型生成最终代码**，完成项目开发"。
- **机制定性**：设计引导是**技能驱动**（原型设计技能两步承载），不是独立管线；风格=预设风格库选择。
- **原型形态与对齐机制未公开**：官方只说「产出原型」「根据此原型生成最终代码」——原型是图片、可交互 HTML 还是静态页；对齐是视觉对齐还是转提示词，均未证实（如实备案，见 §10）。
- 设计质量第二条腿（[guides_vibe_coding_web_app](https://docs.coze.cn/guides_vibe_coding_web_app)）：**前端设计技能**"可以有效指导模型如何排版、设计配色和动画效果、处理背景等等，能显著提升模型的 UI 生成能力，**减少产物视觉效果的『AI 味』**"；且"如果信息不足，模型可能无法生成合适的 PRD **和产品原型**"——PRD 与产品原型并列，原型挂在需求澄清链上。
- 「design-canvas 技能 ID」公开文档查无此名（官方文档只见「原型设计」「前端设计」两个设计类技能名）；技能机制官方口径（[guides_using_skill](https://docs.coze.cn/guides_using_skill)）："系统会根据项目类型自动添加匹配的技能"、上限 50 个、技能商店技能不可用于扣子编程；官方编程技能完整清单无公开文档页。

### 6.2 图片生成：应用内集成定位（豆包 Seedream），非平台设计资产线

- 内置集成（[guides_internal_integrations](https://docs.coze.cn/guides_internal_integrations)，已复核）："扣子编程提供了专业的**豆包生图模型（如 Doubao-Seedream-5.0）**。为 AI 编程项目接入生图大模型后，只需通过自然语言描述，即可生成高质量、风格多样的图片"——表格列 Doubao-Seedream-4.5 / 5.0，调用耗积分。**定位是「给你的应用接入的集成能力」（构建生图智能体等），不是平台替用户产设计资产**。
- 产物去向（教程实测路径，[tutorial_create_picture_book_generator](https://docs.coze.cn/tutorial_create_picture_book_generator)）：生成图经对象存储 API 上传，"生成的图片将存储在扣子编程平台的对象存储空间中，**用户可以在网页上下载生成的图片**"。
- **与「扣子」（coze.cn AI 办公平台）严格区分**：平台直接替用户产设计物（海报）＋设计画布编辑的能力在 coze.cn 侧（skills_market SEO："AI 设计、AI 生图"；cozespace_poster 教程：**多风格批量生成**＋海报编辑器画布改字改布局；文生图=Seedream 3.0/4.0）——扣子编程侧没有这个面。**同一生态两产品分层：办公侧有设计交付物线、编程侧只有设计引导＋集成生图**。

### 6.3 词汇（扣子编程官方原词）

**设计引导**（开关）、**原型 / 产出原型 / 产品原型**、**预设的设计风格**、**原型设计**（技能）、**前端设计**（技能）、**「AI 味」**（官方术语，指生成物视觉劣化）、**生图大模型 / 豆包生图模型 Doubao-Seedream-4.5/5.0**、**图片生成、视频生成**（官方技能/内置集成）、**图文生成**、**对象存储**（产物去向）、**官方技能 / 编程技能**。扣子（coze.cn）侧另册：**AI 设计 / AI 生图 / 设计画布 / 海报编辑器 / 多风格批量生成**。

## 7. Kimi 网页（kimi.com）

### 7.1 图片生成与设计产品面（复核口径见 §10）

- **图片生成（帮助中心单源引述）**：仅 **Agent 模式**生效、无独立按钮——"Kimi 会根据上下文自动决定是否生成图片，无需手动选择或触发"；参数=配色方案/风格/宽高比；场景=配图/海报/社交媒体图片；**会员限定＋每日生成上限**；模型名未写明。**注：该帮助页 slug 现已失效（站点重构转搜索页），落稿人复核未命中，单源引述降级标注**。
- **K3 原生多模态（官方博客，已复核）**："its **native multimodal architecture understands text, images, and video within the same model**"——生图为模型自研多模态能力而非外挂；"Kimi K3 is also particularly effective at producing **infographic-style presentations**, such as the fully editable heatmap and annual report"；网页自检="**seamlessly iterating between code and live screenshots**"（vision in the loop，与前件调研一致）。
- **K2.6 Coding-Driven Design（官方博客，已复核）**：章节名即 **"Coding-Driven Design"**（词汇谱系好样本）——"Kimi K2.6 can turn simple prompts into complete front-end interfaces, generating structured layouts with **deliberate design choices such as aesthetic hero sections**… With strong proficiency in **leveraging image and video generation tools**, Kimi K2.6 supports the generation of **visually coherent assets** and contributes to higher-quality, more salient hero sections."（子代理报告引的 "hero banners, background images, and brand assets like logos" 原句在当前页面版本**复核未命中**，以现行正文为准，见 §10）。
- **Kimi Design / 设计展示页（已复核）**：showcases/design 页官方描述"只需一个提示词，即可生成**海报、研究图表、产品图和视觉素材**，还可做同款"——设计作为 K3 能力的一等出口（海报/信息图/研究图表/产品图）；官网另有 /design 产品页（SPA 正文不可得，SEO："智能调用多种工具，轻松完成内容生成与页面排版，一键制作海报、信息图和社交媒体帖子"）。
- **Kimi Slides 多稿挑选（帮助中心单源引述，降级标注）**：流程=一句 prompt→确认大纲→选「主题与风格」→"每次生成 **2-4 个网页幻灯片方案**，用户可以在缩略图中浏览各方案的效果，选择最符合需求的一个继续生成"→画布模式（拖拽/旋转/缩放元素）→播放导出；**这是六家中明确的「一次 2-4 稿网格挑选」形态**。营销页「生成式设计｜Generative Design…零素材库依赖」「字体系统与调色板」——该页现为应用壳，原文案复核不可得（单源引述）。
- **建站素材生成**：官网动词="规划、**搜索素材**、编排模块并生成完整网页"（检索＋生成混合）；K2 Coding Plan 指南：风格定制走 `tailwind.config.js + index.css` 代码态 token、不建资产库。

### 7.2 Kimi 设计能力小结

图片生成有（Agent 模式自动触发、会员门槛、自研多模态底座）；设计交付物面有且在产品化（Kimi Design 一键海报/信息图/图表、Slides 2-4 稿挑选）；**设计→系统共生面基本没有**——建站走 K2.6 Coding-Driven Design（代码直出+素材生成点缀），无设计稿先行/挑选后喂系统主链路；可视化编辑=建站可视化编辑器（样式修改/排版调整）＋Slides 画布模式，无独立 Design tab。

### 7.3 词汇（Kimi 官方原词）

**图片生成**（Agent 模式）、**配色方案 / 风格 / 宽高比**（生图参数）、**Coding-Driven Design**（K2.6 博客章节名）、**visually coherent assets**、**海报 / 信息图 / 研究图表 / 产品图 / 视觉素材 / 同款**（showcases/design）、**Kimi 设计 / 设计灵感库**（/design SEO）、**主题与风格 / 方案 / 画布模式 / 风格预设**（Slides）、**生成式设计 Generative Design / 零素材库依赖**（营销页，复核不可得单源）、**搜索素材 / 编排模块**（建站）、**原生多模态**（K3）。

## 8. 跨家共性归纳（能力→形态因果链）

1. **设计稿的定义权：正主已把「设计稿」升格为「系统的规格」而非图**。Replit 官方一句话钉死——"A design frame is never just a picture, **it's the spec for real software**"；Lovable 的 design directions 是 "lightweight HTML and Tailwind preview"（真实现不是位图）；扣子的原型挂在 PRD 链上（"无法生成合适的 PRD **和产品原型**"）。**六家没有任何一家把「位图」当设计稿主形态**——设计稿＝可交互/可实现的轻量实现。这对「设计先行路线」的产物形态裁决（#273）是关键证据。
2. **设计先行是少数派（3/6），但三家强度成谱系**：Replit 全链（双入口同项目、探索、挑选、转正）> Lovable 轻量版（三方向＋锁定＋构建，只在首条 UI 消息触发）> 扣子黑箱版（技能驱动、机制未公开）。v0 是反向样本——初代三稿竞选主动退役、只活在 SDK "Classic v0" 复刻里：**多稿主链路有维护成本，行业有人做了又收**。判断尺读数：不是行业都有→不构成「标配缺口」；做不做取决于我们自己的双终点定位，而非行业压力。
3. **图片生成是标配底座（5/6），且全部外采头部模型**：OpenAI GPT Image 2（Lovable 构建+app 内两线）、Nano Banana/Pro 即 Gemini Image 系品牌名（v0/Bolt/Replit Canvas 面板）、Imagen 4（Replit agent 内生）、Seedream 4.5/5.0（扣子）、Veo 3.1/Seedance（视频）——**业界无一家自建生图模型**（唯 Kimi 是自研多模态顺带出图，属模型禀赋不是平台决策）。**对我「出图机制选型」（#270/#275）：外采图像模型是行业统一答案；Replit 的 Vector graphic 路线（LLM 直写 SVG 做 logo）是扩散模型外的第二条路**。
4. **图片产物去向标配三件套**：直落项目资产（Replit "saved directly to your project files"、Lovable Asset storage＋Files 画廊、Bolt 自动 WebP 入项目）＋画廊/文件区可视（三家）＋可下载（Lovable standalone images、Replit 帧 PNG/media Download、扣子对象存储网页下载）。**计费全部并入平台 credit/token，无一家按张独立标价**（Lovable 按消息复杂度、v0 独立计费面、Bolt 落码 token）——对 #275 出图档位与 #276 图片管道的形态直接可抄。
5. **一致性的行业正解＝机器可读规范文件＋agent 看稿重写，四家四件收敛**：Replit `tokens.json`＋`DESIGN.md`（"readable by you, usable by Agent"）、Lovable `.lovable/design-system.json`（构建中 "catches raw colors… deviations" 强制遵守）、v0 design system 存 **skill**＋starter app 血统、Bolt 承接 Stitch 的 `DESIGN.md`（"an open-source file format designed for AI tools to read"）。**无人承诺像素级还原**：Replit 承诺 "translate cleanly into the real product"＋事后校准（"keep chatting to adjust anything that didn't translate"）、Apply 前打 checkpoint 兜底——诚实口径是「规范级对齐＋转译＋可回滚」，与 #267「桥深度=规范级起步（v1 硬要求）」拍板互证，供 #277 直采。
6. **可视化编辑的共同分界＝「确定性层免费、生成层计费」**：Replit Visual Editor "update the source code directly **without consuming AI credits**"（复杂自动升级交 agent）；Bolt visual edits "Making visual edits in the preview is **free**. Bolt uses tokens **only when you save**"（本地 DOM 预览、落码走模型）；v0 Design mode 全走生成（Apply＝序列化进 chat 跑一轮）。三种实现强度递减（确定性直改>批处理落码>全生成），**分界思想一致**：简单样式改动不过模型，结构性改动交模型。对 #275/#274 有直接参考。
7. **多稿挑选共同形态＝「并排比＋挑一张＋继续」**：Replit 帧并排（"judge them as whole screens rather than one at a time"）、Lovable 三方向并排＋refine 上限（6 次）＋重出一组、Kimi Slides 2-4 方案缩略图挑选。**三家都把「挑选」做成轻交互（点选/缩略图），把「没挑中的」保留为可回看**（Replit "never lose a direction"；Lovable 重出一组；Kimi 缩略图浏览）。与 #267「stitch 一点点画出来」过程形态正本互补，供 #274/#278。
8. **词汇面无一家用「原型/Demo」贬义化设计产物**：扣子中文「原型」是唯一用例（且挂 PRD 链）；英文正主词是 design direction（Lovable）/ Design frame＋mockup（Replit）/ 方案（Kimi 中文）。「artwork」「brand kit」六家无一用作功能词。详见 §9 词汇对照表。
9. **反例样本两枚**：Lovable **Themes**（品牌级颜色/字体/间距套件）2025-11 上线、2026-03 因 "low usage and performance issues" 移除——品牌级主题作为独立功能被市场证伪；v0 初代三稿竞选退役——多稿主链有维护成本。**未来增强备案时两枚都值得引**。

## 9. 对本平台双终点的映射

本平台现状基线（#267 Notes 2026-10-03 探查，零基建）：模型栈纯文本（仅 DeepSeek、provider 白名单硬编码、无图片生成/视觉输入）；沙箱纯 Node 运行时无出图基建（无 headless 浏览器/截图/图像库）；前端零图片呈现面（消息模型纯文本、SSE part 封闭五种全文本、附件有壳无管）；文件区点看拒收非文本（PRJ_023）、无单文件下载。设计线=开新产物类别线；图片管道（上传/部件/预览/下载）是公共底座（票 #276）。

**双终点**（#267 开图口径）：①设计即交付——终点=图/设计资产，倾向走完整商业流（正式裁决 #272）；②设计服务系统——被选设计稿喂系统生成，载体正选=同项目双终点（正式裁决 #273）；桥深度=规范级起步（v1 硬要求）。

（各家证据对双终点的映射如下；裁决归 #272/#273/#274/#275/#277，此处只列证据与两档代价定性。）

### 9.1 终点一「设计即交付」（产物=图/设计资产，走商业流——正式裁决 #272）

- **行业有完整先例，但形态分层**：①**平台直接卖设计产出**的产品面存在——Kimi Design 一键海报/信息图/研究图表/产品图（showcases 页官方口径）＋Kimi Slides 2-4 稿挑选后交付；扣子生态的海报线（多风格批量生成＋画布编辑）在 coze.cn 办公侧而不在编程侧。②**构建平台的图片资产出口**——Lovable "standalone images that are not part of your app, like a **logo or a poster**. Ask for one and **download it** from the chat or the Files tab."（logo/海报不是应用一部分、可单独下载=事实上的设计交付物出口）；Replit 帧导出 PNG＋media Download。
- **商用授权口径参照**（Lovable FAQ）："Your apps, code, and the content you create with Lovable are yours… **use them commercially**"＋IPTC AI 生成元数据（平台会读并标注、不影响归属）——**「归用户＋可商用＋AI 溯源标注」三件套是现成口径**，供 #272 商用授权备案与 #279 运营面对照。
- **交付物谱系证据**（#272 票面问的「图案/品牌资产类 vs UI 设计稿类」）：行业两侧都有实例——品牌资产类（logo/favicon/OG 图：Lovable 发布链自动消费；Replit 双通道 logo；K2.6 visually coherent assets）、平面设计类（海报/信息图：Kimi Design、扣子 coze.cn）、UI 设计稿类（Replit Design frame 可留画布作参照物导出，但**六家没有一家把「UI 设计稿卖给用户自己的开发团队」做成交易物**——最接近的是 Figma/Stitch 生态位，不是这些平台）。**判断尺读数：图案/品牌资产类有行业同构物；UI 设计稿交付类无人做**——若做属行业首例，需自行承担论证。
- **计费形态**：无一家按张独立标价（全并入 credit）；Kimi Slides 按次（非会员 3 次/月）。供 #272 下单冻结物/报价物与 #275 档位参照。

### 9.2 终点二「设计服务系统」（被选设计稿喂系统生成——载体正选=同项目双终点，正式裁决 #273）

- **Replit 是完全对位的正主先例**，逐点对表 #273 票面：①**同项目双终点**——"Every Replit project has two entry points… **They open the same project, so nothing you design is stranded in a design tool**"（设计不搁浅在设计工具里=同项目共生的官方理由）；②**设计先行是项目内可选路线**——不走 Design 直接 Build 永远在（与我「生成无门口径不变」同构）；③**流程分岔形态**——探索（suggestions/换模型/五稿并排）→挑选（人眼比帧）→转正（Build 新建 / Apply 重写既有）；④**动线语义**——选中帧自动附到下一条消息、"Agent will prompt you to convert when you try to add a database to a mockup"（被动转正触发）；⑤**反向亦通**（Build 完成后回 Design restyle）。**#273 可直接引为同构正本**。
- **Lovable 是轻量同构**：三方向→锁定（Submit）→构建，只在首条 UI 消息默认触发（局部可再要三变体）；**扣子是黑箱同构**：开关式设计引导，原型机制未公开。三家合成谱系：**设计先行的介入深度光谱＝可选开关（扣子）< 默认首轮（Lovable）< 常驻双入口（Replit）**——#273 裁决「项目内可选路线」的介入深度时有三个档位可对表。
- **一致性（v1 硬要求=规范级，#277 正本）**：行业四件机器可读规范先例（§8.5）＋「看稿重写＋转译口径＋checkpoint 兜底」机制（§2.3）；**无一家有「像素级保证」话术**。对我现状（模型栈纯文本、无视觉输入——agent 看不了稿）的直接含义：**Replit 式「Agent 看稿重写」依赖视觉输入能力，我平台当前不具备**；但「规范文件喂 agent」路线（DESIGN.md/design-system.json 同构物=设计稿提炼成机器可读规范文本）不需要视觉——**规范级桥的最低实现与纯文本模型栈兼容**，这是六家证据里对我们最重要的一条可行性判断（最终选型归 #270/#271/#277）。
- **产物形态（#273 PRD/切片/run 对应物）**：行业设计稿主形态＝可交互轻量实现（HTML+Tailwind 预览/mockup 帧）而非位图——若我平台设计 run 产「可交互设计稿」，与「沙箱内生成系统」的基建同源（都是 Web 产物），与位图管道（#276）是两条不同的基建线。

### 9.3 各能力面对我平台的代价定性（速查，#59/#267 两档口径）

| 证据映射 | 档位 |
|---|---|
| 图片生成（外采图像模型 API） | 需动服务端（provider 白名单＋图像部件） |
| 图片管道三件套（资产落库＋画廊＋下载） | 需动服务端（#276 正本） |
| 设计稿=可交互 HTML 轻量实现 | 与生成系统同基建（沙箱已有 Web 运行时），呈现层需图片/预览部件支撑 |
| 规范文件路线一致性（设计稿→机器可读规范→生成遵守） | 现能力可起步（纯文本模型兼容），遵守链路治理需设计 |
| 可视化样式编辑（确定性层） | 前端为主（预览网关注入，圈注锚定同源） |
| 多稿挑选（并排比＋挑一张） | 前端为主（产物部件化后自然获得） |

### 9.4 词汇对照表（供 #272 词汇谱系裁决）

| 平台 | 设计产物叫什么 | 设计先行流程词 | 图片/资产词 | 可视化编辑词 | 多稿词 | 已弃/退役词 |
|---|---|---|---|---|---|---|
| **Replit** | **Design frame / mockup**（"the spec for real software"） | Design / Build（双入口）、Restyle、Build this design / Apply | Image / Vector graphic / Generate media / brand assets（design system 内） | Visual Editor、Draw | suggestions、**"five different designs"**（帧并排） | Design Mode（2025-11 模式词，Agent 4 后 "no longer a separate mode"）、versions / convert the winner（404） |
| **Lovable** | **design directions**（HTML+Tailwind 轻量预览） | Design guidance、design questions、Submit | image generation、**standalone images**（logo/poster）、Asset storage、Files tab 画廊 | preview toolbar（Select elements / Edit text inline / Draw annotation） | 三 directions、refine（≤6 次）、generate another set | Themes（已移除）、Visual edits（被 preview toolbar 取代）、Figma Import（已移除） |
| **v0** | （无现行设计稿物；输入侧 mockup/Figma/Paper） | 无 | generate_image（agent-action）、Nano Banana (Pro) | **Design mode / Design tab**、design panel、pending edits、Apply | （Classic v0 "3 variations (A, B, C)" 已退役） | Classic v0 / three-generation workflow |
| **Bolt.new** | （无内置；Stitch 侧 mockups） | Import from Figma（Anima）、Use Google Stitch designs | Generate AI images、stock images、Nano Banana | visual edits、Select tool、Save changes | 无（Stitch 侧） | ——（"Design Mode" 从未是官方词） |
| **扣子编程** | **原型 / 产品原型**（中文，挂 PRD 链） | **设计引导**（开关）、预设的设计风格 | 生图大模型（豆包 Seedream）、图文生成、图片生成/视频生成（集成技能） | （圈注为前件；无样式面板词） | 风格选择（预设库） | —— |
| **Kimi** | 海报 / 信息图 / 研究图表 / 视觉素材（Kimi Design） | （无；Slides 线：主题与风格） | 图片生成（Agent 模式）、配色方案/风格/宽高比 | 可视化编辑、画布模式（Slides） | **方案（2-4 个）**、缩略图挑选 | OK Computer（旧模式名） |
| **本平台现状（CONTEXT.md）** | ——（设计线未开；「系统」avoid 原型/Demo/样品/演示版） | 生成（无门口径）、迭代 | 物料（上传物）、附件 | 圈注（选择/圈选） | —— | 原型/Demo（系统词条 avoid 面） |

**裁决要点提示（证据面）**：①「原型」在中文生态有扣子一例（挂 PRD 链、非贬义），但我 CONTEXT.md「系统」词条已在用户可见面 avoid「原型」——若设计线再引入「原型」一词将与其撞面（#272 票面已列此冲突）；②行业高频中性词=设计稿语义下的 design directions / frame / 方案 / 风格，「设计稿」作为上位词在六家均无直接对应单一英文词（最接近 frame/direction）；③「素材」在中文生态（Kimi/扣子）指图片/资产混合体，与我「物料」词条（上传物）语义相邻需防混。

## 10. 未证实事项备案（如实，不推测）

**Replit**：①`whats-changed-agent3-to-agent4` 原文 404（二手转述不承重）；②image generation 发布 blog 404（2025-08-15 日期系搜索引擎摘要，中高）；③**Design frame 底层技术形态**（HTML mock？带不带代码结构？）文档只说 "Static / interactive mock"，未披露实现；④Generate 面板模型阵容官方自注 "changes frequently"（时效性）；⑤agent 内生通道 "powered by Google's Imagen 4" 与 Canvas Generate 面板模型表（Nano Banana 系等）两套口径并存、统一关系官方未说明；⑥`/design/versions`、`/design/convert` 已 404（"三版本帧/convert the winner" 历史形态靠文档考古，现行产品是否保留该交互未证实）。

**Lovable**：①构建线（线 A）完整模型路由表未披露（只知 premium/standard=GPT Image 2）；②**一次多稿挑选无文档**（唯一痕迹 "image variants created in the visual editor" 一句，交互形态未文档化）；③图片生成最早上线时间（changelog 覆盖自 2024-12，最早条目 2025-11-26）；④生成图默认分辨率/尺寸；⑤logo 能否导出 SVG（favicon 有自动转 .ico 说明、logo 无）；⑥构建线文件格式细节（PNG/WebP 语境出自 app 内线）。

**v0**：①Design mode 精确上线日（官方 changelog 网页只回溯 2025-10-01；"≈2025-06 中旬"系官方社媒内容被第三方收录，二手）；②现行 Apply 是否耗 credits（发布期 "without spending any credits" 为二手口径，当前文档未写计费）；③generate_image 产物落盘目录（仅「相对路径渲染」间接证据）；④Nano Banana 底层是否 Gemini 系（官方只用品牌名）；⑤**agent-action schema 写 "See documentation for the registry of known names" 但该 registry 页面在全库检索中不存在**（官方文档引用悬空）；⑥「现行无 per-prompt 多方案」系全量文档＋一年 changelog 反证＋SDK "Classic v0" 复刻口吻推定（无正面官方退役声明）。

**Bolt.new**：①图片生成背后模型未披露（官方只点名编辑用 Nano Banana）；②**bolt.diy「曾有 Design Mode 开关」传闻证伪记录**：bolt.diy 全历史（README 128 提交、v0.0.1~v1.0.0 全 release、各版设置面板源码、全版本文件树）查无此功能，传闻措辞 GitHub 全站 0 结果；唯一无法彻底排除的窗口=上游 stackblitz-labs/bolt 仓库已 404、archive.org 被网络策略阻断——即便上游曾有，bolt.diy 自 fork 起从未携带，结论成立；③createwith.com 运营主体未核验（其报道按二手对待）。

**扣子编程**：①**design-canvas 技能 ID 公开文档查无**（官方只见「原型设计」「前端设计」技能名；对应关系仅走查线索）；②**原型的形态**（图片/可交互 HTML/静态页）官方未公开；③**原型→代码对齐机制**（视觉对齐还是转提示词）未公开；④平台级替用户产设计资产的能力未见（生图=应用内集成定位）；⑤设计引导开关位置与自定义风格描述是否支持未描述；⑥code.coze.cn 产品内界面（技能列表/开关）登录墙未取证。

**Kimi**：①**帮助中心多页 slug 已失效**（站点重构转搜索页，2026-10 实测）——「图片生成仅 Agent 模式生效/会员每日上限/配色-风格-宽高比参数」「Slides 一次 2-4 个方案/画布模式」等出自子代理调研时抓取的帮助页，**落稿人复核不可达，单源引述降级标注**；②K2.6 "hero banners, background images, and brand assets like logos" 原句在现行页面版本**复核未命中**（现行正文口径=§7.1 所引 Coding-Driven Design 段）；③slides 营销页现为应用壳，「生成式设计/零素材库依赖/字体系统与调色板」原文案复核不可得（单源）；④图片生成具体模型名未写明（自研多模态为 K3 博客推断口径）；⑤Slides 导出格式（官方仅「支持播放和导出」）；⑥/design 产品页正文 SPA 不可得（仅 SEO 元数据）；⑦K3 博客「品牌资产（logo/调色板/吉祥物/品牌使用指南）」细分清单在现行英文正文未命中（poster/infographic 命中、palette/typography/mascot 未命中）——细分清单降级为未证实，海报/信息图能力由 showcases/design 页证实。

**方法学注记**：v0 子路曾收到一版 webReader 返回的超长 design-mode 文档（含 floating toolbar/Image 属性行等段），经 curl 原始 HTML 与 .md 双重核验均不存在，判为工具端生成噪声弃用——**本快照 v0 全部细节以 curl 一手文本为准**。Kimi K3 博客 zh-hans URL 实际返回英文正文（已按英文原句复核）。

## 11. 来源与复核记录

**落稿人逐字复核清单（2026-10-03，curl .md 直抓＋python 解码，全部命中）**：

- Replit：`docs.replit.com/design/{what-is-replit-design,design-vs-build,build-your-design,frames,design-md}.md`（双入口原话、"spec for real software"、"Agent rewrites the Artifact's code to match the design"、`tokens.json`/`DESIGN.md` 双表示句）；`features/agent/image-generation.md`（"powered by Google's Imagen 4"）。
- Lovable：`docs.lovable.dev/features/design-guidance.md`（三 directions、HTML+Tailwind 预览、"refine up to six times total"、generate another set）；`features/projects/chat.md`（"Lovable picks a quality level for each image"、standalone images 可下载句）。
- v0：`v0.app/docs/design-mode.md`（pending edits、Apply 序列化进 chat 全段、Tailwind-aware）；`v0.app/changelog`（Nano Banana 图片编辑挂 design mode 句、Nano Banana Pro via AI Gateway、Editor modes 枚举、相对路径渲染、Design mode 可删除元素）。
- Bolt：`support.bolt.new/building/visual-edits.md`（"Making visual edits in the preview is free…only when you save"）；`integrations/figma.md`（"Bolt uses Anima"）。
- 扣子：`docs.coze.cn/guides_vibe_coding_faq.md`（设计引导三步全段）；`guides_internal_integrations.md`（豆包 Seedream 4.5/5.0）。
- Kimi：`kimi.ai/blog/kimi-k2-6`（Coding-Driven Design 全段、"leveraging image and video generation tools"）；`kimi.ai/zh-hans/blog/kimi-k3`（native multimodal、infographic、code and live screenshots）；`showcases/design`（海报/研究图表/产品图/视觉素材，webReader 渲染）。
- 复核未命中如实降级处：Kimi 帮助中心系列、K2.6 hero banners 句、slides 营销页（见 §10）。

**关键来源索引（一手为主，按家）**：

- Replit：docs.replit.com/design/ 全章节 25 页（what-is-replit-design、design-vs-build、canvas、frames、core-components、elements、toolbar、visual-editor、explore、explore-suggestions、refine、build-your-design、design-systems-explained、create-a-design-system、apply-design-system、design-md、library-panel、import-figma-designs、import-claude-designs 等）；docs.replit.com/features/agent/{image-generation,overview}；docs.replit.com/updates/ 2025–2026 全 92 期（关键期 2025-03-21/2025-11-21/2026-03-13/2026-07-31/2026-09-25）；replit.com/blog/{introducing-agent-4-built-for-creativity,introducing-replit-design}；路径考古实测（308/404 记录）。
- Lovable：docs.lovable.dev（features/{projects/chat,design,design-guidance,design-systems,business/design-templates,publish,preview-toolbar,labs}、integrations/{ai,figma,logo-dev}、introduction/{faq,plans-and-credits}、glossary、llms-full.txt 全站镜像检索）＋changelog 全量日期对齐（关键期 2025-11-26/2026-01-16/2026-03-16/2026-05-18/2026-06-22/2026-07-08/2026-07-09/2026-09-23）。
- v0：v0.app/docs（design-mode、design-systems-2、design-systems-legacy、figma、paper、versions、faqs、quickstart、agentic-features、images-and-videos）＋v2 API 参考（agent-action/generate_image schema）＋v0.app/changelog 全量（2025-10-01～2026-09-22）＋pricing/首页 HTML＋SDK "Classic v0" 示例文档。
- Bolt：support.bolt.new（building/{images,visual-edits}、integrations/{figma,google-stitch}、building/design-system/*、release-notes 全文、sitemap/llms.txt 索引）＋bolt.new 首页 chunk 佐证＋github.com/stackblitz-labs/bolt.diy 仓库 API（git trees、README 128 提交全历史、v0.0.5 FeaturesTab.tsx、provider 目录、releases）。
- 扣子编程：docs.coze.cn（guides_vibe_coding_{faq,web_app,overview}、guides_using_skill、guides_internal_integrations、tutorial_create_picture_book_generator、guides_release_note）＋code.coze.cn 官网＋coze.cn/skills_market（生态区分）＋cozespace_poster/tutorial_seedream4_prompt（扣子侧）。
- Kimi：kimi.ai（blog/kimi-k2-6、zh-hans/blog/kimi-k3、zh-hans/showcases/design、zh-hans/design SEO、agent 营销页、slides/websites 帮助与营销页〔部分失效，见 §10〕、blog/k2-coding-plan 指南）；二手：月之暗面微信稿转载（高）、CSDN/什么值得买实测（中，不承重）。
- 二手可信度档总注：官方社媒被第三方收录（v0 design mode 上线语）＝中高；媒体实测（量子位等）＝中高；聚合站评测（vibecoding.app/designtools.fyi 等）＝中，仅佐证存在性；讹传源（"Design Mode" 归于 Bolt）＝低，已在 §5/§10 驳正。
