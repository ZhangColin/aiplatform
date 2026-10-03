# 调研快照：出图机制选型事实——图片生成模型、代码出图、渲染截图三路并评（2026-10-03）

> 结论关联：wayfinder #270（本票）、map #267（设计能力决定集：现状＝模型栈仅 DeepSeek 纯文本、沙箱纯 Node 无出图基建、前端零图片呈现面）；产出供出图档位票裁决（#267 子票线，#270 blocking #275）。姊妹快照：`2026-10-03-design-capability-benchmark-six.md`（六家对标设计能力面，另票）。
> 口径：**只出选型事实、不做档位裁决**——三路机制各自的能力域/价格/许可/审核/工程代价，供裁决票对表。一手来源（官方定价页/API 文档/条款/源码）为主，二手标注可信度；查无证据判「未证实」，不推测。价格与能力按调研日 2026-10-03 口径。
> 方法注记：四路并行取证（国内图片 API／国际对照／代码出图质量边界／headless 渲染截图）＋落稿人本地代码探查（agentscope-java 模型扩展面、aiplatform 接入缝）；落稿人对承重数字与引文做了直接复核（复核清单见 §6），部分官网页 JS 渲染直读失败的已标注证据级别。

## 结论速览

1. **三路是互补域分工，不是三选一**：图片生成模型独占「写实照片/复杂光影插画」域；代码出图独占「文字排版像素级可控」域（HTML 海报/卡片文字永不乱码）；渲染截图本身不产创意，是把「任意 HTML/CSS 保真转 PNG」的呈现管道（设计稿快照/预览呈证）。质量域上代码路线与图片模型几乎不重叠（§2.3 媒介本质边界）。
2. **国内 API 按张价带 0.04–0.6 元/张，商用条款三家有条款明文**：均衡档 CogView-4 0.06／z-image-turbo 0.10／GLM-Image 0.10／wan2.2-t2i-flash 0.14；高质量中文海报档 qwen-image-3.0 0.18／Seedream 4.5 0.25／Seedream 5.0 pro 0.3–0.6。火山/阿里/智谱三家均「生成内容权利归用户、可商用」条款明文；API 层自带审核拦截（阿里 `DataInspectionFailed`、火山敏感内容错误码、智谱可删屏蔽并报告）而**责任归调用方**；聚合平台硅基流动版权表述最弱（无「版权归用户」条款）。中文文字渲染第一梯队＝Seedream 系／智谱 GLM-Image（LongText-Bench 中文 0.9788 官方）／腾讯混元 3.x／阿里 qwen-image 系。
3. **国际阵容 2026-10 大换血，且大陆直连被条款排除**：DALL-E 2/3 已于 2026-05-12 从 API 移除、gpt-image-1 定于 2026-10-23 关停（调研日后 20 天）、Imagen 3/4 已于 2026-08-17 从 Gemini API 关停——现役=GPT Image 2.5（sunburst/flare）／Nano Banana 2·Pro（gemini-3.1-flash-image/3-pro-image）／FLUX 3.1/2。OpenAI 与 Google 官方支持地区列表均不含中国大陆（BFL 无声明＝未证实）；国际路对国内平台是「出境合规前置」而非默认选项。中文文字渲染有官方一手承诺的只有 Google（40+ 语言列表含 zh-CN）——恰是最难直连的一家。单张成本国际约为国内 2–8 倍（NB2 1K ≈0.48 元 vs qwen-image-3.0 0.18 元）。
4. **代码出图可行域清晰、与现有栈零新增基建**：几何图案/形状级图标与 logo mark/配色方案/基于库的图表（Mermaid/ECharts 代码而非手写 path）/**文字排版为主的 HTML 海报卡片横幅**可行——HTML 路线文字 100% 像素级可控，恰是扩散模型的经典痛点（阿里官方自己承认长文本渲染易错字漏字，§2.2）；照片级/复杂光影插画/精确品牌感不可行（矢量媒介本质＋LLM 低层视觉属性缺陷双重边界）。沙箱基座 Next.js/Tailwind/shadcn 与 SVG/HTML 产物天然契合（Tailwind 官方内建 SVG 样式、`next/og` 就是官方「代码出图」路径先例）。已知短板是「Claude aesthetic」审美同质化——需品牌风格约束治理，工程可控性本身是强项。
5. **渲染截图硬代价：加装 chromium ≈压缩 0.4–0.5 GB/解压 1.2–1.5 GB ＋ 6–7 周一版的强绑定升级**：沙箱 dev 镜像（`node:22-bookworm`）可用官方自建姿势（同 bookworm 基底文档示例直配）；中文渲染开箱可用（`playwright install --with-deps` 含 wqy-zenhei）；已知坑=root/`--init`/`--ipc=host`/Alpine 不支持/浏览器默认走微软 CDN（本机容器 github TCP 卡顿同源风险，`PLAYWRIGHT_DOWNLOAD_HOST` 镜像可解）。**对「LLM 产出的任意 HTML→PNG 保真」没有非浏览器替代**：resvg/sharp 只吃 SVG、satori 只吃 CSS 子集 JSX——轻量方案仅在固定模板卡片场景可换（vercel/og 先例）；`chrome-headless-shell`（完整包 ~61% 体积）是浏览器路线官方瘦身项。
6. **本地接入事实：图片生成模型经 AgentScope harness 接入＝可行，接入位是「工具面」不是「模型面」**：core `Model` 接口纯 chat（单一 `stream()`→`Flux<ChatResponse>`），无 image 生成 Model 类型、适配器输出不含 ImageBlock；但 DashScope/OpenAI 扩展各内置 `dashscope_text_to_image`／OpenAI 文生图**工具**（`@Tool` 注解、返回 `ToolResultBlock` 含 `ImageBlock` URL/Base64），2025-12-20 起在库、平台所钉 2.0.1 已含。平台侧接入缝三处：pom 加扩展依赖、`AgentToolkitSupplier` 发工具（业务侧 seam 已在）、`AgentscopePartsMapper`/SSE/前端补 ImageBlock 呈现（现状零图片面）。火山方舟 images 端点 `POST /api/v3/images/generations` 为 OpenAI images 同形路径，与 `OpenAIMultiModalTool` 的 baseUrl 覆写位形态契合（未实测）。

## 1. 路一：图片生成模型 landscape

### 1.1 国内可商用 API 事实表（2026-10-03，北京地域优先）

| 平台／模型 | 能力档（中文文字/海报口碑） | 按张价格（元） | 许可与商用 | 内容审核 |
|---|---|---|---|---|
| 火山方舟 doubao-seedream-4-0 | 第一梯队：中文渲染 3.0 起 94%+ 准确率口碑、4K 直出、商业印刷级宣传（二手高可信交叉） | **0.20**（输入图不计费）；模型广场标注「即将下线」 | 生成内容权利通常归用户、可商用（《AI 服务规则》一手明文）；不得去「AI 生成」标识 | 平台自动过滤输入输出（条款一手＋内容安全护栏＋敏感内容错误码）；责任归调用方 |
| 火山方舟 doubao-seedream-4-5 | 同上升级（多图融合/编辑一致性） | **0.25**（输入图免费） | 同上 | 同上 |
| 火山方舟 doubao-seedream-5-0-pro | 官方主打复杂排版/信息可视化/交互式编辑（2026-07 上线） | 1K **0.30**／2K **0.60**；输入图第 2 张起 0.02 | 同上 | 同上 |
| 火山方舟 Seedream 5.0 lite | 降本档 | **0.22**（豆包产品页快照，二手） | 同上 | 同上 |
| 阿里百炼 wan2.2-t2i-flash/plus | 写实/风格化强；**文字渲染弱于 qwen 系**（双轨定位：万相商业线 vs Qwen-Image 开发者线） | flash **0.14**／plus **0.20**；wanx2.0-t2i-turbo **0.04**（清仓档）；wan2.7-image-pro **0.50**；wan2.6-t2i 0.20 | 合成内容知识产权归用户（上传内容合法前提）、可商用风险自担（协议 7.5/7.6 一手） | 输入一律过内容安全审核，违规返回 `DataInspectionFailed`（一手）；平台有权技术/人工审核输入与合成结果、账号行为调用方全责（4.3 一手） |
| 阿里百炼 qwen-image-3.0 | 文字渲染口碑强（官方主打复杂中文文本渲染） | 输出 **0.18**（1K/2K 同价）、输入 0.02；3.0-pro 1K 0.25／2K 0.50 | 同上 | 同上 |
| 阿里百炼 z-image-turbo | 低价档（速度性价比） | **0.10**（关提示词改写）／0.20（开启） | 同上 | 同上 |
| 智谱 CogView-4 | 汉字生成先行者（首个支持汉字的开源文生图）、DPG-Bench 曾开源 SOTA、推荐场景含中英促销海报 | **0.06**；Batch 批量 **0.03**（同步/异步价差实例＝Batch 半价）；CogView-3-Flash **免费** | 付费即商用、生成内容版权归用户自行维护（用户协议 7.2/7.5 一手） | 平台可删屏蔽并报告机关（一手）；调用方须自建审核/监测机制（服务协议 3.6 一手） |
| 智谱 GLM-Image | 新旗舰（2026-01）：AR+扩散混合架构、昇腾国产芯片训练、海报/PPT 知识密集场景主打；文字渲染 LongText-Bench 中文 **0.9788**／英文 0.9524（官方） | **0.10**（Batch 不支持） | 同上；权重开源（媒体口径 MIT，二手；官方商用需提交《模型商用授权申请》） | 同上 |
| 硅基流动（聚合托管） | 能力=所托管开源模型（Qwen-Image 文字强；Kolors；**FLUX.1-schnell/dev 已不在当前生图价目表**） | Z-Image-Turbo **0.10**／Z-Image 0.30／Qwen-Image 0.30／Qwen-Image-Edit 0.30／Kolors **免费**（官方价格页一手） | 输出「可合规使用」但**无「版权归用户」明文**、平台免责、叠加上游开源许可约束（条款一手）——版权表述最弱 | 显式水印「AI 生成」默认添加＋隐式水印固定；关显式水印则开发者自担法定标识义务（API 文档一手）；平台自动审核机制条款**未证实** |
| 腾讯混元生图 3.x | 中文文字第一梯队口碑（GitHub 实测「比即梦 4 还优秀、出错率极低」，二手高质量线索） | 混元图像 3.5 2K 约 0.15（二手）；Hy-Image-3.0 token 计费折算约 0.2（二手）；**一手未取得** | **未证实**；注意官方公告：混元大模型功能逐步迁移 TokenHub、原平台停止支持新购（一手） | 未证实 |
| 百度千帆（SD-XL 托管等） | 无突出公开口碑 | 一手未取得（二手约 0.15，低可信） | 未证实 | 有独立内容安全产品线；API 强制性未证实 |

要点：

- **计费形态**：百炼「费用＝输入图像单价×输入张数＋输出图像单价×输出张数、请求失败不计费」（官方价格页原文）；免费额度普遍 50–500 张、90 天有效。方舟 images API 同步返回、未见同步/异步双轨价差（未证实〔未见〕）。
- **水印与 AI 标识**：阿里 `watermark` 参数默认 **false**（文案「AI 生成」可关，一手 API 文档）；硅基流动显式默认开＋隐式固定；火山不得去除标识（条款）。国内《人工智能生成合成内容标识办法》是三家条款的共同背景（硅基流动条款直接引用）。
- **URL 时效**：阿里生成图 URL **24 小时有效**，商用需自持存储（一手）——平台侧管道要按「落盘」设计，不能只存 URL。
- **官方承认的能力边界（阿里一手引文）**：「模型可在画面中生成中英文文字，但对长文本（如完整古诗、长段落或多行文字）难以逐字精准还原，易出现错字、漏字或形近字替代。如对画面内文字的准确性有要求，建议尽量缩短画面内文字、仅保留关键标题或短语，或对成图中的关键文字进行后期编辑。」——**海报长文案恰是图片模型与代码出图（§2）的分界证据**。
- **配色控制先例**：阿里 `color_palette` 参数（3–10 色 hex＋占比）官方支持——设计用例（配色板约束）已有平台级参数先例。

### 1.2 国际对照（2026-10-03 现役阵容）

| 维度 | OpenAI GPT Image 2.5 | Google Nano Banana 系 | BFL FLUX 3.1/2 | Stability（对照） |
|---|---|---|---|---|
| 现役旗舰 | gpt-image-2.5-sunburst（编辑精度）/ flare（快速） | Nano Banana Pro（gemini-3-pro-image）/ NB2（3.1-flash-image）/ NB2 Lite | FLUX 3.1 pro/flex、FLUX.2 pro/flex/klein/dev | **API 已于 2026-07-31 关停**（转 StableKit 企业定制） |
| 票面原候选的下场 | DALL-E 3 **2026-05-12 已移除**；gpt-image-1 **2026-10-23 关停** | **Imagen 3/4 已于 2026-08-17 从 Gemini API 关停** | FLUX.1 仍在售 | SD3.5 仅剩开放权重 |
| 文字渲染 | 英文一流；官方自认「precise text placement and clarity」仍可能吃力；中文未承诺 | **40+ 语言本地化文字渲染、最佳语言列表明确含 zh-CN（一手，独有承诺）** | 较 FLUX.1 提升（二手口碑）；中文未证实 | — |
| 按张价 | token 计价：image output **$30/M**；官方按张表（1024²）gpt-image-2 $0.006/$0.053/$0.211（low/med/high）；2.5 按张价官方未列表（二手估算同 gpt-image-2 表，可信度中） | NB2 输出 $60/M→**$0.067/张(1K)／$0.101(2K)／$0.151(4K)**；NB2 Lite $0.0336(1K)；Pro $0.134(1K/2K)／$0.24(4K)；Batch 半价（官方定价页，落稿人复核） | 按 MP 计价：pro **$0.024/MP**（1MP≈$0.024/张、4MP≈$0.096）、flex 0.012、klein 0.007（Apache-2.0）、dev 0.012；官方计算器页显示现价 $0.024/张（划线 $0.048） | — |
| 输出归属/商用 | Output 归调用方（条款明示 assign，一手） | Google 不主张所有权（条款一手） | **API 轨：everything you generate is yours to use, including for commercial purposes（含 dev 模型，一手）；非商用限制只在自托管 dev 权重轨**（klein/schnell Apache-2.0） | — |
| 审核/水印 | **强制 API 组织验证**＋moderation auto/low（违规返回 moderation_blocked 明细）＋C2PA Content Credentials | **SynthID 水印强制嵌入所有生成图**＋Prohibited Use Policy＋Trust & Safety 自动＋人工监控 | safety_tolerance 1–5（默认 2，转引官方参数表，中高可信）；C2PA 未从官方页直证 | — |
| 大陆直连 | **官方支持地区不含中国大陆（也不含香港）**（一手列表） | **可用地区不含中国大陆/香港**（一手列表） | 无地区限制声明（未证实） | — |

**选型前置事实**：国际三家均无「支持中国大陆直连」官方口径；OpenAI/Google 是条款级排除——国内平台集成任一家都隐含境外主体/出境链路设计，应作前置约束。单张成本国际约为国内同档 2–8 倍（美元按 ≈7.1 折算：NB2 1K ≈0.48 元 vs qwen-image-3.0 0.18 元；FLUX pro 1MP ≈0.17 元 vs Seedream 1K 0.30 元——FLUX 与国内旗舰价接近、OpenAI/Google 贵 2–8 倍）。

## 2. 路二：代码出图（LLM 写 SVG/HTML/CSS 作为产物）

### 2.1 质量边界的证据结构

- **具象插画＝代际敏感区**：Simon Willison「鹈鹕骑自行车」探针系列（2024–2026 连续一手实测）：2025 年中最佳仅「像自行车的鸟」级；2025-11 Gemini 3 Pro「excellent pelican」；2026 年 Karpathy 称该基准已趋饱和（顶级模型区分度消失）；但弱模型/低成本档仍系统性失败（几何变形/部件错位），60 模型时间线聚合站留有大量失败样本。**结论：复杂具象插画只有 2026 顶级＋高推理预算模型勉强可用，不应作平台能力承诺。**
- **学术基准**：SVGenius（ACM MM 2025，22 模型）——模型**理解 SVG 显著强于生成 SVG**；VGBench（EMNLP 2024）——形状/颜色/位置低层视觉属性频繁出错。生成维度是公认短板。
- **品类可靠性**（证据支撑的分界）：
  - 可靠：几何图案、形状级简单图标/logo mark（HN 实测「surprising variety of logo marks pretty fast」）、**基于成熟库的图表**（Mermaid/Graphviz/ECharts——注意是「写库的代码」不是手写 SVG path；直写架构图被实践者评为「renders like a ransom note」）、配色方案（颜色是文本模型 token 级强项，无专门基准＝未证实但有反面证据缺失）。
  - 不可靠：复杂具象插画、精确品牌感原画（§2.3）。

### 2.2 HTML/CSS 排版海报：这条子路线被过度验证了

- **官方产品化先例**：Vercel Satori＋OG Image（生产级「代码出文字卡片」）；Anthropic Claude Artifacts（自包含 HTML/CSS/JS 实时渲染，官方指导 SVG 直接内嵌）；**本平台基座内的官方路径＝Next.js `opengraph-image.tsx`＋`next/og` 的 `ImageResponse`（写 JSX/CSS→框架自动出图，底层 Satori+resvg）**——「代码出图→PNG」在 Next 栈不是新增基建而是已封装能力。
- **学术侧清一色 HTML 管线**：2025–2026 学术海报生成研究（PosterGen/Paper2Poster/Any2Poster/P2P）全部 LLM→HTML/CSS→渲染。
- **对图片模型的核心优势＝文字与布局像素级可控**（CSS 盒模型保证换行/字体/多语言），而文字恰是扩散模型经典痛点（阿里官方限制引文见 §1.1）；**已知短板＝审美同质化**（「Claude aesthetic」：默认 Inter/米色/紫赭渐变/玻璃拟态，Reddit 多帖抱怨），社区解法＝外部品牌风格约束/设计 token 限定——与本平台「配好 token/风格约束后让 LLM 填内容」的用法契合。

### 2.3 照片级/插画级不可行的双重边界

媒介本质（矢量原语无法紧凑表示连续色调，Wikimedia/Purdue 图形格式指南常识性共识）＋模型缺陷（低层视觉属性错误，学术基准）。反向互补证据：照片级图生 SVG 仍是开放研究问题；两种技术是互补域非替代关系——**代码路线赢在几何精度与文字，图像模型赢在写实与复杂光影**。

### 2.4 与沙箱基座（Next.js/shadcn/Tailwind）契合度：零新增基建

- Tailwind 官方内建 SVG 样式 utilities（stroke-*/fill-*），官方第一方 SVG 图标库 Heroicons——SVG-as-asset 是该栈原生形态。
- HTML 海报产物＝普通组件/页面，天然带 Tailwind/shadcn 全部能力；React 原生支持 SVG 元素内联渲染。
- 服务端转 PNG 的 Node 工具链成熟度（npm 周下载，registry 官方数据 2026-09 末）：sharp 1.28 亿（SVG 输入走 librsvg，**含 `<text>` 的 SVG 依赖系统字体，容器有字体风险**——官方安装页明示）；@resvg/resvg-js 410 万（支持系统/自定义字体，Satori 官方配套）；puppeteer 1400 万/playwright 1.27 亿（完整浏览器，见 §3）。

## 3. 路三：渲染截图（headless 浏览器 HTML→PNG）

### 3.1 体积与维护代价（硬数字）

- 官方镜像 mcr playwright:v1.63.0-noble：压缩 amd64 **911.5 MiB**/arm64 893.3 MiB（三浏览器）；解压官方未公布（二手 2.2 GB 口径）。
- **沙箱姿势（node:22-bookworm 自建层）**：官方 docker 文档自建示例即 `FROM node:20-bookworm`＋`npx -y playwright@<ver> install --with-deps`——与现 dev 镜像同 bookworm 基底直配。chromium-only 增量≈**压缩 0.4–0.5 GB／解压 1.2–1.5 GB**（deps 层 102.8＋chromium 层 275.3 MiB 压缩推算；官方口径 chromium 281 MB、浏览器「a few hundred megabytes」）。`--only-shell`（chrome-headless-shell）再省：shell 压缩包 114.8 MiB＝完整包 61%，官方定位「snapshot rendering」正是截图场景。
- **维护代价**：Playwright 稳定版 **6–7 周一发**（2026 实测节奏 1.58→1.63 六版）；版本与浏览器二进制强绑定（官方原文「Each version of Playwright needs specific versions of browser binaries」；版本不匹配＝「unable to locate browser executables」）——每次升级＝镜像重建＋浏览器重下。npm 侧增量小（playwright 4.9＋core 12.8 MiB unpacked）。

### 3.2 已知坑（官方口径）

- 官方镜像默认 root→Chromium sandbox 不可用；建议 `--user pwuser`＋seccomp profile 或受控 `--no-sandbox`；`--init`（僵尸进程）＋`--ipc=host`（共享内存）；**Alpine/musl 不支持**（现镜像 bookworm＝glibc，无此问题）。
- **中文渲染开箱可用**：`install-deps` 官方清单含 fonts-wqy-zenhei（中文）/ipafont（日文）等——不会 tofu；观感升级可再补 fonts-noto-cjk（社区惯例）。
- **国内网络**：浏览器默认从微软 CDN 下载，`PLAYWRIGHT_DOWNLOAD_HOST` 官方支持换镜像——与本机容器 github TCP 卡顿既有事实同源风险，镜像构建期一次解决。
- arm64/amd64 双架构齐发（多架构 manifest 实测）。

### 3.3 轻量替代与能力边界（为什么截图场景逃不掉浏览器）

| 方案 | 官方能力边界 | 对本平台场景的判定 |
|---|---|---|
| resvg-js | 只吃 SVG→PNG（README 明示），支持系统/自定义字体 | SVG 产物转 PNG 可用 |
| sharp | 图像处理库，SVG 输入靠 librsvg | 同上，文字型 SVG 有字体风险 |
| satori＋resvg（@vercel/og 内核） | 只吃 JSX/HTML 子集＋CSS 子集（自排版引擎、禁 style/link/script、无 z-index/calc、WOFF2 不支持、不保证与浏览器渲染一致） | **固定模板卡片图**可替代浏览器（Vercel 官方「No headless browser is needed」路线）；LLM 产任意 HTML 不满足 |
| puppeteer/playwright | 完整 CSS/Web 字体保真，`page.screenshot` 一等公民 API（fullPage/元素级/mask/注入样式全文档化） | **「LLM 产任意 HTML 设计稿→PNG 保真快照」的唯一解** |

og-image 业内已分叉成两条路线：无浏览器路线（Vercel 拍板，Edge 可跑、冷启快）与浏览器路线（完整 CSS 保真需求主流，2026 截图即服务商用产品全部基于 Puppeteer/Playwright）。本平台「设计稿呈现/预览快照」输入是任意 HTML/CSS→浏览器是能力下限所需；「固定模板卡片图」场景可用 satori 路线省掉浏览器。

## 4. 本地事实：图片生成模型经 AgentScope harness 接入的可行性（代码路径证据）

本地仓库：`/Users/zhangcolin/workspace/agentscope-java`（main @911595c1，`<revision>2.0.3-SNAPSHOT</revision>`，2026-09-04）；aiplatform 所钉 `agentscope.version=2.0.1`（`aiplatform-server/pom.xml:24,139-147`）。

### 4.1 模型扩展面：五模块、SPI 注册、纯 chat 语义

- 扩展模块清单：`agentscope-extensions/agentscope-extensions-model/` 下 **anthropic／dashscope／gemini／ollama／openai**（＋e2e-tests）。openai 模块 SPI 注册 5 个 provider：OpenAI＋compat 的 kimi/minimax/glm/**deepseek**（`META-INF/services/io.agentscope.core.model.spi.ModelProvider`）——平台现用 deepseek 即出自此 compat。
- **`Model` 接口是纯 chat**（`agentscope-core/src/main/java/io/agentscope/core/model/Model.java:22-33`）：唯一能力方法 `Flux<ChatResponse> stream(List<Msg>, List<ToolSchema>, GenerateOptions)`，可选项只有结构化输出/上下文窗口——**没有图像生成方法，没有多模态输出 Model 类型**。
- 模型输出不含 ImageBlock：全部适配器的 ChatResponse 输出块为 TextBlock/ThinkingBlock/ToolUseBlock 类；ImageBlock/AudioBlock/VideoBlock 只出现在**输入方向**（各扩展 `formatter/*MediaConverter`）与多模态工具（§4.2）。core `ChatResponse.content` 是 `List<ContentBlock>`（结构上可装 ImageBlock），但无适配器这么用＝**模型面扩展位存在但无人用，非现成能力**。
- 注册/解析：`ModelProvider` SPI（providerId/supports/create）＋`ModelRegistry`（named→user factory→SPI，`<provider>:<model>` 串解析；`ModelRegistry.java:104-167`）。新增 provider＝新扩展模块＋SPI 文件，框架侧零改动。

### 4.2 图片生成的现成接入位＝工具面（框架先例，v2.0.1 已含）

- **DashScope**：`agentscope-extensions-model-dashscope/.../tool/DashScopeMultiModalTool.java`（2025-12-20 feat(tool) #241 入库，早于 v2.0.1）：`@Tool dashscope_text_to_image`（行 117-236），模型参数默认 `wanx-v1`、文档点名 `qwen-image`/`wan2.2-t2i-flash` 等；调 DashScope SDK `ImageSynthesis`；**返回 `ToolResultBlock.of([ImageBlock(URLSource|Base64Source)])`（行 196-229）——图片以工具结果内容块流经消息模型**。同件还有 image_to_text/text_to_audio/audio_to_text/text_to_video 等 8 工具。
- **OpenAI**：`agentscope-extensions-model-openai/.../tool/OpenAIMultiModalTool.java`：text-to-image（走自研 `OpenAIClient` 的 `/v1/images/generations`，`OpenAIClient.java:56-62`）＋image-to-text/TTS/ASR；**构造器可覆写 baseUrl**（行 88-108）——对 OpenAI-images 同形端点（如火山方舟 `POST /api/v3/images/generations`、硅基流动 `api.siliconflow.cn/v1` images）形态上可对接（未实测）。
- 挂载方式＝**应用侧显式注册进 Toolkit**（示例 `agentscope-examples/documentation/.../multimodal/MultiModalToolExample.java`：`toolkit.registerTool(new DashScopeMultiModalTool(apiKey))`）——classpath 上有扩展模块不会自动挂工具。
- harness 层对图片块容忍但无处理：`agentscope-harness` 仅在 token 估算（`TokenCounterUtil.java:136`「For other block types (ImageBlock...) estimate minimal overhead」）与会话渲染注释（`SessionEntry.java:83`「image-only」无渲染文本）提及——不崩、不呈现。

### 4.3 aiplatform 侧现状与接入缝（三处动点）

| 现状事实 | 代码证据 | 接入含义 |
|---|---|---|
| classpath 只有 harness＋model-openai | `aiplatform-server/pom.xml:139-147`（无 dashscope 依赖） | 接 DashScope 工具件＝加一个依赖（v2.0.1 已含该件）；或走 OpenAI 工具件 baseUrl 覆写对接方舟/硅基流动 |
| provider 白名单硬编码 | `base/agentscope/ModelRef.java:16` `SUPPORTED_PROVIDERS = Set.of("deepseek")` | **工具不走 ModelRef**——图片生成走工具面时白名单零改动；若未来加「图像模型」作为 Model 才需加白（现无此类型，§4.1） |
| 工具集 SPI 在业务侧 | `base/agentscope/AgentToolkitSupplier.java`（@FunctionalInterface，按 agentKey/工作区/toolSpec 发放；业务实现 `ProfileToolkitSupplier`） | 新图片工具＝业务侧 supplier 发放＋声明清单治理（#252 工具面规格串现成机制），底座零改 |
| 图片呈现零面 | `AgentscopePartsMapper.java:32-104` 只映射 TextBlockDelta/ToolCall/ToolResult 五类事件；#267 现状「消息模型纯文本/SSE part 封闭五种全文本/文件区拒收非文本（PRJ_023）」 | ImageBlock 进 ToolResultBlock 后无前端映射——**呈现管道（图片部件/预览/下载/落盘）是真正的大头**，与 #267「图片管道是公共底座（票9）」一致 |

**可行性结论**：框架侧「LLM 调工具出图」路径现成（v2.0.1 已含双先例、图片以 ImageBlock 流经消息模型）；平台侧动点集中在①扩展依赖＋工具发放（小）②图片呈现管道（大，票9 正本）③计量（工具调用外发 API 的计费/成本观测归计量域，`UsageEvent.provider` 口径需扩图片维度——运营侧，随档位票）。

## 5. 未证实事项备案（如实，不推测）

- **火山方舟按张价一手直读失败**：官方计费页/模型广场页 JS 渲染，数字（4.0=0.2/4.5=0.25/5.0-pro=0.3-0.6/lite=0.22）系搜索快照多源交叉（二手高可信、含 36氪/heyuan110/GitHub 实测/smzdm），**未从官方页正文直证**——裁决前建议控制台人工核一眼。
- GLM-Image 权重开源 MIT（媒体二手）；智谱开源权重商用另需《模型商用授权申请》书面授权（条款一手但流程未走）。
- 混元生图：按张价/商用条款/审核三维度一手未取得；通道迁移 TokenHub 中。百度千帆图片线一手全面未取得。
- gpt-image-2.5 按张价官方未列表（二手估算）；BFL safety_tolerance 1–5/C2PA 未从官方页直证（转引）；BFL 大陆可达性无官方口径。
- Seedream 3.0 现价未证实；方舟同步/异步价差未证实（未见）。
- 硅基流动平台自动审核机制条款未证实（条款只约束用户行为）。
- `OpenAIMultiModalTool` baseUrl 对接方舟 images 端点：**形态契合未实测**（方舟请求体与 OpenAI images 的兼容度、响应 url 字段映射需一次冒烟）。
- satori/`next/og` 对中文与复杂 CSS 的具体落差未实测（vercel/og 内置字体仅 Noto Sans）。
- 价格均未含促销/套餐形态（方舟 Agent Plan 按 AFP 燃料值、智谱 Batch 半价、免费额度），按量标准价口径。

## 6. 来源与复核记录

**落稿人直接复核清单（2026-10-03，webReader 直读，全部命中）**：

- OpenAI image generation 指南（developers.openai.com/api/docs/guides/image-generation）：2.5 双档/组织验证/moderation 表/C2PA/gpt-image-2 按张价 $0.006-$0.211/「precise text placement」自认引文 ✓。
- Gemini API 定价页（ai.google.dev/gemini-api/docs/pricing，2026-10-01 更新）：NB2 $60/M→$0.067/0.101/0.151；Lite $0.0336；Pro $120/M→$0.134/0.24；Batch 半价 ✓；页面导航无 Imagen（佐证已停）。
- Playwright docker 页（playwright.dev/docs/docker）：root/sandbox、`--init`/`--ipc=host`、版本不匹配原话、Alpine 不支持、bookworm 自建示例 ✓。
- BFL 定价页（docs.bfl.ai/pricing）：计算器现价 $0.024/张（划线 $0.048）＋自托管 license 四档结构 ✓；per-MP 全表未在静态抓取中渲染（子代理直读补充）。
- 阿里百炼文生图 API 文档（help.aliyun.com/zh/model-studio/text-to-image，2026-09-11 更新）：现役阵容三系、长文本渲染限制引文、`DataInspectionFailed`、watermark 默认 false、URL 24h、color_palette ✓。
- 火山方舟 1541523 文档：Seedream 5.0 lite API 参考、端点 `POST /api/v3/images/generations` ✓。
- 智谱 CogView-4 模型页（docs.bigmodel.cn/cn/guide/models/image-generation/cogview-4）：0.06 元/次、cogView-4-250304、汉字先行、images generations SDK 形态 ✓；GLM-Image 页落稿人两次直读网络失败（子代理一手直读补充：0.1 元/次＋LongText-Bench 数字）。
- 硅基流动价格页：生图模型清单结构（Qwen/Kolors/Baidu）✓；按张数字未在静态抓取中渲染（子代理一手补充）。
- 本地代码：§4 全部路径逐一读文件核过（Model/ModelProvider/ModelRegistry/DashScopeMultiModalTool/OpenAIMultiModalTool/OpenAIClient/SPI 文件/示例/pom/ModelRef/AgentToolkitSupplier/AgentscopePartsMapper/dev-image.Dockerfile）。

**来源索引**（一手为主，二手已标）：

- 国内：help.aliyun.com/zh/model-studio/{text-to-image, model-pricing, content-security, related-agreements}；terms.alicdn.com 百炼服务协议；docs.bigmodel.cn/{guide/models/image-generation/cogview-4, guide/models/image-generation/glm-image, guide/start/pricing.md, terms/user-agreement.md, terms/service-agreement.md, terms/model-commercial-use.md}；open.bigmodel.cn/pricing；www.volcengine.com/docs/{6256/2551634, 82379/1541523, 82379/1099320, ark/seedream-4-0-5-0}；ark.volcengine.com 模型广场/护栏页；www.siliconflow.cn/pricing；docs.siliconflow.cn/{userguide/capabilities/images, legals/terms-of-service}；cloud.tencent.com/document/product/1729/97731；二手（高可信交叉）：36kr、heyuan110.com、smzdm、seed.bytedance.com、developer.volcengine.com、qbitai.com、pingwest.com、qwenlm.github.io、GitHub Tencent-Hunyuan Issue#3、zhuanlan.zhihu.com/p/721880521（FLUX 历史价）、finance.sinae.com.cn 等（快照级）。
- 国际：developers.openai.com/api/docs/guides/{image-generation, content-provenance, supported-countries}；platform.openai.com/docs/deprecations；openai.com/policies/oct-2024-row-terms/；ai.google.dev/gemini-api/docs/{imagen, image-generation, pricing, available-regions, terms, abuse-monitoring}；docs.bfl.ai/pricing；huggingface.co/black-forest-labs/FLUX.1-dev；platform.stability.ai/docs/changelog；cloud.google.com/vertex-ai/generative-ai/docs/model-reference/imagen-api；二手（中）：sealink.io、callmissed.com（gpt-image-2.5 按张估算）、bkrsna.dev（Imagen 4 历史价）、console.core.today（safety_tolerance）。
- 代码出图：simonwillison.net/2025/Nov/18/gemini-3/、/2025/Nov/25/llm-svg-generation-benchmark/；gally.net；nilethebot.github.io；arxiv.org/abs/2506.03139（SVGenius）、2509.07127v1；aclanthology.org/2024.emnlp-main.213.pdf（VGBench）；vercel.com/docs/functions/og-image-generation；github.com/vercel/satori；nextjs.org/docs/app/api-reference/file-conventions/opengraph-image；tailwindcss.com/docs/{stroke, hover-focus-and-other-states}；v3.tailwindcss.com/docs/resources；sharp.pixelplumbing.com；github.com/thx/resvg-js；dev.to/msteja（SVG 架构图实践）；reddit.com/r/ClaudeCode 等（审美同质化口碑，中）。
- 渲染截图：playwright.dev/docs/{docker, browsers, screenshots}；github.com/microsoft/playwright（Dockerfile.noble、issues/17408）；mcr.microsoft.com manifests（实测）；storage.googleapis.com/chrome-for-testing-public（实测）；pptr.dev/troubleshooting；developer.chrome.com/docs/automation-and-testing/headless-chrome-shell；npm registry（sharp/resvg-js/puppeteer/playwright/satori/@vercel/og，实测取数）。
- 本地：`/Users/zhangcolin/workspace/agentscope-java`（§4 路径）；aiplatform 仓库 `aiplatform-server/{pom.xml, src/main/java/com/aieducenter/aiplatform/base/agentscope/, src/main/resources/docker/workspace/dev-image.Dockerfile}`。

## 7. 三路并评事实表（供档位裁决票）

| 维度 | 路一：图片生成模型 API | 路二：代码出图（SVG/HTML/CSS 产物） | 路三：渲染截图（headless 浏览器） |
|---|---|---|---|
| 产出质量域 | 写实照片/复杂光影插画/氛围感海报（图片模型独占）；中文文字第一梯队可用但长文案官方承认易错漏 | 几何图案/logo mark/图标/配色/库图表/**文字排版海报卡片**（文字像素级可控）；照片级与复杂插画不可行（媒介本质） | 不产创意——任意 HTML→PNG 保真呈现（设计稿快照/预览呈证）；对 LLM 任意 HTML 无非浏览器替代 |
| 文字与排版可控性 | 弱（扩散模型痛点；阿里官方限制引文） | **强（CSS 盒模型保证）**；短板是审美同质化需风格约束 | 取决于被渲染的 HTML 本身（＝路二的质量） |
| 单位成本 | 国内 0.04–0.6 元/张（主力带）；国际 2–8 倍且出境合规前置 | LLM token 成本（与现有 DeepSeek 栈同量级）＋一次图片 API 均无 | 无 API 成本；仅镜像与运行时开销 |
| 基建增量 | 工具件依赖＋图片呈现管道（票9 正本）＋计量扩维 | **零新增基建**（Next/Tailwind 原生；next/og 官方路径） | dev 镜像 +压缩 0.4–0.5 GB/解压 1.2–1.5 GB（chromium-only；shell 版 61% 体积） |
| 维护代价 | API 供应商侧演进快（半年一代模型、老版下线——Seedream 4.0 已标「即将下线」、国际三家半年内换血） | 无外部依赖 | Playwright 6–7 周一版强绑定、升级＝镜像重建；root/sandbox/init/ipc 坑已知；中文渲染开箱可用 |
| 合规与审核 | API 层自带审核＋责任归调用方；水印/AI 标识义务（国内标识办法背景）；商用条款国内三家明文归用户 | 无第三方审核面；产出即平台内容 | 无新增面 |
| 与现有栈契合 | 工具面接入现成（v2.0.1 双先例）；呈现是大头 | **最高**（产物即基座原生形态） | bookworm 基底官方姿势直配；DEV_IMAGE 升版重建既有机制可承载 |
| 主要风险 | 模型迭代快/URL 时效 24h/快照级价格待核（火山） | 弱模型档复杂图形系统性失败；审美同质化 | 镜像体积翻倍级增长；升级节奏绑定 |

（事实到此为止，档位裁决归 #267 子票线。）
