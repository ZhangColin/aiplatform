# semgravio-infra——共享基础设施一键部署单元

nginx＋pgsql＋redis 三件套，**自包含、整目录可拷走单独用**（不含任何平台专属服务）。
任何接入服务（本平台、后台前后端、app-registry 等）都按「接入契约」（本 README 下文）
接入这同一套基础设施；不部署平台，这套东西本身也有用。

## 一键起栈（干净 Ubuntu 从零 / 重跑收敛，同一条命令）

```bash
./deploy.sh
```

幂等做的事（每步已具备即跳过，重跑不改变现网状态、不动数据）：

1. docker / compose v2 缺失时官方脚本 bootstrap（已具备时行为不变；大陆机加
   `INSTALL_MIRROR=Aliyun ./deploy.sh` 走阿里源装）；
2. 幂等创建网络 `webnet`（共享接入网）＋ `previewnet`（平台沙箱网，nginx 双挂所需）；
3. 幂等创建 external 数据卷 `semgravio_pgdata`；
4. `.env` 缺失时生成随机凭据正本（**仅全新机器**；接管既有服务器必须先手写现值，见下）；
5. `docker compose up -d` ＋ 等三容器 healthy。

本地排练 / eu-1 迁移前排演：`./selftest.sh`（本地 Docker 从零跑通：起栈健康＋幂等重跑
＋回环连通＋形态断言；要求 80/443/5432/6379 空闲）。

## 凭据模型

- **`.env` 是服务器上唯一凭据正本，不入 git**（模板见 `env.example`）。
- 全新机器：`deploy.sh` 自动生成随机值。接管既有机器（如 semgravio-eu-1）：
  **必须删除生成的 `.env`、手写现值再跑**——`POSTGRES_PASSWORD` 延用原值（数据卷
  超管密码不变，写错只会连不上、不会改密码），`REDIS_PASSWORD` 按迁移 SOP 新设。
- 应用只拿**分片凭据**：自己的库账号＋redis 密码；PG 超管密码不下发给任何服务。

## 组件口径

| 组件 | 形态 |
|---|---|
| **pgsql**（`semgravio-pg`） | pgvector/pgvector **0.8.7**-pg16（pin 精确版本，与 eu-1 现网一致）；超管 `aiplatform` 延用现值；数据卷 **external 固定名 `semgravio_pgdata`**（`down` 不删、与项目名解耦）；绑宿主**回环 5432**（SSH 隧道直连本地 dbx 客户端，用法见 `docs/deploy/prod-db-access.md`，安全组不放行）。**不预建服务库**——各服务接入时经 `ensure-db-user.sh` 自建（见「建库建号」节），既有库原样延用。 |
| **redis**（`semgravio-redis`） | redis **7.4.12**-alpine；`requirepass` 启用（行为变化，后端配置项见 #383）；绑宿主**回环 6379**；**仅缓存用途：持久化全关（`--save ""` `--appendonly no`），重启/重建即空，需要持久 kv 的服务不要依赖它**。compose 不声明任何持久卷（镜像自带 `VOLUME /data` 声明产生的匿名卷是 docker 行为，不写入任何数据、`docker compose down` 一并回收）。 |
| **nginx**（`semgravio-nginx`） | nginx **1.30.6**-alpine；绑宿主 **80/443**（唯一对外口）；双挂 `webnet`＋`previewnet`——跨网路由只在此容器发生（沙箱不可信负载经 nginx 出网、触达不了 pg/redis）。`nginx/nginx.conf` 主体归 infra；服务配置走 `conf.d/`＋`certs/`（见契约）。 |

镜像一律 pin 精确版本：升级＝显式改 `compose.yml`（重跑永远无害的根基）。

> **命名归属**：`semgravio-pg` / `semgravio-redis` / `semgravio-nginx` 三个容器名（及共享网
> DNS 名）归本单元独有——dev 栈、其它应用栈不得占用；本地排练撞名先 `docker rename` 让位
> （如本机 dev redis 即已改名 `semgravio-dev-redis`）。
>
> **previewnet 归属**：创建由 infra 兜底（nginx 双挂所需、AC 要求两网幂等存在）；沙箱容器
> 的成员管理仍归平台部署——infra 只管网络存在，不管谁进来。

## 接入契约（服务接入方照此办理）

- **入网**：你的服务 compose 里挂 external 网络 `webnet`（容器名即 DNS，直连
  `semgravio-pg:5432` / `semgravio-redis:6379`，不经宿主端口）。
- **建库建号**：用 infra 的幂等建号 helper `ensure-db-user.sh`（随本目录走，用法与权限
  边界见下文「建库建号」节）——每服务独立 database ＋低权限角色，只对自己的库有权限；
  超管凭据不出 infra。
- **nginx 配置**：恰好两个资产——`nginx/conf.d/<service>.conf` 单文件＋
  `nginx/certs/<service>/` 证书目录。**安装/更新唯一合法通道是门禁脚本
  `install-nginx-conf.sh`**（用法与裁决语义见下文「nginx 配置安装」节）——共享
  nginx 是最危险的共享故障点，别让一个服务的坏配置弄挂所有人。
- 证书：infra 只定目录约定；申请/续期人工 certbot。

### 建库建号：`ensure-db-user.sh`（幂等＋低权限）

接入方一条命令为自己的服务拿到专属 database ＋ 低权限用户：

```bash
DB_PASSWORD=$(openssl rand -hex 16) ./ensure-db-user.sh <服务名>
```

- **服务名口径**：`^[a-z][a-z0-9_]{0,30}$`，直接作库名/角色名用（合法 PG 标识符、免引号）。
  库名＝角色名＝服务名。
- **幂等（ensure 语义）**：重复执行结果一致——已存在即对齐（角色属性/密码、库属主、
  权限全部重放），无报错、无重复创建。要跨次结果一致，每次显式携带同一个
  `DB_PASSWORD`；换新值即是对齐改密（接入方自行发起）。
- **权限边界**：角色 `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE`；库属主＝服务用户，
  全权支配自己库内的对象；本库 `CONNECT` 对 `PUBLIC` 收回——服务只对**自己名下的库**
  可连可用，连接其它服务的库直接被拒（`permission denied for database`）。
  维护库（`postgres`/`template1`）的 `CONNECT` 也从 `PUBLIC` 收回——新角色的
  可连接面只有自己的库（超管/属主不受 ACL 影响，运维面不变）。既有非托管库的
  表级权限本就不在 `PUBLIC`，即使连得上也读不到数据。selftest 有两遍幂等＋
  越库/维护库被拒的活断言（phase 4）。
- **凭据分片口径**：脚本内部以超管执行（`.env` 正本，不经接入方），**stdout 只有**
  服务自身分片：`DB_HOST=semgravio-pg`（webnet 内 DNS，不经宿主端口）/
  `DB_PORT=5432`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`。PG 超管凭据不出 infra；
  redis 密码分片由 infra 运维按 `.env` 正本另行下发（见「凭据模型」）。

### nginx 配置安装：`install-nginx-conf.sh`（门禁——唯一合法通道）

安装或更新你名下的 `conf.d/<service>.conf`（以及可选地装入你名下的证书目录），一律走
门禁脚本，**不要手工放文件**：

```bash
./install-nginx-conf.sh <服务名> <conf 文件路径> [证书目录]
```

- **资产边界**：门禁只写你名下的 `nginx/conf.d/<service>.conf` 与（第三参数给定时）
  `nginx/certs/<service>/`，绝不触碰其它服务的文件。服务名 `^[a-z][a-z0-9_-]{0,30}$`
  （比建号 helper 多允 `-`，nginx 惯例名；同名服务还要建库时库名按 helper 口径另行取名）。
  证书源目录整体拷入名下目录（conf 里 `listen 443 ssl` 的证书路径写
  `/etc/nginx/certs/<service>/...`，随 conf 一并过 `nginx -t` 校验）。
- **server_name 撞车即拒**：你的 server_name 与既有**其它**服务配置冲突（通配 `*.` 按
  单层标签口径比对：单侧通配只挡恰好多一层子域的精确名，双侧通配仅后缀相同才算撞）、
  或抢占 `default_server`（IP 直访/未匹配域名兜底归 infra `nginx.conf`）——**写文件
  之前拒绝**，错误信息指明冲突名与占主文件，不留任何痕迹。更新自己名下的文件不受自己
  旧 server_name 限制。契约按单行写 server_name；多行折行写法撞车检测可能漏检（属契约
  外用法）。
- **语法门禁＋自动回滚**：写入后 `nginx -t` 通过才 `reload`；不过自动回滚刚写的文件与
  证书目录（有旧则还原、无则删除，不留半安装态），nginx 未 reload、存活不受影响，其它
  服务的路由照常。若写入前基线 `nginx -t` 已不过（他方坏配置先修，与本脚本无关），拒绝
  进场。
- **并发口径**：门禁无跨进程锁——同一共享 nginx 的安装操作（含人工改 conf.d）由各
  服务部署流程自行串行化，`-t` 与 `reload` 之间的竞态只在串行前提下闭合。
- selftest 有合法生效路由通 / 坏配置回滚且殃及面为零 / 撞车被拒 / 通配口径四组活断言
  （phase 5）。

## eu-1 迁移注记（接管式，SOP 详见 docs/deploy）

容器名由旧 `aiplatform-postgres` / `aiplatform-redis` 更名为 `semgravio-pg` /
`semgravio-redis`——平台侧 `.env.production` 的 `SPRING_DATASOURCE_URL` 与
`REDIS_HOST` 同步改（迁移窗口本来就要改 redis 密码，一次改齐）；旧 infra 的 PG 数据卷
文件级拷入 `semgravio_pgdata`（旧卷保留兜底，卷名以 SOP 文档为准）。
