# 预览网关信任边界：环境性阻断在网关收口（/_next/ Origin＋X-Frame-Options 剥离），应用固有行为不兜

生产 demo「界面永远停在加载中」排查实锤：workspace 以 `next dev` 起服，Next 15.2+ dev 服务器自带跨源防护——`Origin` 头不在白名单（localhost 系＋启动主机名）的请求对 `/_next/` 资产一律 403；浏览器里恰好只有 crossorigin/module 方式加载的 chunk 发 Origin 头，于是「两个 JS 403、其余正常、地址栏直访正常」，而平台收口探活（裸 curl 打 `/`）对此全盲——探针报 200、真浏览器 403，坏系统照样收口交差。这类与 AI 产物产品语义无关的阻断不止 Origin 闸门一族：平台以 iframe 嵌入展示预览，AI 系统自带 X-Frame-Options（Node 安全中间件默认值）同样白屏。本决策（#392，实施 #394–#398）确立统一的责任判据与收口机制，替代逐个打地鼠。

## 责任判据：环境性阻断 vs 应用固有行为

**判据一句话：换个普通 app 放进预览通道也会被挡＝平台兜。**

- **环境性阻断**（平台兜）：平台链路、框架默认或展示方式造成的阻断——Next dev 的 Origin 闸门、安全中间件默认 XFO 拒 iframe。与 AI 写了什么无关，换个基座模板空应用放进预览通道照样被挡。
- **应用固有行为**（平台不碰）：AI 给系统自己写的产品语义——登录墙、权限、付费墙。是系统作者的意图，平台不兜底、不回改应用文件。

预览网关是预览流量的信任边界：ADR-0014 的子域路由让一切预览流量（主预览＋快照）过网关这一跳（部署形态承载体见其 #386 演进注记——共享 nginx 的 conf.d 配置），而 iframe 嵌入与预览子域都是平台选择的展示方式——由此而来的阻断属平台责任，收口位置就在网关：单点覆盖存量＋未来一切 app，零 per-app 配置，存量 workspace 零触碰（部署走资产装配＋门禁安装＋reload）。

## Considered Options

- **Origin 剥离承载面：网关收口（采纳）**——`/_next/` 前缀请求剥 Origin（nginx `proxy_set_header` 置空值＝整头不下传），对任意 Next 版本成立（闸门只看头存在与否，不看版本行为差异）。per-app `allowedDevOrigins`（出局：只够得着新起 workspace 的 baseline 模板，够不着存量——存量的 next 配置是 AI 生成代码，平台不回写；且依赖 AI 在后续迭代中保留该配置，一次重构即退化）。baseline 模板仍加 `allowedDevOrigins: ['*.preview.semgravio.com']`，但定位是语义自文档（不经网关直连、容器内排查时行为也正确，#395），不是修复主力。
- **X-Frame-Options 剥离：网关响应侧 `proxy_hide_header`（采纳）**——XFO 与平台选择的 iframe 展示方式冲突，属环境性阻断。clickjacking 取舍：demo 预览为公网演示环境、承载的是用户生成的演示系统（非生产数据面），攻击面可接受；主站路由不经该代理块，行为不变。
- **CSP：整头不剥（采纳，frame-ancestors 记已知开口）**——frame-ancestors 只是 CSP 的一条指令，原版 nginx 删不了单条指令；整头剥会洗掉应用自身的非展示安全语义，越线到应用固有行为。已知开口的触发条件＝AI 显式给系统设带 frame-ancestors 的 CSP（安全中间件默认值不含它，实测罕见）；升级路径＝headers-more（headers-more-nginx-module，整头级 set/clear、配合 map/正则派生值改写 CSP）或内容改写，触发后再议、不预装。**未来见「预览白屏＋响应带 frame-ancestors」先查本 ADR——这是留档的已知取舍，不是 bug，不要用整头剥来「修」。**
- **修复半径：仅网关层（采纳）**——存量 workspace 任何文件不动（含 next 配置与依赖版本）；生效＝资产装配→门禁安装→nginx -t→reload（reload 优雅退出，活跃 HMR ws 断线一次自动重连）。

## 决议

- 单源落预览网关共享代理块（`gateway-proxy.conf`，#141 拆分口径）：dev 预览网关与生产共享 nginx conf.d（`deploy/nginx/aiplatform.conf`）同吃一份。具体写法须避开 nginx `proxy_set_header` 继承陷阱（location 级一旦声明即不继承外层）——新 location 复制指令组或公共头提升＋map 变量按 URI 派生皆可，写法可换、语义以冒烟断言为准（#394）：`/_next/` 请求上游收不到 Origin；非 `/_next` 请求上游收到 Origin（透传不误伤）；上游发 X-Frame-Options 时经网关的响应无此头。
- `/_next/` 剥 Origin 顺带修复 HMR 握手；其余路径 Origin 原样透传——Server Actions 的 CSRF 校验、AI 自写的 Origin 校验不受扰（应用语义零扰动是硬约束）。
- **覆盖面增补（#403）**：Next dev 闸门护 `/_next*` 与 `/__nextjs*` **两族**（实测 `__nextjs_font*`、`__nextjs_original-stack-frame*` 带 Origin 全 403；字体按 CSS Fonts 规范 CORS 模式加载必带 Origin、同源也带）——Origin 剥离扩到 `__nextjs` 族，判据与机制不变（同属环境性阻断，网关兜）。
- **传输收口（#403）**：预览代理块对上游恒剥 `Accept-Encoding`（sub_filter 注入依赖，#141 起）曾使全链路 identity——Next dev 资产 MB 级（1.06MB chunk→gzip 185KB，5.75×）且 `no-cache` 每次导航回源重验证/重传，跨境单流带宽下未压缩传输成为预览「能用了但热切换也慢」的主因。收口＝网关**客户端侧** gzip（nginx 在 sub_filter 注入后压缩发客户端；上游仍恒收 identity，注入依赖不动）；上游缓存策略不动（dev 要新鲜度，迭代语义优先）。
- 预览与快照响应剥 X-Frame-Options；CSP 整头透传（见 Considered Options）。

## 三层防线

1. **网关结构性根除**：覆盖存量＋未来一切 app、对任意 Next 版本成立——主力层。
2. **部署演练回归**：排练脚本既有预览探针加浏览器头组合断言（带 Origin 打 `/_next` 断言非 403，#396）——防网关配置退化（「谁把代理块改回去」当场红）。
3. **收口探针说真话**：既有就绪探活升级为浏览器真实头形态——带预览域 Origin/Referer/Sec-Fetch 打 `/` 断言非 403（2xx/3xx 均可），HTML 引用 `/_next/static` 时对一个 chunk 带 Origin 复测、无引用则跳过（非 Next 应用零误伤，#397）。失败语义复用既有待期（WSP_012）与 readiness 超时，不新增机制。本层防未知新闸门（如未来 baseline 升级 Next 引入新 dev 默认）。
   - **勘误（#397，以本注为准）**：初稿「容器内回环探针带上预览域 Origin 即复现真实浏览器行为——收口判据照此实现」的口径**仅适用于复现应用级闸门**（#396 排练探针的手法）；作为**收口判据**探针必须走网关路径——闸门按请求头不按来源地址，网关收口（#394）落地后回环直连带 Origin 打 `/_next` 仍会 403，照初稿实现＝全部 Next workspace 误红、待期风暴。
   - **实现形态（#397 落地）**：探针从工作区容器内经 previewnet 网络别名 `preview-gateway`（dev 网关与生产共享 nginx 同名挂别名——网络别名契约，见 `WorkspaceNaming.PREVIEW_GATEWAY_ALIAS`）＋Host 头选 `{id}.{previewBase}` 路由打到网关。curl 7.88 的 `--resolve` 地址段不收主机名（实测 exit 49），Host 头是同效形态；**生产 TLS 落地（conf 换 443＋80 跳转）时探针须改 `--resolve` 形态带 SNI**（80 跳转形态下探针只会看到 3xx、探不到上游）。网关不可达＝判据如实待期，不误报绿。

## Consequences

- 用户不改系统里任何文件，平台更新后存量预览自动恢复可用（生产活体验收对事发 workspace 376467559789526725 带 Origin 打涉事 chunk 红→绿留档，#398；验收记录不进 CI）。
- 收口判据升级（#397）的部署耦合：后端与网关别名（`preview-gateway`）须同窗落地——别名缺席期间收口探针如实待期（预览全红是提醒不是故障）；别名是 Docker 网络元数据非 nginx 配置（门禁/reload 碰不到），既有 nginx 容器可 `docker network connect --alias` 热补零停机（Docker 29.x 实测），compose 声明为正本防重建漂移——顺序见部署文档 §3。
- 语义保证面：非 `/_next` 路径头透传不误伤、应用自身 5xx 不换脸（#180 口径）不变；XFO 剥离只发生在预览/快照代理路径。
- #403 两收口的回归面：网关冒烟扩断言（`__nextjs` 族无 Origin 回显＋上游无 Accept-Encoding 回显且客户端含 `Content-Encoding: gzip`）、排练 ⑧ 同步；生产活体验收＝字体带 Origin 200＋大 chunk gzip 下载耗时对比留档（同 #398 口径，不进 CI）。
- 未来 Next 升级可能引入新的 dev 默认闸门——防线是三层结构，不承诺对具体版本免疫。
- 同类问题再来时按判据归责（CONTEXT.md 术语「环境性阻断／应用固有行为」），不再从头争论。
