# 调研报告：UI 美化路径——竞品 UI 工具链、防千篇一律与第三方设计 skill 可用性（票 #512）

> 票 #512（research），2026-11-15，地图 #510「UI 美化研究后裁」条（顾虑＝平台统一设计语言致千篇一律；重点参考 Stitch、AI Studio）。
> 四路取证：①竞品 UI 保障机制；②本平台「设计规范三件套」扩全域「每项目 AI 生成式设计语言」的可行面；③第三方设计 skill（taste／uupm）形态·许可证·可安装性·口碑；④DeepSeek vs 其他模型的 UI 审美差异证据。
> 方法：外部结论一手来源优先（官方 docs/blog/changelog/产品页/GitHub 仓库/API），逐条带 URL＋访问日期（**全部 URL 访问日期 2026-11-15**）；可信度分层标注：〔一手〕〔榜单〕〔社区口碑〕〔个例〕〔无一手证据，推断〕。仓库机制事实标 `file` 来源；第二节逐条区分【事实】／【推演】。
> 前置关联（交叉印证、不重复展开）：`docs/research/2026-10-03-design-code-consistency.md`（规范级桥「物化＋每轮读＋接地＋扫描纠偏」四件套取证）、`docs/research/2026-10-03-design-stitch-deep-dive.md`（Stitch 过程模型与 SDK 五轴变体）。
> 范围注记：地图提到的参考对象 AI Studio（纯 Demo＋localStorage、快而美）不在本票四问取证清单，其形态已在地图共识内、旧 Stitch 报告录有「Stitch 导出 AI Studio」管道事实，本报告不重复取证。

## 结论速览

1. **竞品没有一家靠模型审美，也没有一家靠随机化**。五家里四家（Replit/Lovable/v0/Bolt）把「好看」外置为同一副基座——React＋Vite（或 Next/TanStack Start）＋Tailwind＋shadcn 系组件；把「不千篇一律」外置为**用户可选、多套并存的主题／设计系统层**（Replit Themes、Lovable 20 风格方向＋design systems、v0 Design Systems 2.0＋五大公版系统、Bolt 设计系统选择器＋预置 Porsche/Cloudscape）；Stitch 走设计稿路线但同构——theme selectors＋prompt 指定配色＋多变体并行。**对地图顾虑的直接对答：v0 官方做过同一 prompt 只换挂载设计系统的实验——不挂系统时产出「调色板散架、乱造图标」，挂不同系统时同 prompt 出完全不同风格。「统一规范致千篇一律」被证伪，前提是规范层多套可换；反过来「不注入」得到的不是多样而是随机 slop，比「统一但单调」更糟。**
2. **三件套扩全域可行、且改动面比顾虑小**：遵守三件套（平台确定性写入基座／执行体协议短条目注入／收口 token 合规扫描）与「规范来源」是解耦的——机制只吃「规范文件」这个输入，不关心文件从哪来【事实＋推演，见 §2】。现状卡点是设计入口收起（#310）后规范来源枯竭、三件套闲置；扩全域＝新增「每项目生成式设计语言」来源（Stitch 式配色先行：访谈/PRD 期产风格方向→确定性物化 token），遵守机制零新建。基座技术栈已是 Tailwind＋shadcn，与行业收敛同构，落点零新建（旧报告 §4 已核）。
3. **taste 与 uupm 都是标准 SKILL.md 形态、都 MIT、都可快照安装进本平台技能体系**；差别在代价：uupm 轻（Python 3 标准库离线、8.4 MB），taste 重（Node 22＋本机 Chrome＋76 MB 资产＋Stop hook 依赖，官方自述单次构建 30-45 分钟／几十万 token）。许可证各有陷阱：uupm 的 npm 安装器单独 CC-BY-NC-4.0 禁商用（只可取仓库内容）；taste 内置照片按 CC 协议须连带署名。口碑有分歧（taste 有 2.5× 成本的负面盲测个例；uupm 缺独立深评）——建议接入前自做内部盲测再定转正。
4. **「DeepSeek UI 审美弱于 Claude/GPT/Gemini」部分成立，是档位差而非能力有无**：WebDev Arena 上最强 DeepSeek（V4.1-Flash，#12、开源第一）与头部稳定差 80-140 Elo〔榜单〕；官方曾在 V3-0324/R1-0528 两次把「生成的网页更美观」列为版本卖点〔一手〕。但 DesignArena 上 DeepSeek 快档挤进 top5（样本尚小）〔榜单〕；「黑白/性冷淡」这一具体特征**无任何社区一手证据**——更可能是「默认保守审美＋平台未注入任何设计规范」的叠加〔推断〕。规范注入是业界标准做法（Anthropic 官方 frontend-design skill 的存在本身就是官方承认默认产出是 slop 水准）〔一手〕。平台 A/B 通道已备（运营配置面改档不发版，`docs/agents/demo-stage-model-tier.md`）。

---

## 一、竞品 UI 保障机制：怎么做到好看且不千篇一律

### 1.0 行业总判（先给结论）

- **技术栈收敛**：除 Stitch 外，四家全部收敛到 React＋Vite（或 Next.js/TanStack Start）＋Tailwind CSS＋shadcn/ui 系组件。「好看」不靠模型审美，靠把审美外置为 Tailwind token＋组件库层。
- **多样化机制收敛**：没有任何一家宣称使用「随机化/自动多样化」；「不千篇一律」的解法全部是**把风格做成用户可选的独立层**（主题／设计系统选择器），与保下限的组件基座分立——底座保好看，风格层供差异，二者不矛盾。
- **规范落地靠校验器不靠提示词**：Lovable 的 adherence checks（扫裸色字面量即自动重试）与 v0 的「每次构建对照真实源码校验 prop/token/icon 存在性」都是生成后校验闭环——弱模型品味不可靠，但可被机械校验兜住。

### 1.1 Replit（Replit Agent）

- **技术栈**：官方博客实锤基于 shadcn 主题体系——「We embedded the open source project TweakCN directly into our theme editor… because **TweakCN is the best way to customize Shadcn themes**」〔一手〕https://replit.com/blog/introducing-comprehensive-design-support-for-ai-apps 。React+Vite+Tailwind 为社区口径（官方 docs 未声明默认栈）〔社区口碑〕https://docs.replit.com/replitai/agent 。
- **主题/token 机制**〔一手，同上博客〕：Themes 全用户可用——「manage colors, fonts, and UI properties across your entire app with a single update」，可保存主题复用到未来任何 app；Enterprise Beta 支持导入 Figma 设计系统与「design tokens＋规则」包；官方支持「multiple design systems or themes for different teams or use cases」并存。
- **多样化解法**：多主题/多设计系统按团队与用例并存；Design Canvas 先出 mockup 再落码〔一手〕。无随机化机制。社区观察默认产出仍趋同、用户讨论如何让 Agent 出「clean UI」〔社区口碑〕。

### 1.2 Lovable（lovable.dev）

- **技术栈**〔一手〕：官方 FAQ——2026-05-13 起新 app 用 TanStack Start（SSR）、旧 app 用 React＋Vite；「**Lovable uses Tailwind for styling**」。shadcn 为旧版官方文档指令＋社区一致口径，现行官方口径仅明确到 Tailwind。https://docs.lovable.dev/introduction/faq
- **设计保障**〔一手〕https://docs.lovable.dev/features/design-systems ：Design systems（付费）＝专用 Lovable 项目，发布时生成 `design-system.json`（tokens、组件目录、stack 约束的机器可读 schema）＋渲染规则文件；连接项目后**每次生成都读**；**Adherence checks** 每次生成扫描四类违规——「Raw color literals where a design system token should be used、one-off values bypassing tokens、inline styles override defaults、local component implementations」——发现即自动重试修正；workspace 可设默认设计系统。另有 Visual edits 可视化微调（https://docs.lovable.dev/features/design ）与 Knowledge 持久注入品牌/设计规范（https://docs.lovable.dev/features/knowledge ）。
- **多样化解法**〔一手·关键〕：官方提示词指南承认默认脸存在并教用户对抗——「**Get the design right first**: Your visual language is a foundation, not a polish layer」；「Use buzzwords to dial in aesthetic… "minimal," "expressive," "cinematic," "playful," "premium"… they meaningfully influence typography, spacing, shadow, border radius, and color palette… avoid the **default-UI look**」；官方 prompt library 含 **20 个风格方向**现成设计提示词。https://docs.lovable.dev/prompting/prompting-one
- **判读**：Lovable 的答案不是「要不要统一规范」，而是「**先选风格方向，再让规范跟着走**」——与本票「配色先行」设想同构。

### 1.3 v0（v0.app，Vercel）

- **技术栈**〔一手〕：发布博客官方原话「**v0 produces code using open-source tools like React, Tailwind CSS, and Shadcn UI**」https://vercel.com/blog/announcing-v0-generative-ui ；FAQ 声明能力不锁定（可写 Svelte/Vue/Bootstrap/MUI，「Is v0 useful without Tailwind? Yes.」）https://v0.dev/faq ；shadcn 官网每个组件「Open in v0」双向官方集成 https://ui.shadcn.com 。
- **设计保障**〔一手·Design Systems 2.0，2026-06-25 官宣〕https://x.com/v0/article/2075589636227735902 ：「Instead of reshaping your system to fit v0, you can **give v0 your real design system**. Feed it your docs, your source code, an app that consumes it, your Figma, Storybook and more.」导入即生成 skill（**SKILL.md 规则**＋starter 脚手架＋references＋v0.json）；**每次构建对照真实源码校验**——「reads your actual component source, mounted read-only, and checks every piece against it: the prop exists, the token name resolves, the icon is actually exported」；每个 app 从真实 starter 起步（provider/字体/全局样式预接好，「on-brand from the first render」）。内置可试用：GitHub Primer、IBM Carbon、Microsoft Fluent、Google Material 3、AWS Cloudscape。
- **多样化解法＝官方实验（本课题最有力的官方证据）**〔一手，同上长文〕：v0 团队用同一 prompt 跑 6 次只换挂载的设计系统——不挂系统时「a **palette that doesn't hold together**, three or four different greens that belong to no scale, **made-up icons**… It's the ceiling of building from memory instead of from a system」；挂不同系统则动效、token、暗色映射、组件行为忠实跟随该系统。旧 theme builder 亦有合作方记录佐证（feed design tokens 进生成以得「more custom looking interface」）〔社区/合作方口碑〕https://robotostudio.com/blog/how-our-studio-uses-v0 。

### 1.4 Bolt.new（StackBlitz）

- **技术栈**〔一手〕：官方 use-case FAQ 原话「**Bolt.new generates modern web apps using React, Vite, Tailwind CSS, and Node.js by default**」https://bolt.new/use-cases/ai-prototyping-tools 。默认组件库未官方声明；社区观察存在纯 Tailwind 模板与 nextjs-shadcn 模板两类〔社区口碑〕。
- **设计保障**〔一手·官方 Release Notes〕https://support.bolt.new/release-notes ：Paid Teams 可上传自有设计系统，Bolt 解析文档与组件源码生成 Storybook 式参考、构建 UI 时引用；首页有 **Design System selector**；「**All users can explore and build with pre-loaded design systems, including Porsche, Cloudscape, and others**」；项目级随时 attach/detach/切换；Visual edits（预览内点选改文本/颜色/字体，编辑免费）；官方 curated Skills 含「**Web Design Guidelines**」；**Google Stitch 设计一键导出到 Bolt**（页面截图＋HTML＋预填 prompt）——设计工具→代码工具的官方管道。
- **多样化解法**：模板市场多品类多风格起跑〔一手〕https://bolt.new/resources/templates ＋设计系统选择器＋设计导入管道。「Every generated tool is unique to your prompt」为营销口径〔一手·营销〕；默认脸问题社区同样存在（"Bolt App Looks Generic? A 30-Minute De-Slopification Pass" 类文）〔社区口碑〕。

### 1.5 Stitch（Google Stitch，stitch.withgoogle.com）

- **技术栈与形态**〔一手〕：与四家根本不同——不走 React 组件库路线，产出＝高保真 UI 设计＋前端代码（「Paste to Figma」＋「Export front-end code (clean, functional)」），底层 Gemini 多模态。发布博客 https://developers.googleblog.com/en/stitch-a-new-way-to-design-uis/ （2025-05-20）；2026 演进＝AI 原生无限画布＋design agent＋Agent Manager 并行多方向＋**DESIGN.md**（agent 友好的 markdown 设计规则，可跨工具导入导出）＋「extract a design system from **any URL**」＋MCP server/SDK＋导出 AI Studio/Netlify https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design （2026-03-18）、https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-updates （2026-05-19）。
- **主题/配色机制**〔一手〕：发布博客原话「refine your design with… **theme selectors**」；官方指引「Describe the application you want to build in plain English, **including details like color palettes**」；意图先行——「Instead of starting with a wireframe, you can **start by explaining the business objective**… what you want your users to **feel**」；语音实时变体「**show me this screen in different color palettes** — as you speak」。
- **多变体是产品一等概念**〔一手＋旧报告 SDK 佐证〕：官方「generate **multiple variants** of your interface. Experiment with different layouts, components, and styles」；SDK 把发散度定死为参数——`variantCount`（1-5，默认 3）× `creativeRange`（REFINE/EXPLORE/REIMAGINE 三档）× `aspects`（**LAYOUT / COLOR_SCHEME / IMAGES / TEXT_FONT / TEXT_CONTENT 五轴**，见旧报告 `2026-10-03-design-stitch-deep-dive.md` §2.2）——配色与排版都是显式变体轴。
- **对地图表述的核对**：地图所称 Stitch「配色先行、逐页排版丰富含图片」——「配色先行」有官方支撑（theme selectors＋prompt 配色指引＋意图先行）；「逐页排版丰富含图片」在官方五轴变体（LAYOUT/IMAGES 在轴）与多变体口径有部分支撑；「先选主题再逐页生成、每页排版各异」的**逐字官方表述未找到**，属社区对产品行为的一致观察〔社区口碑，如 Codecademy 教程 https://www.codecademy.com/article/google-stitch-tutorial-ai-powered-ui-design-tool 〕。Stitch 帮助为 SPA 不可静态抓取，Edit Theme 侧栏（明暗/主色/圆角/字体）与 Standard（Flash 快、量大）/Experimental（Pro 高质、限量）双档为多源一致转述〔社区口碑·多源一致〕。

### 1.6 横向对照表

| 产品 | 组件库 | 样式方案 | 主题/token 机制 | 多样化解法 | 证据强度 |
|---|---|---|---|---|---|
| Replit | shadcn（官方博客实锤 TweakCN＝Shadcn 主题编辑器） | Tailwind〔社区〕 | Themes（色/字体/UI 属性单点更新、可存复用）；Enterprise：Figma/tokens 包导入 | 多主题/多设计系统按团队用例并存 | 主题机制一手；栈＝一手＋社区 |
| Lovable | shadcn〔旧档官方＋社区〕 | Tailwind（官方 FAQ 一手） | design-system.json＋adherence checks 扫裸色自动重试＋workspace 默认系统 | 官方 20 风格方向 prompt 库＋「先定风格再生成」官方指引 | 一手 |
| v0 | shadcn/ui（发布博客官方原话） | Tailwind（官方；非强制） | Design Systems 2.0：真实系统→SKILL.md＋starter＋每构建对照源码校验 | 五大公版系统＋自带系统；官方实验证明无系统即散架、换系统即换风格 | 全部一手 |
| Bolt | 未声明默认（纯 Tailwind 与 shadcn 模板并存〔社区〕） | Tailwind（官方 FAQ 一手） | Teams 上传→Storybook 式参考＋首页选择器＋预置 Porsche/Cloudscape | 模板市场＋设计系统选择器＋Stitch/Figma 导入管道 | 机制一手；组件库默认＝社区 |
| Stitch | 无组件库（设计稿＋HTML/CSS 导出） | 设计稿层（多模态生成） | theme selectors（官方原话）＋prompt 配色＋DESIGN.md/URL 提取设计系统 | 官方多变体（五轴含配色/排版/图片）＋Agent Manager 并行；「先选主题逐页生成」＝社区口碑 | 博客一手；产品内细节社区多源一致 |

### 1.7 对本平台顾虑的直接对答

- **「统一设计语言会致千篇一律」——被竞品实践证伪，但要加前提**：前提是「统一」落在**底座/token 语义层**（保下限），风格值（色/字体/圆角的具体取值）做多套可换。v0 官方实验的两面：挂不同系统＝同 prompt 完全不同风格；不挂系统＝「调色板散架、乱造图标」——即什么都不注入的结果不是「自然多样」而是「随机 slop」，比统一但单调更糟。
- **业界共识组合**（各家都在做，与旧报告 `2026-10-03-design-code-consistency.md` 结论 2/3 交叉印证）：a) Tailwind＋shadcn 基座；b) 多套预置主题/风格方向；c) 生成前让用户（或模型代替用户）选风格/配色；d) 生成后校验闭环（扫描纠偏）＋可视化微调。

---

## 二、三件套扩全域「每项目 AI 生成式设计语言」可行面

（【事实】＝仓库文件/词条可查；【推演】＝基于事实的机制推演，未实现。）

### 2.1 机制事实面（全部【事实】）

1. **三件套遵守机制**（`CONTEXT.md`「设计规范」词条）：带规范的生成遵守三件套——①规范值由平台确定性写入基座（非模型动作）；②执行体协议短条目注入（token 优先、禁裸色禁任意值）；③收口 token 合规扫描（违规有限重试一轮、仍违规如实标注）；调色板收窄至语义 token。规范＝双件形态（机器可读 token：色/字体/圆角，**基座语义变量集为底、品牌附加色可扩**＋人读规则文件）。
2. **规范来源＝设计执行体伴随产出、平台确定性提取**（同词条）：界面类写稿协议保证 token 集中放稿内、定稿时直提，不经模型判读。
3. **设计入口已收起**（`CONTEXT.md`「设计过程」词条；#310、ADR-0025）：上线版无设计入口、代码保留、触发律不变——即**现网新项目实际无法成为「带规范项目」**，三件套机制在库但规范来源枯竭。
4. **基座技术栈已是行业收敛形态**（`CONTEXT.md`「基座」词条）：TypeScript / Next.js / React / pnpm / **shadcn** / PostgreSQL / Redis，镜像预装；厚模板六层含「设计 token 注入」层——与 §1 四家的「Tailwind＋shadcn」基座同构（本基座无 Tailwind 字样但 shadcn 隐含同一 token 语义变量形态；旧报告已核基座 `globals.css` 与 shadcn 官方脚手架逐行同构）。
5. **「机制统一、长相各随项目」的分层哲学已有先例**（`CONTEXT.md`「基座健壮件」词条）：健壮件担保行为契约，「呈现主权在设计 token 与项目侧——件消费项目 shadcn/设计 token 渲染……行为统一而长相各随项目」。
6. **模型档与 A/B 通道已有**（`docs/agents/demo-stage-model-tier.md`）：运营配置面改档不发版（语义键 flash/pro，方舟 DeepSeek V4 系）、路由判定、活体对比 SOP（第六节）齐备。
7. **「像不像」语义级判定归用户人眼与圈注、不做机器评分**（设计规范词条）——已定口径，扩全域不触碰。

### 2.2 扩全域的本质＝换规范来源，不动遵守机制（【推演】，基于 2.1-1/2/3）

三件套三步只吃「规范文件」这个输入，没有任何一步依赖「文件来自设计线」：来源枯竭是 #310 收起设计入口的副作用，不是机制失效。因此扩全域的最小形态是**给三件套新增一个规范来源**——「每项目 AI 生成式设计语言」：项目首建（访谈/PRD 期）由模型产风格方向（配色/字体/圆角/风格词），平台**确定性物化**为与设计线同构的双件规范文件，此后三件套照常生效。写入、注入、扫描、收窄全部复用，机制零新建；与「设计执行体产出」并存不冲突（未来设计入口重启时设计线定稿仍可覆盖，词条「随定稿刷新」语义不变）。

### 2.3 「配色先行」（Stitch 式）的落地形态推演（【推演】）

- **竞品同构物**：Stitch 官方「先说业务目标与用户感受→theme selectors→生成」与 Lovable 官方「Get the design right first——visual language is a foundation, not a polish layer」都是「**风格先于代码**」。本平台对应物＝主智能体在五问访谈/PRD 期顺带采集或推荐风格方向（甚至从 PRD 领域词推配色，如教育/医疗/工业不同气质），起 Demo 前定格为项目设计语言。
- **多样性恰好在这一步解决**：规范来源从「平台一套默认」变为「每项目生成时从风格方向库选定/组合」（Lovable 20 风格方向、Stitch 五轴变体是同型参照）——项目间不同、项目内一致，正是「不千篇一律」且「不散架」的平衡点。可选增强：taste 的「发牌（deal）」式随机选向（§3.1）可作风格方向库的选取机制参考。
- **与生成哲学的一致性**（【推演】，对照地图 #510 锁定共识「杜绝臆想加戏」）：设计语言只定 token 与风格约束，不新增用户不可见的功能性规则——属呈现面非功能面，不与「实现深度跟信息量走」冲突。

### 2.4 现成可借力的工具链（【事实】，旧报告已核＋本次补证）

- `@shadcn/lint`（agent-first linter，Tailwind v4 即用、规则面与 Lovable adherence 同款）——正对本平台基座（旧报告 `2026-10-03-design-code-consistency.md` 结论 7）。
- Tailwind v4 官方调色板收窄机制（`--color-*: initial` 只暴露语义色工具类）——与三件套「调色板收窄至语义 token」同型（同上）。
- Google DESIGN.md（YAML token＋prose 双件、`lint` 带 WCAG 对比度、`diff` token 级回归，2026-04-21 开源）——双件形态与本平台「机器可读 token＋人读规则」同构，可作规范文件格式参照（同上＋旧 Stitch 报告结论 6/7）。
- v0 Design Systems 2.0 的「SKILL.md＋starter＋references」包装形态——若把「风格方向库」做成技能，形态先例在此〔一手，§1.3 URL〕。

### 2.5 风险与边界（【推演】）

- **生成式规范的质量不可靠风险**：模型产的配色/字体组合可能不和谐——业界对策是「预设方向库＋模型在其中选/微调」（Lovable 20 方向）而非让模型凭空配色；Stitch 的 WCAG lint（DESIGN.md）示差可机械兜底。本平台「平台确定性物化」一环（不经模型判读的提取）沿用即可保住「规范值可信」。
- **扫描收紧的误伤**：全域生效后「禁裸色禁任意值」对无规范期习惯是行为变更，收口扫描的「有限重试一轮、仍违规如实标注」既有语义已是安全阀，可先灰度（运营配置面支持按档实验，2.1-6）。
- **存量项目**：「存量不回补」是基座版本语义既有口径，设计语言同样只对新项目生效（对照地图「新项目一律五问试用开局、存量不迁移」）。

### 2.6 可行性判定

**可行**，且改动集中在「规范来源」一环（风格方向库＋物化通道＋访谈期采集触点），遵守链路（写入/注入/扫描/收窄）全复用；底座与工具链零新建。是否做、先做多大面积，归地图裁决——本票只证可行面。

---

## 三、第三方设计 skill：taste 与 uupm

### 3.1 taste（tasteskill.dev / Leonxlnx/taste-skill）

- **形态**〔一手〕：标准 Agent Skill（SKILL.md 目录形态），官方定位「open-source agent skill…for any agent that reads SKILL.md files」，支持 Claude Code/Codex/Cursor 等。https://www.tasteskill.dev/ 、https://github.com/leonxlnx/taste-skill 。内容＝`skills/taste-skill/SKILL.md` 单文件 **87,253 字节**（YAML 前言含 **Stop hook** 指向 gate.mjs）＋大资产库（starter.html、system.css、素材库）＋Node 22+ 脚本组＋references 分域文档，仓库整体 **76 MB**（大头内置照片）。
- **主打**〔一手〕：反 AI 俗套的「艺术方向＋成品验证」闭环——官网 before/after 直指 AI 默认页「Purple gradient, emoji, gradient text and three glass cards. The page every agent ships」；headless Chrome 四档宽度自检（空视觉/坏 shader/对比度/占位文本），gate.mjs 最多三轮自修复。**防千篇一律是显式核心设计**：repo topics 含 `anti-slop`；「deal（发牌）」机制——从 247 个精选站点蒸馏库里每页随机发 3 个设计方向（20 个方向）＋palette/字体/hero 发牌器，SKILL.md 原文「Deal, never choose by taste」「never violet: deal again」。图片取自 Openverse/Wikimedia 等仅 CC0/PD/CC BY/CC BY-SA 且页面内署名。
- **许可证**〔一手〕：仓库 **MIT**（LICENSE「Copyright (c) 2026 Leonxlnx」，GitHub API spdx_id=MIT）——快照入库＋内部再分发合法，仅需保留版权与许可声明；**注意内置照片受各自 CC 协议约束（CC BY/CC BY-SA 须连带署名元数据）**。https://raw.githubusercontent.com/Leonxlnx/taste-skill/main/LICENSE
- **可安装性**（对照本平台 SKILL.md 装时快照体系）：形态兼容（标准目录可整目录快照）；但三重代价〔一手·官方 FAQ〕：①运行前置 **Node 22＋本机 Chrome/Chromium**（headless 检查是核心闭环）＋网络取图；②官方自述单次构建 **30-45 分钟、几十万 token**；③**Stop hook（gate.mjs）是 Claude Code 插件机制**——本平台技能体系无 hook 对应物，收口闸一环缺失需平台侧补实现或明示降级（与本平台「收口闸假完成盲区」教训同族，见 MEMORY 实录）。
- **口碑**：GitHub API 2026-11-15 快照 **108,819 stars / 7,321 forks**（2026-02 创建，增速异常快）〔社区口碑〕https://api.github.com/repos/Leonxlnx/taste-skill ；入选多篇「Best UI Skills」评测清单〔社区口碑〕；**负面个例**——Itai Spector 盲测（2026-11-09）：成本约 **2.5 倍**于裸 Claude，且作者更愿用裸 Claude 的成品 https://medium.com/@itaispector1/the-anti-slop-skill-that-beat-impeccable-in-claude-code-just-copies-a-human-designer-hallmark-41ef9bca3e12 〔个例〕；HN 无专帖。

### 3.2 UI UX Pro Max Design Intelligence（uupm.cc / nextlevelbuilder/ui-ux-pro-max-skill）

- **形态**〔一手〕：Agent Skill＋npm 安装器 CLI＋官网三件套，主体是「可检索的设计知识数据库 skill」。https://github.com/nextlevelbuilder/ui-ux-pro-max-skill 、https://uupm.cc/ 。结构＝源目录（`data/*.csv` 数据库＋`scripts/*.py` BM25 检索〔纯 Python 标准库、离线〕＋SKILL.md 正文模板＋15 平台安装映射）；Claude 映射生成 `.claude/skills/ui-ux-pro-max/SKILL.md`＋`scripts/search.py`。数据面（README v2.x 口径）：80 个可搜 UI 风格（51 active）、192 色板、74 字体配对、120 UX 指南、**192 条行业推理规则**、34 landing 模式、1,934 个 Google Fonts 带许可元数据〔一手·README〕。注意 frontmatter 宣称数字与 README 有版本漂移。
- **主打**〔一手〕：数据驱动的设计推荐引擎——按产品类型多域并行检索，推理出整套设计系统（含「AVOID Anti-patterns」与 Pre-delivery checklist）；**反 AI 俗套是显式设计**——行业规则带反模式清单，样例直指「AI purple/pink gradients」（银行业禁用）、checklist 首条「No emojis as icons」。商业模式＝开源 Basic（本仓库）vs 官网 Premium 付费版。
- **许可证**〔一手〕：仓库 **MIT**（「Copyright (c) 2024 Next Level Builder」）；⚠ **npm 安装器 `ui-ux-pro-max-cli` 单独 CC-BY-NC-4.0（禁商用）**——**skill 内容可自由快照再分发，npm CLI 包不可商用再分发**；导入应走仓库/release 内容，不走 npm 包。https://raw.githubusercontent.com/nextlevelbuilder/ui-ux-pro-max-skill/main/LICENSE 、https://www.npmjs.com/package/ui-ux-pro-max-cli
- **可安装性**：形态兼容（仓库不直接提供拼装好的 skill 目录，可在临时目录跑一次 CLI 安装再整目录快照入库）；运行前置仅 **Python 3**（标准库、离线、无 key、8.4 MB）——环境面远轻于 taste。已知坑〔一手·README Troubleshooting〕：v2.5.1 前内部 symlink 曾致 marketplace 安装失败；Claude.ai 网页端上传 200 文件上限会拒整仓 ZIP。
- **口碑**：GitHub API 2026-11-15 快照 **144,328 stars / 15,273 forks**（2025-11-30 创建；增速异常，且组织有自家流量矩阵交叉推广——star 数≠真实使用质量）〔社区口碑〕https://api.github.com/repos/nextlevelbuilder/ui-ux-pro-max-skill ；入选多篇清单〔社区口碑〕；**无独立盲测/深评**，第三方同分对比榜未入榜（弱信号）〔社区口碑〕https://www.thetoolnerd.com/p/best-claude-skills-ui-ux-design ；HN 无专帖。

### 3.3 同类候选列名（顺带核查，不深挖）

- **Hallmark**（Together AI / Nutlope）：anti-AI-slop 设计 skill，51,856 stars、MIT、2026-04 创建〔一手·GitHub API〕https://api.github.com/repos/Nutlope/hallmark
- **Impeccable**（Paul Bakaus）：「design vocabulary for your coding agent」，社区口径 10 万+ stars（repo 精确路径未核实到，404）〔社区口碑〕
- **frontend-design**（anthropics 官方 skills 系）——见 §4.1-4，Anthropic 官方自家的设计 skill 先例。

### 3.4 对照本平台技能体系的安装判定（对照 `docs/agents/skill-audit-guide.md`）

- **形态**：两者均标准 SKILL.md 目录，装时快照体系可直接承载【事实级对照】。
- **审核面**：taste 的 SKILL.md 87KB＋脚本组——「注入面逐件读完再下结论」的审核成本高；scripts 是注入面（只对带 shell 槽位发放）——taste 的 gate/shoot 脚本组在本平台执行体槽位的发放面需逐件过审。uupm 的 search.py 是纯本地检索、注入面小。
- **双向边界**：两者都是「设计方法论」（换流派即变）——按尺该住技能层、不进内建工作协议，**与分层纪律相容**；但若地图走 §2 的「平台级风格方向库＋确定性物化」，则「风格方向库」本身是机制挂点（进平台），skill 形态只承载可选的方法论增强——两者不冲突，先后可裁。
- **建议路径**：先内部盲测（同一 brief×有/无 skill，技能 TDD 的 RED 基线思路，skill-audit-guide §五）再定转正；taste 若只用其「发牌选向＋反俗套清单」思想而不要其运行时闭环，可蒸馏为自产技能草稿（经 ADR-0022 自产晋升通道）而非整包安装——76 MB 资产与 Node/Chrome/hook 依赖全免。

---

## 四、模型面：DeepSeek vs 其他模型在 UI 生成审美上的差异证据

### 4.1 官方证据

1. DeepSeek 官方两次把「网页更美观」列为版本卖点，之后再未提及〔一手〕：V3-0324（2025-03-24）「生成的网页与游戏前端**更加美观**」；R1-0528（2025-05-28）同句；V3.1（2025-08-21）起公告全为推理/Agent 基准、无 UI 条目。https://api-docs.deepseek.com/zh-cn/updates 。V3-0324 英文 model card 亦有「**More aesthetically pleasing** web pages and game front-ends」https://huggingface.co/deepseek-ai/DeepSeek-V3-0324 。
2. 官方无任何 UI 生成示例/设计 benchmark——api-docs 只有 curl 对话示例；V3.1/V3.2 model card 评测全为数学/代码/Agent 基准〔一手·对「不存在」的确认〕。https://huggingface.co/deepseek-ai/DeepSeek-V3.1
3. **对照关键事实**：Anthropic 官方 marketplace 维护 `frontend-design` skill（社区公认为让 Claude「写码前先做审美决策、避开 AI slop」）——连 Claude 默认产出也被官方承认是 slop 水准，「审美弱」不是 DeepSeek 独有；且「注入设计规范弥补」是被官方实践背书的路径〔一手（Anthropic 侧）＋社区口碑〕。https://claude.com/marketplace/plugins/frontend-design 、https://github.com/anthropics/skills

### 4.2 榜单证据（均注明快照日期；榜单为匿名双人对比＋Elo 人偏好投票，非客观正确性，分数持续波动）

- **WebDev Arena**（快照 2026-11-11）：前五全为 Claude/GPT/Gemini（1838-1764），**无 DeepSeek**〔榜单〕。https://arena.ai/leaderboard/code/webdev
- DeepSeek 最好成绩〔榜单·官方数据集聚合，发布 2026-10-19〕：**#12 DeepSeek V4.1-Flash（1664）＝开源第一**，与 #1（Claude，1807）差约 143、与 top5 差约 80-105；DeepSeek V4 Pro #32（1582）。arena 官方 X 亦确认「#1 open model, #12 overall」。https://tokscale.ai/benchmarks/webdev-arena 、https://x.com/arena/status/2111894094448615500
- LMArena「WebDev and UI」类目（2026-06 数据，三手解读）：第一 Gemini 3 Pro、第二 Claude Sonnet 4.6；DeepSeek V3.2 总体 ~1330、Coding 类目与 GPT-5 并列第二但 **WebDev/UI 类目不在前列**——「代码逻辑强、UI 观感弱」画像〔榜单解读〕。https://qz.com/best-ai-models-ranked-millions-user
- **DesignArena**（专测生成设计美观，快照 2026-11-09）：Overall Agentic 榜 **#5 DeepSeek-V4.1-Flash（1304）**，压过 Kimi K3.1 Pro/Claude Sonnet 5.5/Gemini 4 Argon——但该条目仅 249 场对战（±6.2%）、Claude Opus 是 1667 场，样本差距大〔榜单〕。https://designarena.ai/leaderboard
- 历史波动提醒：DeepSeek-R1 曾于 2025-01 列 WebDev Arena **#2**——名次随版本与竞品大幅波动，「截至访问日」名次才有意义〔社区转述榜单〕。https://www.reddit.com/r/ChatGPTCoding/comments/1ia0yuv/deepseekr1_is_2_place_in_lmarenas_webdev_arena/

### 4.3 社区口碑（正反两面）

- 反方：官方公告反证（§4.1-1，最强非社区证据）；r/ClaudeAI 对 R1 的失望帖〔社区口碑·个例〕https://www.reddit.com/r/ClaudeAI/comments/1ibbx9k/not_impressed_with_deepseekaita ；r/codex 对 V4 Pro 的编码吐槽（非 UI 专项）〔个例〕。
- **「黑白/性冷淡」特征：无一手证据，推断**——英文（Reddit/HN）与中文（知乎/掘金）社区均未命中专门描述「DeepSeek 生成 UI 黑白、无色彩」的讨论；社区对 AI 前端的通用吐槽词汇是「AI 味/slop」（蓝紫渐变、模板化三卡片）且指向全模型。本平台的黑白素更可能是「模型默认审美保守＋生成链路未注入任何设计规范」叠加〔推断〕。
- 正方：r/DeepSeek 多模型落地页实测（V3-0324 时代）：排序 Claude 3.7 > Gemini 2.5 Pro ≈ **DeepSeek V3-0324** > o1-pro > Grok 3，作者原话「DeepSeek V3 did far better than I could've ever imagined」（但复用模板失败；终判仍是 Claude 前端最强）〔社区口碑·方法较认真〕https://www.reddit.com/r/DeepSeek/comments/1jpj70b/i_tested_out_all_the_best_language_models_for/ ；r/LocalLLaMA 0324 发布期「It's very good at front end and programming now」〔社区口碑〕；HN V3.2 发布帖（982 分）agentic 性价比好评〔社区口碑〕https://news.ycombinator.com/item?id=46108780 。
- 共识侧写：Claude 前端审美口碑四家最稳（多独立来源一致）；DeepSeek 呈**版本依赖**——0324 曾因前端惊艳口碑爆棚、V3.1 后官方不再强调、社区焦点转向 Agent/性价比〔社区口碑综合〕。

### 4.4 公开同 prompt 跨模型对比

1. NexusTrade 落地页对比（最完整公开实验）：同一 system prompt 喂 Grok 3/o1-pro/Gemini 2.5 Pro/DeepSeek V3-0324/Claude 3.7 Sonnet——Claude 第一，DeepSeek 第三且「美观度过得去、复用组件失败」：**审美与工程贴合是两个维度**〔个例〕（URL 同上 r/DeepSeek 帖）。
2. WebDev Arena / DesignArena 本身＝大规模持续同 prompt 匿名对比（数百万票级）〔榜单〕。
3. Stackademic 真实项目双模型对比（一方图表「ugly. Like, really ugly」）〔个例〕https://blog.stackademic.com/deepseek-vs-chatgpt-for-coding-i-tested-both-for-real-projects-cb801ee9a016

### 4.5 归纳与对本平台的含义

- **「DeepSeek UI 审美弱于 Claude/GPT/Gemini」＝部分成立的档位差**：最强 DeepSeek 快档与头部稳定差 80-140 Elo、WebDev&UI 类目不在前列（支持）；但 DesignArena 快档 top5（样本小）、R1 曾 #2、0324 口碑爆棚（限制条件）；「黑白素」特征无社区一手证据（不支持其为 DeepSeek 特有）。
- **版本核对是第一步**：deepseek API 侧 2026-04-24 起 `deepseek-chat`/`deepseek-reasoner` 退役、由 `deepseek-flash`（指向 V4.1-Flash）承接〔一手〕https://api-docs.deepseek.com/zh-cn/updates ——本平台走方舟通道（DeepSeek V4 系，`docs/agents/demo-stage-model-tier.md` §五），快档 flash 语义键实际指向的模型版本应先核对（运营面一行查询，不发版）；若仍指旧世代，升档本身即有官方声称与榜单佐证的美观改进。
- **规范注入能弥补多少**：「注入设计规范能显著改善任何模型的 UI 产出」有三条独立证据链（Anthropic 官方 skill〔一手〕、taste 社区实践〔社区口碑〕、NexusTrade 带设计上下文实测〔个例〕）；「补平与 Claude 头部差距」无任何公开证据〔推断〕——榜单差距是默认 prompt 下的盲测，规范注入对双方都适用。合理预期＝把 DeepSeek 从「默认 slop/素」拉到「可控、可上线的合格审美」，不是追平 Claude。
- **A/B 面已备**：运营配置面改档/回滚/活体对比 SOP 均不发版（`docs/agents/demo-stage-model-tier.md` §三/§六）——「规范注入有无×档位」的对照实验通道现成，无需新机制。

---

## 五、综合：路径选项供地图裁决（研究票不擅定）

四路证据合起来的一个连贯判断：**「好看」与「不千篇一律」不是二选一，是两层**——业界把「好看」压在基座＋校验闭环（本平台已有：Tailwind+shadcn 基座＋三件套扫描）、把「多样」压在风格层（本平台缺：每项目生成式设计语言＋风格方向库）。老板顾虑的「统一设计语言致千篇一律」在「规范层多套可换」前提下不成立（v0 官方实验两面证）；什么都不做的结果不是多样而是随机 slop（v0 官方实验反面证）。

候选路径（可组合）：

- **A. 每项目生成式设计语言（三件套扩来源）**——§2 可行面：改动集中、机制零新建、与生成哲学相容；Stitch/Lovable 同构先例充分。
- **B. 引第三方 skill**——uupm 轻（Python 离线）可先试、taste 重（Node+Chrome+hook+76MB+2.5× 成本）建议蒸馏思想而非整包；均 MIT 可安装、均需先内部盲测；另有 Hallmark 候选。
- **C. 模型面动作**——先核对快档实际指向版本；用既有 A/B 通道做「注入有无×档位」对照；「升档解决审美」预期有限（档位差 80-140 Elo 是默认态差距，注入后差距不可预期）。
- 裁决关注点（地图「研究后裁」）：风格方向库的粒度与数量（Lovable 20 个、taste 20 方向可参照）、用户可感选择（Stitch theme selector 式）还是模型代选（访谈期采集）、以及是否要可视化微调面（Lovable/Bolt Visual edits——本平台已有圈注通道可参照承载）。
