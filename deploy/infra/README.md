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
| **pgsql**（`semgravio-pg`） | pgvector/pgvector **0.8.7**-pg16（pin 精确版本，与 eu-1 现网一致）；超管 `aiplatform` 延用现值；数据卷 **external 固定名 `semgravio_pgdata`**（`down` 不删、与项目名解耦）；绑宿主**回环 5432**（SSH 隧道直连本地 dbx 客户端，用法见 `docs/deploy/prod-db-access.md`，安全组不放行）。**不预建服务库**——各服务接入时经建号 helper 自建（#384），既有库原样延用。 |
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
- **建库建号**：用 infra 的幂等建号 helper（#384，随本目录走）——每服务独立 database
  ＋低权限角色，只对自己的库有权限；超管凭据不出 infra。
- **nginx 配置**：恰好两个资产——`nginx/conf.d/<service>.conf` 单文件＋
  `nginx/certs/<service>/` 证书目录。**必须经门禁脚本安装**（#385：`nginx -t` 通过才
  reload、失败自动回滚你的文件、`server_name` 冲突即拒）——共享 nginx 是最危险的共享
  故障点，别让一个服务的坏配置弄挂所有人。
- 证书：infra 只定目录约定；申请/续期人工 certbot。

## eu-1 迁移注记（接管式，SOP 详见 docs/deploy）

容器名由旧 `aiplatform-postgres` / `aiplatform-redis` 更名为 `semgravio-pg` /
`semgravio-redis`——平台侧 `.env.production` 的 `SPRING_DATASOURCE_URL` 与
`REDIS_HOST` 同步改（迁移窗口本来就要改 redis 密码，一次改齐）；旧 infra 的 PG 数据卷
文件级拷入 `semgravio_pgdata`（旧卷保留兜底，卷名以 SOP 文档为准）。
