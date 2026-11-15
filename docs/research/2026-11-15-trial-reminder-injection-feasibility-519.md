# 调研报告：试用「只是体验」提示的注入通道可行性（票 #519）

> 票 #519（research），2026-11-15，地图 #510「试用拦截面」条。
> 硬约束（票面）：提示机制**不得进入用户生成系统的代码**——否则付费后要么提示还挂着、要么得改用户代码摘掉，均不可接受。
> 方法：本仓代码为一手来源，全部 claim 标注 `file:line`；浏览器标准行为（iframe load 语义、SPA 路由 API）标注为「机制事实」并注明非仓内代码；不确定处如实标注。
> 范围：提示文案与触发频次不在本票（随 #513/#514 定，见票面）。

## 结论速览

**三选一判定：可行**——推荐「预览网关响应注入试用提示脚本」，即**复用圈注（annotation.js）的既有注入通道模式**：

| 备选机制 | 碰生成代码 | 付费后移除 | 「页面切换」语义 | 覆盖新窗口裸开 | 复杂度 |
|---|---|---|---|---|---|
| A. 平台壳层提示（沙箱窗 chrome 区） | 零 | 干净（前端一处） | 弱：常驻或不随切换 | 否（新窗口无壳） | 小 |
| B. iframe postMessage 控制悬浮条 | 单独不成立（需 iframe 内平台件＝回到注入） | — | — | — | — |
| **C. 预览网关响应注入（推荐）** | **零（圈注同模式实证）** | **干净（平台数据/网关侧收口，用户工作区零字节）** | **最强：MPA 逐页天然＋SPA 可 hook 路由 API** | **是（裸子域也过网关）** | 中 |
| D. 其他（unavailable.html／快照路由） | — | — | — | — | 先例/口径备查 |

推荐理由三点：① 零侵入不是推演是实证——同模式（nginx `sub_filter` 注入＋网关自持脚本资产）已承载圈注在线运行，ADR-0014 决议明文「不进用户源码、不在容器内」；② 「页面切换时」语义四案最强，且是唯一覆盖「新窗口打开」裸子域场景的方案；③ 付费后移除干净——提示字节从不落用户工作区，熄灭＝平台侧一处收口，不存在「改用户代码摘提示」路径。

关键前置事实：**「本项目是否已付费」的判定数据当前不存在**（Project 聚合无此位、metering 零商业概念、#513 OPEN）——机制与判定数据解耦：#513 未落期间可按地图 #510「新项目一律五问试用开局」全量恒显先上，#513 落地后接判定数据自动熄灭。降级备案见 §三。

---

## 一、本平台注入通道事实面

### 1.1 圈注机制：怎么注入、怎么消失、零侵入是否成立

**注入方式＝网关响应级注入，三层事实：**

1. **网关 sub_filter 改写 HTML 响应**：把 `</head>` 替换为「脚本引用 + `</head>`」——`aiplatform-server/src/main/resources/docker/gateway/gateway-inject.conf:14-17`（`sub_filter_once on`，每文档一次）。仅主预览 server 块 include（`deploy/nginx/aiplatform.conf:68-76`）；「查看当时」快照路由不 include（`aiplatform.conf:78-86`，ADR-0007「只逛不换」口径）。
2. **脚本资产网关自持**：`location = /.aiplatform/annotation.js` alias 到网关资产目录（`gateway-inject.conf:9-12`）；资产经 `deploy/nginx/assemble-assets.sh:27-35` 装配，单源在 `aiplatform-server/src/main/resources/docker/workspace/annotation.js`（装配零改写、dev/生产同路径，`assemble-assets.sh:8-9`）。
3. **对生成系统代码零侵入——成立**：脚本引用只存在于网关的**响应流**，用户工作区与容器内文件不含任何标注字节；ADR-0014 决议明文（`docs/adr/0014-preview-gateway-subdomain-routing-injection.md` 决议第 3 条）：「脚本资产由网关自持……**不进用户源码、不在容器内**」。付费后移除干净性同理成立：一切收口在网关配置与平台资产侧。

**生命周期（出现/消失）：**

- 注入＝每个 text/html 响应注入一次（文档级，非会话级）。
- 运行＝脚本在预览 iframe 内执行、**非常驻**：不进标注态时零监听、零遮罩、对页面零干扰（`annotation.js:4-5` 头注）。
- 呼出/退出＝**父窗（平台前端）postMessage 驱动**：`system-panel.tsx:121-128` 发 `{ type:"annotate", mode:"enter"|"exit", tool }` → `annotation.js:407-418` 收信封进/出标注态；Esc 双侧退出（子窗 `annotation.js:421-426`、父窗 `system-panel.tsx:134-141`，事件不跨帧、无双触发）；回传锚/退出信封均校验/收敛 origin（父窗校验 `event.origin === 预览源`，`system-panel.tsx:145-157`；子窗回传目标＝呼出消息的 `event.origin`，`annotation.js:46-53`）。
- 协议正本＝`aiplatform-web/src/lib/preview/annotation.ts:7-16`（与注入脚本双侧同源契约）。
- iframe 重挂后脚本状态清零：父窗 onLoad 重发 enter（`system-panel.tsx:391-397`）——证明「注入脚本随文档加载重生、状态由父窗补」的模式已跑通。

### 1.2 沙箱窗/预览网关架构

**沙箱窗是平台前端 React 组件，预览本体是跨源 iframe：**

- `SystemPanel`（demo variant）即沙箱窗壳：交通灯（`aiplatform-web/src/components/project-page/system-panel.tsx:306-312`）＋真地址栏（`:323-352`，goto 同源导航）＋设备切换/刷新/新窗口按钮（`:249`、`:206-242`）＋**沙箱徽标「沙箱演示环境 · 隔离运行不产生正式扣款」**（`:250-253`）＋底部浮动圈注工具条（`:466-474`，`preview-toolbar.tsx:29-31` 平台件浮于视口下方）。Demo 体验页装配点 `aiplatform-web/src/components/demo-experience/demo-experience-page.tsx:169-177`。
- 预览本体＝`<iframe src={frameUrl}>`（`system-panel.tsx:385-398`），源＝`{workspaceId}.preview.semgravio.com`（`WorkspaceNaming.java:68-76` 纯函数；URL 由 `GET /projects/{id}/preview` 探活通过后返回，`aiplatform-web/src/hooks/use-project-preview.ts:11-19`）。**平台域与预览子域跨源**（ADR-0014 安全边界：用户生成代码碰不到平台后端）。
- iframe 重挂 key＝url+预览纪元+手动刷新节拍（`previewFrameKey`，`system-panel.tsx:558-560`）——仅 run 收口/手动刷新整帧重建；iframe 内自身导航不重挂。
- 预览流量全过网关一跳：生产＝共享基础设施 nginx `semgravio-nginx`（`deploy/infra/compose.yml:57-78`，443 唯一对外口、conf.d 门禁安装热更）；dev＝独立预览网关容器，与生产同吃两份块文件单源（`gateway-proxy.conf:1-7` 头注）。

**网关能否识别「页面切换」（HTML 响应级）并注入 script/banner——能，且已实做：**

- 网关对每个 text/html 响应做字符串替换注入（sub_filter）——**MPA 式页面切换**（每次导航产生新 HTML 文档请求）每页都会注入、脚本每页重跑，无需任何新增感知机制。
- 前置条件已备：上游 `Accept-Encoding` 恒剥保证 sub_filter 命中未压缩 HTML（`gateway-proxy.conf:60-62`）；注入后客户端侧 gzip（`:75-82`，#403）。
- **边界（机制事实，浏览器标准行为，非仓内代码）**：SPA 客户端路由（`history.pushState`）不产生新文档——不过网关、不触发 iframe load；要感知 SPA「页面切换」须在注入脚本内 hook history API（包装 pushState/replaceState＋监听 popstate/hashchange——浏览器扩展/分析工具的成熟手法，纯平台脚本可实现）。
- 网关自持页先例：`error_page 502 504` → `/.aiplatform/unavailable.html`（`gateway-proxy.conf:14`、`:19-31`，internal 直访 404，且该提示页**覆写 sub_filter 为永不命中模式防自注入**——网关对「哪些响应注入、哪些不注入」已有精细控制手法）。
- 信任边界口径（ADR-0034，`docs/adr/0034-preview-gateway-trust-boundary-environmental-blocking.md`）：判据「换个普通 app 放进预览通道也会被挡＝平台兜」；**应用固有行为（AI 写的登录墙/付费墙等产品语义）平台不碰、不回改应用文件**（第 10 行）。试用提示是平台自己的商业化提示、非应用固有行为，网关响应注入不越此界；该 ADR 对响应级「内容改写」在 CSP 条目有备案（Considered Options，第 18 行）——机制家族内有先例认可。

### 1.3 试用/付费态现状：零（如实记录）

- Project 聚合字段全貌（`aiplatform-server/src/main/java/com/semgravio/aiplatform/business/project/domain/aggregate/Project.java:54-106` 起）：name/type/endpointType/workspaceId/ownerAccountId/ownerGuestId/archivedAt/prdProducedAt/首次生成时点等——**无任何试用/付费模式位**。
- metering BC 自述「零商业概念」（`base/metering/package-info.java`）：只记 token 用量与平台成本换算，无用户余额/充值/试用态。
- order 域的「已支付」（OrderStatus 等）是订单交易状态，≠「本项目处于付费模式」的项目级判定；且地图 #510 已把订单域搁置。
- 计费域本体＝票 #513（OPEN，正文自述「全新域（代码零余额/充值雏形）」）。**判定数据当前不存在。**

---

## 二、备选机制盘点

> 评估维度：是否碰生成代码／付费后移除是否干净／复杂度量级／「页面切换」语义强度／覆盖面（重点场景：「新窗口打开」＝`window.open(url)` 裸开预览子域，`system-panel.tsx:196`——**无平台壳**）。

### A. 平台壳层提示（沙箱窗 chrome 区，非页面内）

- **碰代码**：零——纯平台组件（`system-panel.tsx` 一处）。
- **移除**：干净——前端按项目态摘除即可。
- **复杂度**：小。最简形态＝沙箱徽标区（`:250-253`）并列一枚「试用模式」chip，近乎顺手；已有同类常驻文案先例（「沙箱演示环境 · 隔离运行不产生正式扣款」）。
- **语义**：弱——常驻不随页面切换。若做成「iframe onLoad 闪现」：iframe load 事件在其内文档加载完成时触发（机制事实），可覆盖 MPA 页面切换与手动刷新，但 **SPA 路由切换对父窗完全不可见**（无新文档、无 load 事件；要感知只能靠 iframe 内平台脚本回传——回到注入）。
- **覆盖缺口**：新窗口裸开无壳可见。

### B. iframe 通信（平台壳 postMessage 控制悬浮条显隐）

- **单独不成立**：postMessage 需要 iframe 内接收方——接收方要么是网关注入的平台脚本（那机制本体已是 C，B 退化为 C 的联动件），要么写进用户代码（违反硬约束，直接排除）。
- **联动价值**：annotation.js 已有 message 监听器＋信封协议（`annotation.js:407-418`、`annotation.ts:7-16`）——扩一个 `type:"notice"` 信封即可让父窗控制页内提示显隐/频次/用户关闭（父窗天然知道项目态与用户会话），与 C 搭配很顺，独立无用。

### C. 预览网关响应注入（HTML 响应插 script/banner）——推荐

- **碰代码**：零——与圈注同模式实证：脚本引用只在响应流，用户工作区/容器零字节（§1.1）。
- **移除**：干净——两层收口皆平台侧：判定数据侧（付费后查询接口回「不显示」，脚本静默）或网关侧（撤 sub_filter 行，重新装配＋门禁 reload 全量消失）。
- **语义**：最强——MPA 每页天然注入重跑；SPA 可在脚本内 hook 路由 API（成熟手法）；**唯一覆盖新窗口裸开**（裸子域流量同样过网关）。
- **复杂度**：中。构件清单（量级评估，非施工设计）：
  1. 提示脚本资产一件（或并入 annotation.js 扩信封），装配清单 `assemble-assets.sh` 增列＋`gateway-inject.conf` 增一条 sub_filter——小；
  2. 试用态查询端点（平台后端只读 GET）——小；**注意：预览子域捕的是 workspaceId 而非 projectId**（`aiplatform.conf:71` 正则捕获 → 容器 `ws-{id}`；workspaceId≠projectId，映射在后端），端点入参口径按 workspaceId 设计；
  3. 查询通道二选一：脚本跨源 fetch 平台域（需 CORS 对动态预览子域回显校验）；或网关在 `/.aiplatform/` 同前缀下加 location 反代平台端点（同源零 CORS，贴合自持资产先例；只读 GET 开口面小，但预览域→平台后端是新开口，安全口径随实施票过审）；
  4. SPA 路由 hook＋频次/去抖控制（频次与文案策略随 #513/#514）——小-中。
- **前置依赖**：试用/付费态判定数据（#513，当前不存在）。解耦口径：#513 未落期间按地图 #510「新项目一律五问试用开局（存量项目不迁移）」——演示阶段新项目皆试用态，提示恒显恰好与现状语义一致；#513 落地后改数据驱动熄灭，机制不动。

### D. 其它在代码中发现的可借通道

- **unavailable.html**（`gateway-proxy.conf:14-31`）：网关自持错误页（error_page 内部重定向）——形态是「网关自己产 HTML」而非注入用户 HTML，非本用途通道；但坐实 `/.aiplatform/` 自持资产模式与 sub_filter 覆写防自注入手法，C 的构件 3 可直接沿用该前缀与手法。
- **快照路由（snap-{viewId}）不注入**（`aiplatform.conf:78-86`；ADR-0007/0014「只逛不换」）：圈注不进快照的语义理由是「在『当时』的页面上圈注发给『现在』的系统语义乱」——**试用提示无此错乱**，若要覆盖「查看当时」快照体验（`demo-version-view-dialog.tsx` 弹窗 iframe 同走快照子域）需显式扩口径（给快照也装配提示）；不扩则快照面无提示。属口径题，建议随实施票定，本报告只备案。
- SSE/对话面报价卡先例（ADR-0017）：与预览面提示正交，不复用。

---

## 三、结论三选一

**可行——推荐机制 C（预览网关响应注入试用提示脚本，复用圈注注入通道模式）。**

理由：

1. **零侵入是实证不是推演**：同模式已承载圈注在线运行（网关 sub_filter＋网关自持资产＋父窗 postMessage 协作全套现成）；ADR-0014 明文「不进用户源码、不在容器内」。硬约束在现有通道上直接满足。
2. **「页面切换时」语义最强**：MPA 逐页天然成立；SPA 一段平台脚本 hook history API 即成立；且是唯一覆盖「新窗口打开」裸子域场景的方案（A 无壳可挂、B 依赖 C 才有接收方）。
3. **付费后移除干净**：提示字节从不落用户工作区；熄灭＝平台判定数据一处（或网关注入行一撤），不存在「改用户代码摘提示」的路径——硬约束的第二半同样满足。

实施顺序口径：**机制与判定数据解耦**。#513 未落期间可按「新项目一律试用开局」全量恒显先上（与地图 #510 现口径一致）；#513 落地后接判定数据自动熄灭、机制零改动。文案与触发频次策略随 #513/#514（票面已注明）。

**降级备案**（若 C 被裁重或排期不及）：A 的最简形态＝沙箱徽标区并列「试用模式」chip（`system-panel.tsx:250-253` 处）——成本小到近乎顺手、零碰代码、移除干净，代价是不随页面切换、不覆盖新窗口；届时按地图票「复杂则优先级放低可不做」的精神处置，语义弱化属可接受的折损。

---

## 附：引用文件清单（本报告全部一手来源）

| 文件 | 用途 |
|---|---|
| `deploy/nginx/aiplatform.conf` | 主预览/快照 server 块、注入块 include 面（:68-86） |
| `aiplatform-server/src/main/resources/docker/gateway/gateway-inject.conf` | sub_filter 注入＋资产 location（:9-17） |
| `aiplatform-server/src/main/resources/docker/gateway/gateway-proxy.conf` | 代理块、error_page 自持页、Accept-Encoding/XFO/gzip（:14、:19-31、:60-66、:75-82） |
| `aiplatform-server/src/main/resources/docker/workspace/annotation.js` | 圈注脚本本体：非常驻、postMessage 协议、生命周期（:4-5、:46-53、:407-426） |
| `deploy/nginx/assemble-assets.sh` | 网关资产装配通道（:27-35） |
| `aiplatform-web/src/components/project-page/system-panel.tsx` | 沙箱窗壳、iframe、圈注父窗侧、新窗口打开（:121-157、:196、:250-253、:306-352、:385-398、:466-474、:558-569） |
| `aiplatform-web/src/components/project-page/preview-toolbar.tsx` | 圈注工具条（平台件，:29-31） |
| `aiplatform-web/src/lib/preview/annotation.ts` | 圈注协议正本（:7-16） |
| `aiplatform-web/src/hooks/use-project-preview.ts` | 预览地址来源（:11-19） |
| `aiplatform-web/src/components/demo-experience/demo-experience-page.tsx` | Demo 体验页装配（:169-177） |
| `aiplatform-server/.../base/workspace/domain/model/WorkspaceNaming.java` | 预览 URL 纯函数（:68-76） |
| `aiplatform-server/.../business/project/domain/aggregate/Project.java` | 项目聚合无试用/付费位（:54-106） |
| `aiplatform-server/.../base/metering/package-info.java` | metering 零商业概念 |
| `deploy/infra/compose.yml` | 生产共享 nginx 承载（:57-78） |
| `docs/adr/0014-preview-gateway-subdomain-routing-injection.md` | 网关注入决议正本（「不进用户源码、不在容器内」） |
| `docs/adr/0034-preview-gateway-trust-boundary-environmental-blocking.md` | 信任边界判据、内容改写备案 |
| `docs/adr/0007-version-view-snapshot-pgdata-copy.md` | 快照「只逛不换」口径（快照不注入的语义来源） |
| GitHub Issues #510（地图）、#513（计费域 OPEN）、#519（本票） | 需求与现状口径 |
