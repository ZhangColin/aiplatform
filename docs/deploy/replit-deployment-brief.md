# aiplatform → Replit 部署需求简报

> 读者：Replit（Agent／部署侧）。这是我们平台的第一次真实生产化尝试，目标是**演示环境、全功能不阉割、不改业务代码**（环境变量、配置文件、构建辅助可动）。请通读 §1 的环境事实，对照 §2 的硬需求给出支持性结论；某项不支持请直说，并按 §4 的问题清单给出你们建议的接法。

## 1. 环境如实陈述

### 1.1 组件与端口拓扑

| 组件 | 技术栈 | 端口 | 对外 | 说明 |
|---|---|---|---|---|
| aiplatform-web | Next.js 16 standalone（React 19 / pnpm） | 3333 | **唯一对外口** | 浏览器只发同源相对路径 `/api/*`、`/auth/*`，由 next.config.ts rewrites 转发到后端（`BACKEND_URL`，**构建期求值烤进产物**）；SSE 走 `/api/events`（`compress:false` 防 gzip 扣流） |
| aiplatform-server | Java 21 Spring Boot（Maven，`--enable-preview`） | 8888 | 内部 | 全部业务 API、智能体编排（AgentScope 跑在 JVM 内） |
| embedding 服务 | Python fastembed（BAAI/bge-small-zh-v1.5，512 维） | 9091 | 内部 | `aiplatform-server/scripts/embedding_server.py`，绑 127.0.0.1；后端经 `EMBEDDING_URL` 访问 |
| PostgreSQL | ≥15 且**必须带 pgvector** | 5432 | 内部 | 库需预建；Flyway V1–V45 首启自动建表；V1 第一步即 `CREATE EXTENSION vector`（缺 pgvector 直接失败起不来） |
| 工作区沙箱容器 | Docker（见 1.2） | 容器内 8081 | 经预览网关 | 平台核心 |
| 预览网关 | nginx（配置在仓） | 80/443 | 泛子域 | 见 1.3 |

主站**没有** nginx 反代层：对外入口就是 Next.js（3333），单端口天然匹配 Replit 单 HTTP 端口规则，Replit 边缘 TLS 可替代任何主站反代。Redis 平台核心不依赖（可不配）。

### 1.2 工作区沙箱（硬需求核心）

- 实现：`DockerEnvironmentBackend`，**docker CLI 子进程**（非 docker-java 库）。设计上是可替换接口（代码注释预留「上云换 TKE 适配器」）。
- 每个工作区＝一个常驻容器：`docker run -d --name ws-{id} --restart unless-stopped --network previewnet -v vol-ws-{id}:/workspace aiplatform/dev:0.13 sleep infinity`。无宿主端口映射。
- 容器内 all-in-one：用户生成应用（node，**8081**）＋PG15（`/workspace/data/pg`，回环 trust）＋redis（回环）＋git。智能体不进容器，平台 JVM 经 `docker exec` 驱动文件面。
- 镜像 `aiplatform/dev:0.13`：node:22-bookworm ＋ postgresql-15 ＋ redis ＋ pnpm@10 ＋ Playwright/Chromium 渲染器（**当前 zip 为 arm64 专用，amd64 主机需换包**，见 Dockerfile 内注释）＋ Next.js 基座模板。镜像由后端从 classpath 资源现场 `docker build`（ensureDevImage），**jar 运行形态会抛异常，须由部署流程预置镜像**（此缺口在自有服务器同样存在，属我们侧待办）。
- 生命周期：闲置 60min 删容器保卷 → 深度唤醒（解包＋pnpm install，10min 探活窗）→ 30 天封存（`seal-archives/`，生产应挂独立卷）。
- 出网 allow-all：容器内 pnpm install、`git clone` github；模型 API 出网在**平台进程**（ark.cn-beijing.volces.com、api.deepseek.com、api.bocha.cn 等中国大陆端点为主）。

### 1.3 预览网关与子域路由

- 配置在仓：`aiplatform-server/src/main/resources/docker/gateway/`。
- 路由：`{workspaceId}.{PREVIEW_BASE}` → `ws-{id}:8081`（Docker 内置 DNS 按容器名解析）；快照 `snap-{viewId}.{PREVIEW_BASE}` 同理。
- prod：80→443 跳转＋泛域名证书（Let's Encrypt，DNS-01）；`PREVIEW_BASE` **无默认值，缺失启动 fail-fast**。
- 主预览页 sub_filter 注入标注脚本；WebSocket 升级透传；`proxy_read_timeout 3600s`。

### 1.4 数据库细节

- Flyway `classpath:db/migration`（V1–V45），`baseline-on-migrate: true`，历史表 `aiplatform-server_flyway_schema_history`。
- V44 种子经 Flyway placeholder 注入 `VOLCENGINE_API_KEY`/`DEEPSEEK_API_KEY`（**首启迁移时密钥必须在场**）。
- `wsp_workspaces` 表是容器/卷/库的注册表正本（清理与判定准绳）。
- 无内置备份预期：自备 `pg_dump`。

### 1.5 登录（本部署不接 SSO）

- 不配任何 `SSO_*`：后端照常启动（无 fail-fast）。前端顶栏「登录」链接与受保护页 302 会指向不可达的 `/auth/login`（SSO 流）——**已知死链，不视为故障，不修**。
- 可用路径①访客：首页匿名提交首条需求（`POST /api/projects`）→ 自动发 `aiplatform_guest` cookie（30 天）→ 全功能可用。
- 可用路径②验证码登录：M01 弹窗（触发点：保存项目／签约门／任意 401）→ `POST /auth/code`＋`/auth/verify`，验码即登录即注册，访客项目自动平移。**发送通道＝后端日志**（设计内过渡态，无需 SMTP/短信配置），演示时从后端日志读码；验证码存内存，后端重启作废；`EMAIL/PHONE` 通道开关**不可全关**（默认邮箱开）。
- `/api/backoffice/**` 机机签名面本部署不启用（不配 `OPENAPI_*`）。

### 1.6 密钥／环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 是 | `prod` |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | 是 | 指向 Replit PG |
| `PREVIEW_BASE` | 是 | 预览泛子域基域 |
| `EMBEDDING_URL` | 是 | 指向 embedding 服务（同机 `http://127.0.0.1:9091`） |
| `VOLCENGINE_API_KEY`、`DEEPSEEK_API_KEY` | 是 | 对话主通道（V44 迁移期注入库） |
| `BOCHA_API_KEY` | 是 | 外部资料搜索 |
| `IMAGE_GEN_PROVIDER`＋`ZHIPU_API_KEY`/`ARK_API_KEY`/`OPENAI_IMAGE_API_KEY`/`DASHSCOPE_API_KEY` | 视选型 | 出图 |
| `VERIFICATION_*` | 默认即可 | 勿全关通道 |
| `REDIS_HOST/PORT`、`OPENAPI_*`、`SSO_*` | 不配 | 核心不依赖／不启用／不接 |
| 前端 `BACKEND_URL` | 是（**构建期**） | 同机部署给 `http://127.0.0.1:8888`；换后端地址需重建 |

## 2. 硬需求清单（验收口径）

| # | 需求 | 验收 |
|---|---|---|
| R1 | 沙箱容器置备 | 用户发起任务 → `ws-{id}` 容器可创建/exec/删除，镜像由部署侧预置 |
| R2 | 预览可达 | `{id}.{PREVIEW_BASE}` 从公网（含大陆）可打开工作区内应用 |
| R3 | pgvector | 首启 Flyway V1–V45 全绿 |
| R4 | SSE 长连接 | `/api/events` 流式帧可达（对话直播） |
| R5 | Java 21 | `--enable-preview` 构建并运行 |
| R6 | 多进程单端口 | web 对外（Replit `PORT`），server＋embedding 内部；一条 run command 拉起并崩溃自愈（示例：后台依次起 embedding→java→node，`wait -n` 任一退出即整体重启） |
| R7 | 模型 API 出网 | ark.cn-beijing.volces.com 等从部署环境可达 |
| R8 | 大陆访问冒烟 | 大陆网络直访首页（我们已有活体经验可用，保留冒烟项） |

## 3. 我们已核实的 Replit 事实与沙箱三接法

事实基线（若有误请纠正）：部署环境为 Nix、无 docker/特权/docker.sock；单 HTTP 端口＋边缘 TLS＋`*.replit.app`/自定义域名；Autoscale 缩零（支持 SSE、不支持 WebSocket）、Reserved VM 常驻；Replit PG 15.2 自带 pgvector、同区域内网、无自动备份；push→自动部署非原生。

沙箱与 R1 的冲突及接法：

| 接法 | 改动量 | 隔离 | 说明 |
|---|---|---|---|
| 一 外部 docker host：`DOCKER_HOST=ssh://…` 指向我们的 VPS | **零代码（纯环境变量）** | 完整（在 VPS 上） | 预览网关随迁 VPS（需泛域名＋证书）；每次 exec 跨公网；**需 Replit 放行出网 SSH 长连接** |
| 二 沙箱云（E2B/Daytona 类） | 新写 `EnvironmentBackend` 适配器（真实开发量） | 完整 | 需对齐卷持久化/休眠/封存生命周期 |
| 三 同机 Nix 进程化 | 最大（重写沙箱机制） | 无 | 不推荐 |

接法二长期即我们预留的 K8s/TKE 路线；Replit 这次是提前逼出该课题。

## 4. 请 Replit 回答

1. 沙箱：你们有什么方式支持 §1.2 这种沙箱？三接法中你们能配合哪个？
2. 若接法一：出网 SSH 长连接是否允许且稳定？工作区镜像（docker build context 数百 MB）跨网构建你们建议怎么走？
3. R6 多进程：你们有无官方 supervisor 模式／进程守护建议？
4. PG：pgvector 具体版本？与部署同区域确认？备份自备 pg_dump 有无限制提醒？
5. 域名：主站用 `*.replit.app`，预览泛子域不经 Replit（DNS 直指外部网关）——两者并存有无冲突？自定义域名只支持单主机名吗？
6. Java：JDK21＋Maven 构建的内存档建议？构建机与运行机可否分离（构建吃内存、运行 4GB 够）？
7. 自动部署：GitHub Actions → Replit API 触发部署的官方姿势？
8. 任何你们认为更优的接法请直接提出（附需要的改动清单）——我们接受配置级改动，机制级改动单独评估。

## 5. 预期改动清单（我们侧，均为配置/资产级）

- Secrets 按 §1.6 注入；`BACKEND_URL` 构建期注入。
- amd64 chromium 资产：dev-image.Dockerfile:44 附近 arm64 zip → 按架构取包（外部 host 与 Windows 新机同依赖此资产）。
- 父 POM `com.cartisan:cartisan-boot:0.1.0-SNAPSHOT` 不在本仓：发 GitHub Packages（私有＋token）或随仓 vendored——待定二选一。
- `.replit`／replit.nix（或 Modules）随仓版本控制。
- 分支策略：优先对上 main；被迫机制级改动 → 单独 replit 分支。
- 已知死链（顶栏登录 → `/auth/login`）不修，演示口径绕行。

## 6. 附录

### 6.1 备选：纯 VPS 对照

| 维度 | Replit（＋外部 host） | 纯 VPS（2C4G，香港/国内，¥50–150/月） |
|---|---|---|
| 改动量 | 环境变量级＋资产 | **零**（现开发形态即生产形态） |
| 沙箱 | 外置 VPS，跨公网 exec | 本机原生 |
| 月成本 | Replit 档位费＋VPS 费（两头） | 单 VPS |
| 运维面 | 平台在 Replit、沙箱在 VPS（双轨） | 单机 |
| 大陆可达 | 边缘在海外 | 可选国内/香港 |

结论：Replit 反馈不理想时的无缝退路。

### 6.2 深入文档

`aiplatform-server/docs/guide/生产部署.md`（拓扑与环境变量正本）、`本地开发启动.md`；ADR-0001（单容器工作区）、0014（预览网关）、0016（闲置休眠）、0019（沙箱出网）。
