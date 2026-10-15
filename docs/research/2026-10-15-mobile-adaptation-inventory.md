# 调研快照：移动端适配现状盘点——aiplatform-web 390px/360px 逐页盘点＋交接包 13.2/AC11 差距清单（2026-10-15）

> 结论关联：wayfinder research 票 #319、地图 #307（语义引力网站改版线）；产出供 IA 票与实施 spec 用（页面 × 要求差距对表）。
> 口径：**只盘现状、列差距，不做改版裁决**。要求侧正本＝交接包 `docs/语义引力网站_UI设计交接包_v1.0/语义引力网站PRD_UI设计版_v1.0.docx` 13.2「桌面与手机」＋ 14.1 AC11（及关联条款 6.3 首屏约束、第 7 章「手机以对话为主、切换查看摘要与 PRD」、9.2「新窗口打开」）。现状侧＝`aiplatform-web/` 代码层判断（静态盘点，**非真机实测**；「待真机验证」处已标注）。
> 方法注记：三路并行代码盘点（响应式手段全量分布／项目页逐面板／首页·列表·登录·proto·全站壳）＋落稿人对承重结论（侧栏触发器缺失、device-frame 固定宽、首页卡片 grid、画布 hover 入口、软键盘零处理、viewport 缺省）直接复核。代码基线＝develop e603fef（本报告所在 research/mobile-inventory 分支）。表内文件路径均相对 `aiplatform-web/`。

## 结论速览

1. **移动适配的既有投资高度集中在一处：项目页 <1024px 双页签（mobileTab）机制**——`project-page-shell.tsx` 以 `lg:` 断点把「桌面 resizable 双槽」退化为「对话/成果双页签 Tabs」，配合 `project-page-view.tsx` 受控 `mobileTab`（生成起跑跳「系统」、下单跳「订单」、设计在途跳「设计稿」），项目页主体在 390/360px **结构上可用**：单区域切换（13.2 要求）**已达成**，成果区 tab 簇横滚、各面板独立滚动基本齐备。全站其余页面（首页/列表/proto）**没有任何布局级移动分支**，只靠单列限宽天然塌缩。
2. **四枚已知破点（代码层可判，严重度序）**：①移动端侧栏抽屉**没有任何打开入口**（无 SidebarTrigger、toggle 键在抽屉内部、Cmd+B 触屏不可用）→ 导航/账号菜单（主题、退出登录）手机不可达；②预览「手机档」设备框固定 `w-[390px]`，在 390/360px 视口内**两侧被裁**；③设计稿画布的预览放大/删除入口为 `opacity-0 group-hover:` **触屏不可见不可点**；④首页最近项目卡 meta 行（StageBadge+EndpointMark 均 shrink-0 不换行）在 grid-cols-3 三等分卡内**横向出血**。
3. **两条全局缺口**：软键盘**零处理**（全库无 visualViewport/interactive-widget，项目页壳 h-svh 固定高 + Composer 固定底部 → iOS 键盘弹起遮输入框，AC11 硬伤）；触屏**零手势**（无 touch 事件处理，画布无捏合缩放，缩放靠按钮兜底）。
4. **要求对照的一句话账**：13.2/AC11 的九项要求里，「单区域切换」「长文档独立滚动」「Demo 新窗口打开（能力）」三项**现状已达成**；「需求输入」「查看成果」「提交反馈」「签约与付款」四项**部分达成**（主体可用、各有明确差距点）；「软键盘不遮挡」「Demo 不适配手机时主动说明」两项**缺失**。合同/签约 UI 现状**不存在**（功能域未建，13.2「合同独立滚动」无从对照，IA 票需留位）。
5. **底座事实**：Tailwind v4 默认断点（sm 640/md 768/lg 1024），无自定义断点、globals.css 零 @media；全库 252 个 ts/tsx 文件仅 ~24 个出现响应式前缀、其中大半是 shadcn 基件（input/textarea 移动端 16px 防 iOS 缩放、questionnaire 44px 触控目标——**基件层有移动自觉，业务层几乎无**）。三个独立阈值并存：Tailwind md=768（侧栏 Sheet、输入字号）、lg=1024（项目页页签）、useIsMobile=768（仅侧栏消费）。390/360px 全部落入 mobile 档；768–1023px（大手机横屏/竖板）项目页已走页签、侧栏已是桌面栏。viewport 走 Next.js 默认注入（`width=device-width, initial-scale=1`），无 interactive-widget 定制。

## 1. 适配机制底座

| 机制 | 事实 | 证据 |
|---|---|---|
| 断点体系 | Tailwind v4 CSS-first，无 tailwind.config、globals.css 零 @media、零自定义断点；实际只用 sm/md/lg 三档 | `src/app/globals.css`（全文件无 @media）、`package.json` |
| 移动判定 hook | `useIsMobile`＝matchMedia `max-width:767px`，**唯一消费者是侧栏**（mobile 转 Sheet 抽屉）；项目页面板零消费 | `src/hooks/use-mobile.ts:3,9`、`src/components/ui/sidebar.tsx`、`src/components/layout/app-sidebar.tsx:47-48` |
| mobileTab 机制（项目页） | <lg（1024px）双页签：TabsList `m-2 grid grid-cols-2`（对话/成果两枚均分），`mobileTab` 受控＋自动跳页三入口（openOutputsTo：生成起跑→系统、下单成功→订单、设计在途→设计稿）；闲聊期（PRD 未产出）单槽对话占满 | `src/components/project-page/project-page-shell.tsx:84,105-121,111`、`src/components/project-page/project-page-view.tsx:48,68-75,117-122,248-250` |
| 侧栏抽屉 | <768px 整个 Sidebar 渲染进 Sheet（宽 18rem），桌面栏 `hidden md:block`；**但无任何 SidebarTrigger，toggle 键在 Sheet 内部（关着不可见），Cmd+B 触屏不可用 → 抽屉打不开** | `src/components/ui/sidebar.tsx:97-110,182-205,210,233`、`src/components/layout/app-sidebar.tsx:80-88`；全库 grep SidebarTrigger 零业务引用（已复核） |
| viewport | 根 layout 无 `export const viewport` → Next.js 默认 `width=device-width, initial-scale=1`，无缩放问题、也无 interactive-widget 键盘行为定制 | `src/app/layout.tsx`（已复核：仅 title/description） |
| 软键盘 | **全库零处理**：无 visualViewport、无 interactive-widget、无 focus scrollIntoView 兜底；项目页壳 h-svh 固定高 + Composer shrink-0 钉底部 → 键盘弹起遮挡输入区（待真机验证幅度） | `src/app/layout.tsx`、`project-page-shell.tsx:59`、grep 已复核零命中 |
| 触屏 | 零 touch/手势处理；唯一触屏适配是基件层：input/textarea 移动端 text-base（16px 防 iOS 聚焦缩放）、questionnaire 移动档 44px 触控高 | `src/components/ui/input.tsx:12`、`src/components/ui/textarea.tsx:10`、`src/components/ui/questionnaire.tsx:169,200,223`（input 已复核） |
| 滚动骨架 | 全站壳 h-svh + min-h-0 + overflow-y-auto 逐层收缩，面板级独立滚动普遍成立（详见 §2） | `src/components/layout/app-sidebar.tsx:220-221`、`project-page-shell.tsx:59,80,84,109,115-118` |
| 全局字号/间距 | 无小屏缩小机制：body 固定 14px/1.75，容器 padding 固定 p-6/px-6；全站唯一响应式字号＝首页/原型主标 `text-3xl md:text-4xl` | `src/app/globals.css:159-165`、`src/components/site/create-project-hero.tsx:176` |

## 2. 逐页面盘点（390px/360px）

### 2.1 首页（/，create-project-hero）

| 部位 | 现状 | 证据 |
|---|---|---|
| 布局 | 居中单列 `max-w-2xl px-6 pb-24 pt-16`，窄屏天然塌缩，无溢出 | `src/components/site/create-project-hero.tsx:171-175` |
| hero 切换件（做系统/做设计） | 小胶囊右对齐，窄屏 OK | 同上 :184-208 |
| 一句话输入（Composer hero） | textarea 自适应长高（height=scrollHeight）、附件/类型下拉/发送工具行 ~220px 放得下；附件弹层 `w-80`（320px）在 360px 视口偏紧但不破 | `src/components/composer/composer.tsx:155-165,348,358` |
| 示例 chips | `flex flex-wrap` 换行 OK | `create-project-hero.tsx:220` |
| 模板卡 | **`grid grid-cols-3` 无降列**：360px 下每卡 ~96px（内宽 64px），挤但不溢出（可读性差） | `create-project-hero.tsx:232`（已复核） |
| 最近项目卡 | 同款 `grid-cols-3`；**meta 行 `flex gap-1.5` 无 wrap，StageBadge（whitespace-nowrap）+EndpointMark（shrink-0）+时钟+时间合计 > 64px 内宽 → 横向出血**（「系统＋设计」终点名最长时最重） | `create-project-hero.tsx:265,289-297`、`src/components/ui/badge.tsx:8`、`src/components/site/endpoint-mark.tsx:29`（已复核） |
| 侧栏导航 | 机制上 <768px 转抽屉，**无打开入口**（§1）；兜底＝页内「查看全部」→/projects、最近项目卡直达 | §1 |
| 首屏约束（PRD 6.3） | 单列布局下输入区+主操作无结构性遮挡，代码层无阻断（待真机验证） | — |

### 2.2 项目页（/projects/[id]）

壳层（双页签）适配良好：`TabsList m-2 grid-cols-2`、TabsContent `min-h-0 flex-1`；桌面 ResizablePanelGroup <lg 不渲染，无像素 min 溢出面（`project-page-shell.tsx:84-121`）。

| 面板 | 现状 | 证据 |
|---|---|---|
| 对话区（CommandArea） | 单列 `max-w-3xl` 居中，消息流 `min-h-0 flex-1 overflow-y-auto` 独立滚动，气泡 max-w-[80%]；Composer textarea 自适应（min-h-10 max-h-40）、圆形发送键触屏可用、chips flex-wrap；**软键盘遮挡无处理（§1）** | `src/components/project-page/command-area.tsx:234-235,268`、`src/components/composer/composer.tsx:155-165,276,446-455` |
| 成果区（OutputsArea tab 簇） | tab 条 `h-11 overflow-x-auto` 横滚不破版、tab shrink-0；「收起」键 <lg 隐藏（双页签语义下正确）；「+ 新标签页」菜单 w-64 OK | `src/components/project-page/outputs-area.tsx:102,113,147,166-169` |
| 系统预览（浏览器条+iframe） | 浏览器条刷新+地址表单（w-full max-w-md 可收缩）+设备切换+新窗口钮，360px 不溢出；iframe w-full 随面板；**手机档设备框固定 `w-[390px] shrink-0`+外层 p-4 → 390px 视口裁 ~16px/侧、360px 裁 ~31px/侧**；有手动新窗口打开（window.open）、**无「不适配手机→引导新窗口」主动说明** | `src/components/project-page/system-panel.tsx:188,214,228,235,267`、`src/components/project-page/device-frame.tsx:63-77`（已复核）；圈注工具条 `src/components/project-page/preview-toolbar.tsx:30-31` |
| 查看当时弹窗（版本回看） | `h-[90vh] w-[94vw]`，复用 DeviceFrame → 手机档同被裁 | `src/components/project-page/version-view-dialog.tsx:76,94` |
| PRD 文档 | `ScrollArea h-full` 独立滚动（13.2 长文档要求已达成）；代码块 overflow-x-auto、img max-w-full；**table 仅 w-full 无 min-width/overflow 包裹 → 窄屏多列表逐格硬换行压碎**（不溢出容器） | `src/components/project-page/prd-doc.tsx:41,109,113-117,121` |
| 设置 tab | 单列表单 max-w-xl、根 overflow-y-auto 独立滚动；RadioGroup/勾选竖排，360px 无溢出（p-6 边距偏大而已） | `src/components/project-page/settings-panel.tsx:85-86,94-127,181-201` |
| 文件区 | 两列：左文件树 `w-52 shrink-0`（208px）+右内容 min-w-0；**无断点折叠** → 360px 下内容列仅 ~152px，不横向溢出但极窄难用；树/内容各自独立滚动；FileView pre 无 overflow-x | `src/components/project-page/files-panel.tsx:53,79,210,262,222` |
| 订单面 | 单卡竖排、根 `overflow-y-auto` 独立滚动（长记录可滚）；价格行/历史行 min-w-0+truncate；支付确认=AlertDialog max-w-xs 窄屏 OK；顶栏「确认下单」小按钮 + 项目名 truncate 不溢出。**无合同签署/长合同面（功能域未建）** | `src/components/project-page/order-panel.tsx:95-151,268-279`、`src/components/project-page/pay-confirm-dialog.tsx:32`、`src/components/project-page/confirm-order-button.tsx:15`、`src/components/project-page/quote-card.tsx:46,74` |
| 设计稿画布 | 板容器 overflow-auto 独立滚动；稿帧 1280×800 等比缩进卡（ResizeObserver）；缩放=滚轮+右下角按钮（触屏靠按钮，无捏合）；**预览放大/删除入口 `opacity-0 group-hover:opacity-100` 触屏不可见不可点**（定稿钮常显）；提示条窄屏换行撑高 | `src/components/project-page/design-canvas.tsx:308,322,53,58,156-169,381-430,584,618,666-675,732-760`（584/618 已复核） |
| 收尾卡 | 竖排卡随对话列宽，清单行 truncate、统计行 flex-wrap，无溢出 | `src/components/project-page/closing-card.tsx:289,342-350,374,413` |
| 稿卡预览弹窗 | `w-[min(1080px,92vw)] max-h-[78vh]`（少数用 min() 的弹窗）、FitFrame 等比缩放 | `src/components/project-page/design-preview-modal.tsx:71,99,133-165` |
| 顶栏 | h-12：项目名 truncate＋确认下单＋「成果」呼出键，360px 不溢出 | `project-page-shell.tsx:60-75`、`project-page-view.tsx:187` |

### 2.3 项目列表页（/projects）

| 部位 | 现状 | 证据 |
|---|---|---|
| 卡片栅格 | `grid gap-4 sm:grid-cols-2 lg:grid-cols-3` → <640px 自动 1 列，**全站唯一栅格响应，做法最对** | `src/components/site/project-list-view.tsx:91,123,135` |
| 四态过滤条 | `flex w-fit flex-row` 无 wrap，当前文案 360px 勉强放下（~260px < 312px 内宽），文案加长即溢出 | `src/components/ui/toggle-group.tsx:45` |
| 分页 | 无（注释明言 v1 全量本地分区不分页），pagination.tsx 全库未用——当前数据量下非移动问题 | `project-list-view.tsx:30-31` |
| 卡内徽标 | 全宽卡内无溢出；标题 truncate+菜单键 OK | `src/components/site/project-card.tsx:63-79,91-98` |

### 2.4 登录相关

现状＝**前端无登录页**：`/auth/login` 为后端 BFF 端点整页代理（OIDC 外部跳转），前端只产跳转 URL。移动适配责任完全在后端/IdP 侧，本仓库无可适配代码也无破点；后端登录页是否移动可用**未盘点**（超出本票范围，标注留 IA 票确认）。证据：`next.config.ts:27-30`、`src/lib/auth/login-redirect.ts:6-8`、`src/proxy.ts`（白名单放行 /auth/*）。

### 2.5 proto 原型页（非生产）

三族均为桌面走查工具、零响应式（个别 min() 宽度除外），**不需按移动修**：`proto/platform`（侧栏固定 w-60 无 Sheet、模板/最近 grid-cols-3）、`proto/platform/project`（ResizablePanelGroup 无堆叠分支）、`proto/design-process`（自述一次性走查件）、`proto/process-visibility`（对话区 `w-[400px] shrink-0` 在 390/360px **必然横向滚动**）。例外：`proto/platform/preview`（Demo 新窗口页）全页渲染用户系统、天然任意宽 ✅。证据：`src/app/proto/_shared/sidebar.tsx:72-84`、`src/app/proto/platform/page.tsx:60,84`、`src/app/proto/design-process/page.tsx:121-124`、`src/app/proto/process-visibility/page.tsx:583`、`src/app/proto/platform/preview/page.tsx:26-38`。

## 3. 差距清单（页面 × 要求 → 现状 → 差距）

要求编号：R1=手机完整支持需求输入；R2=查看成果；R3=提交反馈；R4=签署与付款（均 13.2/AC11「主要动作可完成」）；R5=对话/文档/预览单区域切换；R6=长文档和合同独立滚动；R7=主要操作易触达、不被软键盘遮挡；R8=Demo 不适配手机时给新窗口或桌面体验说明；R9=覆盖 390/验证 360（本表即其盘点）。

| 页面/区域 | 要求 | 现状 | 差距 |
|---|---|---|---|
| 首页·输入区 | R1 | **部分**：单列塌缩可用、textarea 自适应、输入 16px 防 iOS 缩放、chips 换行 | 模板卡 grid-cols-3 不降列（可读性差）；附件弹层 w-80 偏紧；无实质阻断 |
| 首页·最近项目卡 | R1（进入既有项目） | **部分**：卡片可点直达 | meta 行不换行横向出血（已知破④）；三列不降列 |
| 首页/全站·导航 | AC11 主要动作可完成 | **缺失**：<768px 侧栏转抽屉 | 抽屉无打开入口（已知破①）：导航/主题/退出登录手机不可达，仅剩页内「查看全部」与浏览器返回兜底 |
| 项目页·对话区 | R1/R3（需求输入与迭代反馈的主通道） | **部分**：双页签单区域、独立滚动、发送键触屏可用 | 软键盘遮挡零处理（AC11 硬伤，全局缺口）；自动滚底无键盘弹起修正 |
| 项目页·成果区切换 | R5 | **已适配**：<lg 双页签＋成果区 tab 簇横滚＋mobileTab 自动跳页（生成→系统/下单→订单/设计在途→设计稿） | 无（机制层面）；两层 tab（页签→范式）切换深度略高，非阻断 |
| 项目页·PRD 文档 | R2/R6 | **已适配**：ScrollArea 独立滚动、代码块横滚 | markdown 表格窄屏逐格压碎（可读性，不溢出） |
| 项目页·系统预览 | R2/R8 | **部分**：浏览器条不溢出、设备切换、手动新窗口打开（window.open） | 手机档 390px 框被裁（已知破②）；无「不适配手机→主动引导新窗口/桌面说明」（R8 缺失） |
| 项目页·设计稿画布 | R2 | **部分**：独立滚动、等比缩放、按钮缩放 | hover 依赖的预览/删除入口触屏不可达（已知破③）；无捏合缩放（按钮兜底可用） |
| 项目页·文件区 | R2 | **部分**：不横向溢出、双列各自滚动 | 文件树 208px 无断点折叠，360px 内容列 ~152px 极窄难用 |
| 项目页·设置 tab | R1（终点变更等） | **已适配**：单列表单+独立滚动 | 无实质差距（p-6 边距口味项） |
| 项目页·订单面/支付 | R4 | **部分**：单卡竖排+独立滚动+支付弹窗窄屏 OK | 签署/合同 UI 功能域未建——「合同独立滚动」（R6 后半）无从对照，IA 票需为此留位 |
| 项目列表页 | R1（续接入口 P08） | **已适配**：<640px 单列栅格 | 过滤条无 wrap（文案余量小）；无分页（v1 口径，非移动专项） |
| 登录/绑定（M01） | R4（身份补全） | **不适用（前端）**：OIDC 后端整页跳转，本仓零代码 | 后端/IdP 登录页移动可用性未盘点——留 IA 票确认项 |
| 全站·软键盘 | R7 | **缺失**：零处理 | interactive-widget/visualViewport 均未配置；h-svh+底部 Composer 结构下面键盘遮挡输入区（待真机验证幅度） |
| 全站·触屏 | R7/R9 | **部分**：基件层 16px 输入字号+44px 触控目标 | 业务层 hover 入口（画布）、滚轮缩放依赖等未做触屏等价物 |
| proto 原型页 | —（非生产） | 不适配（桌面走查工具） | 不修；`proto/platform/preview` 新窗口页天然任意宽可沿用 |

## 4. 已知会破点汇总（代码层可判，按严重度）

1. **移动端侧栏抽屉打不开**：无 SidebarTrigger、toggle 在抽屉内部、Cmd+B 触屏不可用 → 导航与账号菜单不可达。`src/components/layout/app-sidebar.tsx:80-88`、`src/components/ui/sidebar.tsx:97-110,182-205`。
2. **预览手机档设备框被裁**：`w-[390px] shrink-0` 固定宽 + p-4，390px 视口裁 ~16px/侧、360px 裁 ~31px/侧；version-view-dialog 复用同框同病。`src/components/project-page/device-frame.tsx:63-77`。
3. **设计稿画布 hover 入口触屏不可达**：预览放大（:584）/删除（:618）均 `opacity-0 group-hover:`。`src/components/project-page/design-canvas.tsx`。
4. **首页最近项目卡 meta 行横向出血**：nowrap 徽标群 > 三等分卡内宽。`src/components/site/create-project-hero.tsx:289-297`。
5. （全局）**软键盘遮挡**与（原型，可不修）**process-visibility w-[400px] 必横向滚动**：`src/app/proto/process-visibility/page.tsx:583`。

处置提示（仅方向，不做裁决）：①②③④均为点状修复（触发器补位 / min() 宽度兜底 / 常显或长按入口 / flex-wrap），软键盘需 viewport 配置+聚焦滚动兜底，可在改版 spec 中按本清单对表排程；合同/签署面为新功能域设计时直接带上 R6/R7 约束。

## 5. 来源

- 要求侧：`docs/语义引力网站_UI设计交接包_v1.0/语义引力网站PRD_UI设计版_v1.0.docx`（13.2、14.1 AC11、6.3、第 7 章、9.2；textutil 提取全文核对）＋`设计交接说明.txt`。
- 现状侧：`aiplatform-web/src/` 全量静态盘点（关键文件：project-page-shell/view、use-mobile、ui/sidebar、layout/app-sidebar、site/create-project-hero、site/project-list-view、project-page/{command-area,outputs-area,system-panel,device-frame,files-panel,settings-panel,order-panel,design-canvas,closing-card,prd-doc,version-view-dialog,design-preview-modal}、composer/composer、app/layout.tsx、globals.css、next.config.ts）。
- 历史：移动相关既有修复仅见 947ea52（问答卡选项窄屏溢出——questionnaire 基件层）；mobileTab 机制沿革见 #17/#20/#79/#293/#299/#305 commit 注记。
