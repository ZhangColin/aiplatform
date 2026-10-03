# 调研快照：Stitch 深度拆解——过程模型、多稿挑选、渐进呈现与产物导出（2026-10-03）

> 结论关联：wayfinder #268（本票）；#267（设计能力决定集：双终点扩展）；#59（判断尺：行业同构物）。供定位票/载体票/过程形态票作证据。
> 口径：只看线上产品 stitch.withgoogle.com 及其官方口径（blog.google 三篇公告、Google Developers Blog 官宣、官方 X @stitchbygoogle、google-labs-code 官方三仓、Google AI Developers Forum Stitch 团队回复）。二手来源逐条标可信度；查无官方证据的判「未证实」。调研日 2026-10-03。
> 方法注记：blog.google 三篇与官方 GitHub README/论坛帖为本机直接抓取全文逐字摘录；developers.googleblog.com 官宣本机网络不可达，关键句经搜索索引逐字核对＋官方论坛「About the Stitch forum」镜像同句复核；Stitch 应用本体为客户端渲染（内部代号线索：manifest 路径 `/Nemo/manifest.json`、boq-pitchfork 框架），站内 FAQ 无法静态抓取，限额数字以论坛官方回复为准。

## 结论速览

1. **Stitch 是「设计过程形态」的行业正主，且刚刚完成一次形态换代**：2025-05-20 I/O 首发（Gemini 2.5 Pro，文本/图片→UI 设计＋前端代码）；2025-12-10 换 Gemini 3＋「Prototypes」流程原型；2026-01 起 MCP Server/API key/Skills 开放；**2026-03-18 整体改版为「AI-native 无限画布」**（官方造词 vibe design）；2026-05-19 I/O 宣布**实时流式生成**。拆解必须分「画布前/画布后」两个时代看，两代的会话结构与呈现形态不同。
2. **会话结构＝画布承载 diverge/converge＋对话区只管意图**：官方原话画布「gives your ideas room to grow from early ideations to working prototypes... where you often **diverge and converge** before landing on something great」，且画布可吞「images, text or even code」当上下文；对话侧官方主张**从意图起步而非线框**——「Instead of starting with a wireframe, you can start by explaining the business objective you're hoping to achieve, what you want your users to feel」。
3. **多稿机制是参数化的，且三档发散度是产品一等概念**：画布前默认一次两稿（实践者逐字稿「By default, it generates two options」），variation 模式可自定数量与「creative range」（refined ↔ YOLO），变体维度五轴（layout/color/text 等）；画布后官方口径「explore **dozens of variations**」＋Agent Manager 并行探索多方向。官方 SDK 把它定死为 API 参数：`variantCount`（1–5，默认 3）× `creativeRange`（**REFINE / EXPLORE / REIMAGINE** 三档）× `aspects`（LAYOUT / COLOR_SCHEME / IMAGES / TEXT_FONT / TEXT_CONTENT 五轴）——发散度三档化与本平台多稿探索直接同构。
4. **「一点点画出来」在 2026-05 后是官方产品承诺，不只是观感**：官方公告配图说明原话「**Stitch now streams its work straight to the canvas, and you can see it working in real time**」＋「**The Stitch Agent allows you to steer iterations before the final product is done**」——流式上画布＋完成前可转向，两个语义都有；语音模式「make real-time updates — like "give me three different menu options"... **as you speak**」。实现协议未公开（判未证实），产品语义清晰。
5. **挑选语义轻：选中＝后续编辑的作用对象，不是终态动作**。选中一帧后对话/语音编辑都作用于该帧，可再开变体、可 Direct Edit 直改文本图片、可多选帧批量导出（「I can even select multiple frames and export them together」）；选完的出路四条——继续改、转流程原型、导 Figma/代码、喂 AI Studio/Antigravity/Netlify。**没有「锁定/成交/定稿」语义**，工具内无交易环节。
6. **产物模型：screen＝HTML＋截图双形态，流程＝screens＋Prototypes，规范＝DESIGN.md**。官方 SDK 仓自述「Generate UI screens from text prompts and extract their **HTML and screenshots** programmatically」；`screen.getHtml()`/`screen.getImage()` 各给一个下载 URL。导出链四路：Figma（2025 是「Paste to Figma」且仅 Standard 模式，2026-02 起直导）、代码（HTML/CSS，官方形容「clean, functional front-end code」）、AI Studio/Antigravity（2026-03）、Netlify 一键发布（2026-05）。DESIGN.md（YAML tokens＋prose 理由，带 WCAG 对比度 lint）2026-04-21 开源，现 28k stars。
7. **设计→系统的桥走「规范级」而非像素转译**——与本平台 #267 已拍板的桥深度正选（规范级）同构：官方 DESIGN.md 定位原话「Instead of guessing intent, AI agents can know exactly what a color is for, and can **validate their choices against WCAG accessibility rules**」；配套「extract a design system from any URL」＋项目间导入导出。Stitch 自己就是「设计产物→下游代码生成」的规范级桥梁先例。
8. **Figma 导出能用但非像素级**：二手教程口径「Auto Layouts, named layers, and editable text — but it's not pixel-perfect」；论坛/Reddit 实报 hero 区导出散架、间距错位、嵌套 div 层级乱；2026-02 直导上线后社区喝彩（论坛 WOW 帖）。代码导出口碑「快而可用、原型级」。
9. **能力边界：只做 UI，非 UI 平面设计无产品面**。官方自述仅「generates UIs for mobile and web applications」，SDK 设备类型只有 MOBILE/DESKTOP/TABLET/AGNOSTIC；logo 只能作为资产贴进 UI（贴图进 prompt 支持，官方团队承认用 prompt 精确换 logo「can be a bit tricky」），**做 logo/海报/产品图案＝未证实支持**，二手一致口径「built specifically for web and mobile UI. It does not do presentations or slide decks」。
10. **交易形态：纯免费工具，零交易语义**。Google Labs 实验期免费、无付费层（2026-04 多家二手一致「does not have any paid plans」，有来源报 $25/月 Pro 与全部其他证据矛盾，判失实备案）；限额制非计价制——官方团队回复「we currently offer **150 design limits per day**」（2025-12），2026 二手报翻倍至 400/天＋15 redesign/天（未官方证实）；部分区域不可用（2025-06 官方论坛），2026-05 官方宣布「available to global users」。**「设计即交付走完整商业流」在对标名单里无人做**——Stitch 的终点是导出，不是交易。

## 1. 产品时间线与形态演进（一手为主）

| 时间 | 事件 | 来源级别 |
|---|---|---|
| 2024～2025 初 | Google 收购 Galileo AI，后以 Stitch 之名重推（实践者访谈＋多家二手一致；**官方无公告，判未证实（官方口径）**） | 二手 |
| 2025-05-20 | I/O 2025 首发。官宣：「Stitch is a new experiment from Google Labs that allows you to turn simple prompt and image inputs into complex UI designs and frontend code in minutes」，Gemini 2.5 Pro 多模态；interactive chat、theme selectors、**paste to Figma**、export front-end code | 一手（官宣） |
| 2025-06 | 区域限制浮出：官方论坛「Stitch is unavailable in some countries (working on it!)」 | 一手（论坛团队/官方回复） |
| 2025-09 | 官方团队：Figma 导出**仅 Standard 模式**，Experimental 模式生成物不可直导 | 一手（论坛团队回复） |
| 2025-12-02 | 官方团队：每日 **150 次设计生成**限额，「actively working to expand」 | 一手（论坛团队回复） |
| 2025-12-10 | **Gemini 3** 引擎＋**Prototypes**：「you can now "stitch" together different screens on your canvas to create a working prototype. Building on static screens, you can now design interactions and entire user flows」 | 一手（blog.google） |
| 2026-01 下旬 | **MCP Server＋Gemini CLI Extension＋Agent Skills＋API key** 上线（官方 X：「Just generate a key in Settings and connect your tools instantly」）；同期待测：Deep Design、design systems、直导 Figma、Agent Manager | 一手（官方 X 转引）＋二手（testingcatalog 挖掘） |
| 2026-02-11 | 直导 Figma 上线（论坛用户 WOW 帖＋团队回复） | 一手（论坛） |
| 2026-03-18 | **vibe design 改版**：AI-native 无限画布＋design agent（跨项目演化推理）＋Agent Manager（并行多方向）＋DESIGN.md＋语音＋Prototypes 即时化＋导出 AI Studio/Antigravity | 一手（blog.google，全文已核） |
| 2026-04-21 | **DESIGN.md 开源**（google-labs-code/design.md）；lint 带 WCAG 对比度检查、diff 检测回归 | 一手（blog.google＋官方仓） |
| 2026-05-19 | I/O 2026：**实时流式上画布＋完成前可转向**；输入扩到 codebase/设计文件；AI Studio 分享链接；Antigravity 导出；**Netlify 直接发布**；global users | 一手（blog.google，全文已核） |

两个时代一句话：**画布前（2025-05→2026-03）**是「对话生成两稿→挑一稿继续聊」的线性工具；**画布后（2026-03→）**是「无限画布上发散收敛＋agent 并行＋语音实时」的过程空间。下文各节按两代分述。

## 2. 设计过程模型

### 2.1 会话结构：对话区与画布的分工

- **画布前**：左对话右预览的分栏（对话输入 prompt，右侧出 screens、tab 切换）；实践者口径生成「takes about a minute」量级（二手 hands-on 一致）。
- **画布后**（现行形态）：画布是主体、对话是驱动。官方对画布的定位是承载**设计过程的空间**——「an AI-native, infinite canvas that gives your ideas room to grow from early ideations to working prototypes... where you often **diverge and converge** before landing on something great」；画布同时是**上下文容器**——「It allows you to bring your ideas regardless of the shape they take — **images, text or even code** — directly to the canvas as context」（二手上手记展开：竞品截图、手绘草图丢上画布都成上下文）。
- **对话/语音侧只管意图**：官方方法论原话「Instead of starting with a wireframe, you can start by explaining the **business objective** you're hoping to achieve, what you want your users to **feel**, or even examples of what's currently **inspiring** you」——需求语义（业务目标/用户感受/参照物）先于结构。对本平台「一句话需求→访谈→PRD」的访谈语义直接同构。
- **agent 双层**：design agent「can reason across the entire project's evolution」（知道你试过什么、为什么否掉某方向）；Agent Manager「tracks your progress and helps you to work on **multiple ideas in parallel**」——多稿探索从「一次生成多张」升级为「多线程并行追踪」。

### 2.2 多稿生成与呈现

- **一次几稿**：画布前默认**两稿**（实践者逐字稿：「I toggled to web and selected the Gemini 2.5 Pro thinking model. **By default, it generates two options.** And I can also use the "variation" feature」）；画布后官方口径放大到「a professional designer looking to explore **dozens of variations**」＋Agent Manager 并行。官方 SDK 给出精确参数面：`variantCount` **1–5、默认 3**。
- **稿间变体关系是显式参数**，不是黑盒随机：SDK `variants(instruction, options)`：
  - `creativeRange`: **REFINE / EXPLORE / REIMAGINE** 三档（默认 EXPLORE）——对应 UI 上的 refined↔YOLO 滑杆与「redesign 模式」（实践者：「The redesign model is also very interesting — it gives you a lot of creative ideas by doing a full redesign」）；
  - `aspects`: **LAYOUT / COLOR_SCHEME / IMAGES / TEXT_FONT / TEXT_CONTENT** 五轴（实践者转述 UI 面上的「Aspects to vary: layout, color schemes, text content」）。
  - 实践者算术：「When you use YOLO mode and the redesign mode, you can generate **15 or 16 different ideas**」——组合叠加出批量稿。
- **呈现形态**：画布前＝并排切换的两屏；画布后＝**空间并置**（每代生成落在画布上、不覆盖前代），二手上手记点出要害：「The spatial canvas lets ideas exist in relationship to each other. You can see your **divergent explorations and convergent refinements** at a glance」——发散与收敛在空间里可见。

### 2.3 挑选语义

- **选中＝编辑作用域**：选中一帧，后续对话/语音修改作用于该帧；Direct Edit 直改（2026-03 官方团队回复点名「the new **Direct Edit** feature in Stitch for more control over your components」——改文本/换图/微调，二手评测印证「manually edit generated designs, swap images, and tweak text」）。
- **选中后能干什么**（无终态锁定）：
  1. **继续改**：chat/语音迭代、开变体（变体自选中稿再发散）、Direct Edit；
  2. **转下一环——流程**：「You can "Stitch" screens together in seconds and simply click "Play" to quickly preview your interactive app flow. **Stitch can automatically generate logical next screens based on the click**」（点元素自动生成逻辑下一屏——流程是从单稿长出来的，不是一次生成整套）；
  3. **导出/交付**：Figma、代码、AI Studio 分享链接、Antigravity、Netlify 发布、MCP/SDK 程序化取件；
  4. **批量**：「I can even **select multiple frames and export them together**」（多选帧成组导出）。
- **没有的语义**：定稿/锁定/验收/成交——选稿在 Stitch 里永远可逆、可再发散，工具不管下游交易。

## 3. 「一点点画出来」的渐进呈现

- **现行官方口径（2026-05-19，一手，配图说明原话）**：
  - 「**Stitch now streams its work straight to the canvas, and you can see it working in real time.**」——工作过程流式上画布；
  - 「**The Stitch Agent allows you to steer iterations before the final product is done.**」——未完成即可转向（中途可打断改）；
  - 官方正文定性为「design, **stream** and **steer** iterations in real time」。
- **语音通道的实时语义（2026-03-18，一手）**：「you can speak directly to your canvas. The agent can give you **real-time design critiques**, design a new landing page **by interviewing you**, and make **real-time updates** — like "give me three different menu options," or "show me this screen in different color palettes" — **as you speak**」——三个可复用语义：实时点评、边访谈边生成、说话间落改动。
- **画布前时代**：只有不定态 loading（论坛实报 bug「generation keeps loading」；hands-on「takes about a minute」；I/O 2025 demo 逐字稿「takes about a minute or so」）——**2025 UI 无流式证据，判未证实**。渐进呈现是 2026 画布时代才产品化的。
- **实现形态（工程/官方口径）**：**未证实**。官方只到产品语义层（streams/steer/real-time），协议（SSE？分块？画布增量？）无公开文档；SDK 的 `generate()` 是同步返回 `Screen` 对象，公开 API 面不含流式事件。判：机制层无官方口径，产品语义层证据充分。

## 4. 产物模型

### 4.1 输出物格式

- **双形态：HTML＋截图**。官方 SDK 仓一句话自述：「Generate UI screens from text prompts and extract their **HTML and screenshots** programmatically」；代码级：`const html = await screen.getHtml(); const imageUrl = await screen.getImage();`——`html` 与 `imageUrl` 均为下载 URL。对本平台「图片管道公共底座」：出图走 HTML→截图是正主同构路线。
- **代码**：官宣「Export front-end code: Stitch generates **clean, functional front-end code**」；HTML/CSS（二手教程/workflow 提及 Tailwind 与 inline CSS 省流hack——代码风格细节二手口径）。按屏导出，非整站工程。
- **设计规范**：theme selectors（首发）→ Design Systems（tokens 集，可切换）→ **DESIGN.md**（项目间/工具间可携带）。DESIGN.md 结构（官方仓 README）：**YAML front matter 放机器可读 tokens**（colors/typography/rounded/spacing）＋**markdown prose 讲人话理由**（「Tokens give agents exact values. Prose tells them *why* those values exist」）；工具链 `npx @google/design.md lint`（WCAG 对比度检查、结构 findings 出 JSON）与 `diff`（token 级回归检测）。

### 4.2 多屏/流程组织

- 数据模型（SDK）：**Project → Screens（→ DesignSystem）**；`generate(prompt, deviceType)`，`DeviceType = MOBILE | DESKTOP | TABLET | AGNOSTIC`。
- **Prototypes**（2025-12 上线、2026-03 即时化）：屏与屏连线成可交互流程，「click "Play"」预览；点按元素可**自动生成逻辑下一屏**（「mapping out user journeys effortlessly」）。流程是挑选之后的组织动作，不是生成时的输出物。

### 4.3 导出链（质量与形态）

| 通路 | 形态 | 口径 |
|---|---|---|
| Figma | 2025：paste to Figma（剪贴板＋插件），**仅 Standard（快）模式**——官方团队回复明说 Experimental 生成物不可直导，用户被迫返工重做；2026-02 起直导（.fig 语义） | 一手（论坛）＋二手（教程） |
| Figma 质量 | 「Auto Layouts, named layers, editable text — **not pixel-perfect**, though it gives designers a real starting point」（二手教程）；实报：hero 区散架（论坛帖）、间距/对齐漂移、嵌套 div 层级（Reddit r/FigmaDesign）；workaround＝下代码 zip 走 html.to.design 插件进口 | 二手（多源一致） |
| 代码 | HTML/CSS 按屏，官宣「clean, functional」；社区定位「原型级起点」 | 一手（官宣）＋二手 |
| AI Studio | 选中帧/项目导出到 Google AI Studio 继续原型（2026-03 官宣＋实践者演示「select multiple frames and export them together」）；2026-05 起可生成 **AI Studio 分享链接** | 一手 |
| Antigravity | 屏导出到 Google Antigravity「easily plug in your backend logic」（2026-05 官宣） | 一手 |
| Netlify | 「publish your work to the web directly with Netlify」（2026-05 官宣）——设计稿直发线上 | 一手 |
| 程序化 | **MCP server（https://stitch.googleapis.com/mcp）＋ @google/stitch-sdk**：create_project / generate_screen_from_text / get_screen 等工具；Vercel AI SDK 与 Google ADK 即插；**stitch-skills**（8.4k stars）按 agentskills.io 开放标准出技能包，适配 Codex/Claude Code/Cursor/Gemini CLI/Antigravity/OpenCode | 一手（官方仓） |

## 5. 能力边界：非 UI 设计、收费与限制

- **非 UI 图案/平面设计（logo/产品图案/海报）：无产品面**。官方自述边界句「Stitch generates UIs for mobile and web applications」（官方站描述）；SDK 设备枚举只有四档屏幕类型。logo 相关能力止于**把图作为资产贴进 UI**（输入框 Cmd+V 贴图，官方团队回复）——且官方承认「adding an exact image as a logo via prompting **can be a bit tricky**」。二手一致：「built specifically for web and mobile UI. It does not do presentations or slide decks」（Muz.li 对比文）。**判：logo/海报/产品图案作为交付物＝未证实支持，定位明确不含**。
- **收费**：无付费层。2026-04 二手一致（banani「does not have any paid plans or subscription fees. Users need not even provide credit card」；多家同口径）。反例：nxcode 报「Pro plan $25/month」——与全部一手/其他二手矛盾，判**失实**（SEO 农场文）。
- **限制**：限额制。官方（2025-12-02 论坛团队回复）：「we currently offer **150 design limits per day**」；2026 二手报 400 design/天＋15 redesign/天（banani 等，**未官方证实**，论坛另有用户求「link an api key to pay for extra credits」被官方记录为 feature ticket——付费扩容只是请求，未上线）。双模式：Standard（快）vs Experimental（Gemini 2.5 Pro 质量，后 Gemini 3）——模式影响质量与 Figma 导出资格（官方团队回复）。
- **可用性**：2025-06「unavailable in some countries (working on it!)」（官方论坛）；2026-05-19 官宣「available to **global users** starting today」；Labs 惯例个人 Google 账号＋18+（二手一致，官方 FAQ 页未取得——判二手一致）。首发期 waitlist 报道两说（mlq 报需排队、enhancedhuman 报「No waitlist, no credit card」），2026 现状即开即用。

## 6. 交易形态

- **纯免费工具，零交易语义**：无付费层、无下单、无交付物交易、无按件计价。终点是**导出**（Figma/代码/AI Studio/Antigravity/Netlify/MCP），不是成交。
- 限额即成本闸门：按「次生成」计（150/天级），不按产物计价、不按商用授权分级。**生成物商用授权条款：未证实**（未查到 Stitch 专项商用授权官方口径，只能套 Google Labs 泛条款——备案）。
- 对本平台的含义：对标六家＋Stitch 全部是「工具免费/订阅」形态，**「设计即交付走完整商业流（下单冻结物＝设计资产包）」在整个对标名单里无人做**——这不是标配缺口，是空位（判断尺 #59：行业都没有的，按我们自己的商业决策裁决；#267 已裁决双终点入图）。

## 7. 对本平台双终点的映射

> 对照 #267 决定集：一过程双终点（设计即交付＋设计服务系统）、桥深度正选＝规范级（v1 硬要求）、体验标杆＝Stitch「一点点画出来」。判断尺沿 #59。以下每条都锚 Stitch 实据。

1. **过程形态可直接借的三件**：
   - **意图起步的对话语义**：官方「business objective / what you want your users to feel / what's inspiring you」三问，与本平台访谈→PRD 的问法天然对接——设计线访谈不必另造框架，PRD 的业务目标/用户感受字段就是 prompt 侧输入；
   - **多稿三档发散度**：REFINE/EXPLORE/REIMAGINE 三档＋五轴变体维度（layout/配色/图片/字体/文案）——比「一次 N 稿」裸数量更可治理，多稿探索的参数面照此建模（数量×发散度×维度）；
   - **画布空间并置做 diverge/converge 可见**：每代不覆盖前代、发散收敛一屏可见。我们第一步不必做无限画布（#267 已裁独立工具入口不做），但「稿与稿空间并置、不互相覆盖」的呈现原则可落在对话流产物区。
2. **渐进呈现的产品语义两件，机制自选**：官方口径拆出来就两个承诺——「streams its work straight to the canvas」（过程流式可见）＋「steer iterations **before** the final product is done」（未完成可转向）。我们的服务端能力（SSE 已有）可先落这两个语义（生成过程分块到达＋中途插话改向）；实现协议 Stitch 未公开，无行业标准可抄，按自身事件模型设计即可（备案触发器：若 Stitch/同类公开流式协议且成熟，再对齐）。语音实时（边说边改、实时点评、边访谈边生成）是增强位，现能力可做文字版「访谈中逐稿落画布」。
3. **产物模型三件套锚定**：screen＝**HTML＋截图双产物**（对票9 图片管道：出图基建走 HTML→截图渲染，正主同构；文件区需接纳图片件——PRJ_023 现拒非文本是硬缺口）；流程＝screens 连线＋「点元素自动生成下一屏」（流程是挑选后的组织动作，不是一次生成整套——对本平台生成节奏同构）；规范＝**DESIGN.md 是「规范级桥」的行业正主**——YAML tokens＋prose 理由＋WCAG lint，28k stars 且跨工具开放。**设计稿→系统一致性（v1 硬要求）的载体**：被选设计稿沉淀为机器可读设计规范（tokens＋理由），系统生成侧以规范为约束——这正是「规范级起步」的成熟同构物，不必发明新格式语言（是否直接采 DESIGN.md 格式归载体票裁决）。
4. **挑选语义保持轻**：选中＝后续编辑作用域＋导出单元（单选/多选），不做终态锁定语义；「设计即交付」终点在**平台交易层**收口（下单冻结＝设计资产包），工具层与 Stitch 一样永远可逆。转下一环的出口语义（喂系统生成）对应 Stitch 的「export to AI Studio」位——同项目双终点（#267 正选）在 Stitch 有直接同构：同仓产物、规范随行、一键转生成工具。
5. **能力边界与空位**：非 UI 图案/平面（logo/海报）在 Stitch 无产品面（只做 UI）——我们若做是非对标既有面（#267 现状事实：模型栈纯文本无图片生成，本就开新产物类别线）；Figma 导出质量口碑「起点级非成品」印证：**规范级桥比像素级搬运可靠**（像素搬运各家都翻车，规范随行是行业解法）。
6. **交易形态空位确认**：Stitch 免费工具无交易语义，六家亦然——「设计即交付走完整商业流」按 #267 既定决议推进，不因「行业都没有」降级（判断尺反过来用：这是我们的商业扩张位，不是标配缺口）。

## 8. 未证实事项备案

1. **渐进呈现的实现协议**（SSE/分块/画布增量）——官方只到产品语义，无工程口径；公开 API `generate()` 为同步语义。
2. **Galileo AI 血统**——实践者与多家二手一致陈述「Google acquired Galileo AI and rebranded it as Stitch」，官方无公告无否认；备案为二手一致、官方未证实。
3. **现限额数字**——官方只锚到 2025-12 的「150 design limits per day」；2026 的「400/天＋15 redesign/天」为二手（未官方证实），官方 FAQ 页静态不可达。
4. **生成物商用授权条款**——未查到 Stitch 专项官方口径。
5. **画布时代一次生成的默认稿数**——SDK 默认 3（1–5），但现行画布 UI 的默认值无一手确认（官方口径只说 dozens）；画布前默认两稿有一手访谈佐证。
6. **付费层存在性**——反方证据（nxcode「$25/月」）判失实；「API key 付费扩容」仅为用户请求记录。
7. **首发期 waitlist**——二手两说（有排队/无排队），现状即开即用。
8. **Stitch 官方帮助中心/站内 FAQ 全文**——应用客户端渲染无法静态抓取；本快照 FAQ 类事实全部改用官方论坛团队回复替代。

## 9. 来源与复核记录

一手（本机抓取全文，引文逐字）：
- [Introducing "vibe design" with Stitch](https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-ai-ui-design/)（2026-03-18，Rustin Banks）——画布/agent/Agent Manager/DESIGN.md/Prototypes/语音/MCP 出口全部引文。
- [We're introducing new ways to design in real time with Stitch](https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-updates/)（2026-05-19，I/O 2026）——stream/steer/AI Studio 链接/Antigravity/Netlify/global 引文。
- [Bring your app ideas to life with Gemini 3 in Stitch](https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-gemini-3/)（2025-12-10）——Gemini 3＋Prototypes 引文。
- [Stitch's DESIGN.md format is now open-source](https://blog.google/innovation-and-ai/models-and-research/google-labs/stitch-design-md/)（2026-04-21，Cassia Xu）——开源/WCAG 引文。
- [google-labs-code/stitch-sdk](https://github.com/google-labs-code/stitch-sdk)（README 全文，2026-10 仍活跃）——产物模型/variants 参数/DeviceType/MCP 工具/`getHtml`/`getImage`。
- [google-labs-code/design.md](https://github.com/google-labs-code/design.md)（README）——格式结构/lint/diff；28,218 stars（2026-10-03 GitHub API）。
- [google-labs-code/stitch-skills](https://github.com/google-labs-code/stitch-skills)（README）——agentskills.io 标准、六家编码代理适配；8,414 stars。
- Google AI Developers Forum（Discourse JSON 抓取）：[Increase of Credit Limit](https://discuss.ai.google.dev/t/increase-of-credit-limit/110391)（官方团队 150/天回复，2025-12-02）；[Exporting Stitch to Figma](https://discuss.ai.google.dev/t/104903)（Standard-only 回复，2025-09-17）；[Sorry, Stitch is unavailable](https://discuss.ai.google.dev/t/87225)（区域限制，2025-06）；[(FANTASTIC) Export to Figma](https://discuss.ai.google.dev/t/122501)（直导上线，2026-02）；[Generation Keeps Loading](https://discuss.ai.google.dev/t/129720)（Direct Edit/贴图/换 logo 口径，2026-03）。
- [developers.googleblog.com 官宣](https://developers.googleblog.com/en/stitch-a-new-way-to-design-uis/)（2025-05-20）——本机不可达；首句经搜索索引逐字核对，与官方论坛 About 帖镜像同句；功能清单（chat/theme selectors/paste to Figma/export front-end code）经索引引文核对。
- 官方站自述（meta description 直抓）＋官方 X @stitchbygoogle 2026-01-28 API key 帖（经 testingcatalog 全文转引）。

二手（标注可信度）：
- [aakashg.com 访谈逐字稿](https://www.aakashg.com/designing-ai-products-the-right-way-google-stitch-custom-gpts-and-prototyping-workflows-with-xinran/)（2026-02-20，实践者 Xinran）——**高可信**（一手使用经验、逐字）：默认两稿/variation 参数/YOLO/redesign/多选导出/Figma 限 fast 模式/Galileo 说法。
- [testingcatalog.com](https://www.testingcatalog.com/google-tests-deep-design-for-stitch-figma-export-and-more/)（2026-01-29）——**高可信**（逆向挖掘+官方 X 嵌入）：Deep Design/设计系统/直导 Figma 测试。
- solafide.ca 上手记（2026-03-25）——**中可信**（文末自述 AI 代笔，但事实面与官方公告一一对应）。
- banani.co / thatpainter.com / enhancedhuman.ai 等 SEO 评测——**中低可信**（限额数字/耗时口径，多源交叉后采用）。
- Muz.li 对比文（2026-04-20，「只做 UI 不做幻灯片」）——**中可信**。
- nxcode.io（「$25/月 Pro」）——**判失实**（与全部其他证据矛盾）。
