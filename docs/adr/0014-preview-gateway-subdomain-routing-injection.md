# 预览网关：平台级 nginx 反向代理（子域路由 + 圈注注入），替换随机端口暴露

系统预览从「Docker 随机宿主端口（`localhost:随机端口`）直接暴露」改为「平台级 nginx 网关按子域路由 + 注入圈注脚本」。每个工作区一个稳定子域 `{workspaceId}.preview.{domain}`（开发 `{workspaceId}.localhost`），网关按 Host 子域路由到对应容器 `ws-{id}:8081`，并在 HTML 响应注入平台标注脚本（annotation.js）。一套配置吃开发与生产两个 profile（只差 base 域名与 TLS）。

## 定位

- 平台级反向代理（nginx），独立于后端业务逻辑、独立于用户容器。不是容器内代理、不是源码注入。
- 预览 URL 对前端是不透明值：网关可替换（如 Traefik）只动网关配置，前后端零改动——这是「网关可替换」的扩展点。
- 对标 Replit（`*.replit.dev`）、Lovable（`*.lovable.app`）、v0（isolated domain + iframe），容器只跑 app。

## Considered Options

- **随机宿主端口直连**（现状）：地址不稳定不可分享、生产不可用、圈注脚本挂不到主 Next.js 应用——被否。
- **路径前缀路由（`/preview/:id` 反代）**：同一 origin 下预览与平台混流，用户生成代码可碰到平台后端（安全边界弱）；Next.js 绝对资源地址在 base path 下易断裂——被否。
- **子域 + 通配 server 块**（本方案）：隔离 origin（安全）+ 资源地址不破 + 零 per-app 配置——选之。

## 决议

- 容器命名：裸 `ws-{id}`（去 kind 后缀——网关按子域 `{id}` 反查容器名 `ws-{id}`，名字必须与子域一一对应），进共享网络 `previewnet`，不再 `-p` 随机映射宿主端口。
- 路由：`server_name ~^(?<wsid>[0-9]+)\.localhost$` 通配正则捕获 workspaceId；`resolver 127.0.0.11` 指向 Docker 内置 DNS；变量 `proxy_pass http://ws-$wsid:8081`。一条 server 块吃所有项目，零 per-app 配置、零 reload。
- 注入：`sub_filter` 把标注脚本引用注入 HTML（`</head>` → `<script src="/.aiplatform/annotation.js"></script></head>`），脚本资产由网关自持（`location = /.aiplatform/annotation.js`），不进用户源码、不在容器内。上游 `Accept-Encoding` 置空保证 sub_filter 命中未压缩 HTML。
- 后端改造：工作区容器不再随机映射端口；预览 URL 是 workspaceId 子域（`{id}.{base}`，base = 开发 `localhost` / 生产 `preview.{domain}`）的纯函数，不落库（`preview_port` 列随概念出局删除）。应用仍监听 8081，收口判据（容器内 8081 探活）不变。本片只落 dev：`previewUrl` 恒拼 `http://`，生产 `https` 需把 scheme 参数化（未来切片扩展，现不做投机参数）——#129 已把 scheme 参数化落位（`app.workspace.preview-scheme`，默认 http / prod https）。
- 开发/生产同构：同一份 nginx 配置（路由/注入块不变）；开发 base `*.localhost`、关 TLS；生产 base `*.preview.{domain}` + wildcard 证书（certbot dns-01，TLS 终止）。nginx 侧同构，后端 URL 的 scheme 差异见上一条——#129 已补齐 prod 侧（`nginx.prod.conf.template` + 生产 URL https）。

## 范围

本片只覆盖主工作区容器（dev 预览）。「查看当时」快照容器（#92）是独立临时视图，仍走各自随机端口（`SnapshotHandle.previewPort`），不纳入网关注入——非本片范围。

## 未来演进（备案，带触发器）

Traefik 作为可替换备选——因 `preview.url` 对前端不透明，换网关只动网关配置、前后端零改动。触发器：需要动态配置重载以外的网关能力（指标/限流/多节点 LB 等）时论证引入。

## Consequences

- 预览地址稳定、可分享、生产可用；沙箱重建后子域不变。
- 圈注脚本网关注入后，主 Next.js 应用的圈注真功能接通（「指哪说哪」成立）。
- 预览流量走平台边缘、子域独立 origin——用户生成代码碰不到平台后端（安全边界）。
- 生产证书一张 wildcard 覆盖所有项目（非每项目一签）。
- 交付物：nginx 网关配置（`src/main/resources/docker/gateway/nginx.conf` dev + `nginx.prod.conf.template` prod，路由/注入块单源在 `gateway-route.conf`）+ 部署指南（`docs/guide/`）。
