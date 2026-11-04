# semgravio-eu-1 接管式迁移 SOP（基础设施栈独立化）

> 读者：执行 eu-1 停机切换的运维。目标：平台从「自建基础设施」（旧
> `deploy/infra.compose.yml`＋手工网关容器）切到共享 infra 三件套（`deploy/infra`，
> 决议正本见 ADR-0033），**数据零丢失、超管凭据延用、客户端零感知**。
> 交付物＝脚本＋SOP＋排演（#381 out of scope）：切换由运维择期执行，本 SOP 照做即可。
>
> 服务器事实：AWS 法兰克福 `ubuntu@3.70.172.160`，密钥 `~/ssh/semgravio-eu-1.pem`
> （开发机 Git Bash）；平台部署目录 `~/aiplatform`（`REMOTE_DIR`）；infra 单元目录
> `~/semgravio-infra`（`INFRA_DIR`，发布脚本默认即此）。
> 文中 `aiplatform-postgres` / `aiplatform-infra_pgdata` 等旧名均为 eu-1 现网既有
> docker 资源的事实指称（迁移对象本身），非部署物残留——「无旧项目名残留」约束的是
> `deploy/infra` 部署物（selftest phase 1 有活断言，docs/ 不在其列）。

## 0. 迁移前后形态对照

| 项 | 旧（现网） | 新（infra） |
|---|---|---|
| PG 容器 | `aiplatform-postgres`（`pgvector/pgvector:pg16` 浮动 tag） | `semgravio-pg`（pin `0.8.7-pg16`） |
| PG 数据卷 | compose 项目 `aiplatform-infra` 的 `pgdata`（实际名 `aiplatform-infra_pgdata`） | external 固定名 `semgravio_pgdata` |
| PG 超管 | `aiplatform` / 现值（`~/aiplatform/deploy/.env` 的 `POSTGRES_PASSWORD`） | **全延用，不变** |
| redis | `aiplatform-redis`，**无密码** | `semgravio-redis`，**requirepass（新设密码）** |
| 预览网关 | 手工 `docker run` 的 `aiplatform-gateway`（bind-mount 特例） | 共享 nginx conf.d 下平台自己的 `aiplatform.conf`（#386 已退役网关） |
| embedding | 旧 infra compose 内 | 平台 server compose 同栈（#386 归位），容器名延用 `aiplatform-embedding` |
| 平台连接串 | `jdbc:postgresql://aiplatform-postgres:5432/aiplatform` | `jdbc:postgresql://semgravio-pg:5432/aiplatform`（仅主机名变） |
| 网络 | webnet / previewnet（人工创建，已存在） | 同名同构，deploy.sh 幂等接管 |
| 域名 / 80/443 / 安全组 | — | **全程不动** |

## 1. 前置（窗口外先做，全是只读或备份）

- [ ] **本地排演全绿**：开发机 `deploy/infra/selftest.sh` 跑通（从零起栈＋幂等＋建号＋门禁
      全断言）。eu-1 切换只排演这一次，窗口内不再试新东西。
- [ ] **发布物就绪**：develop 已含 #382–#387（新 `deploy/` 形态）；`deploy/build-bundle.sh`
      能出包。
- [ ] **全量备份**：`deploy/server.sh dump`（pg_dump 到服务器 `~/backup-<时间>.sql.gz`，
      此时打的还是旧栈数据）。这是兜底之外的兜底——文件级拷贝全程旧卷只读，理论上
      不需要 restore，但备份必须先有。
- [ ] **记录基线数字**（窗口后比对用，都在旧栈活着时取）：
      `wsp_workspaces` 行数、库清单（`\l`）、Flyway 最大版本
      （`select max(version) from "aiplatform-server_flyway_schema_history"`）、
      PG 数据目录大小（`docker exec aiplatform-postgres du -sb /var/lib/postgresql/data`）。
- [ ] **窗口前快照（回退资产）**：`ssh … 'tar -czf ~/aiplatform-deploy-backup-pre-infra.tar.gz -C ~ aiplatform'`——
      旧 `deploy/infra.compose.yml`、旧 `.env` 全在里面；release.sh 解包只覆盖不删文件，
      旧 compose 窗口后仍在盘上，但快照是干净正本。
- [ ] **infra 目录上服务器**：开发机仓库根
      `scp -i ~/ssh/semgravio-eu-1.pem -r deploy/infra ubuntu@3.70.172.160:semgravio-infra`——
      ④ 要跑的 deploy.sh 以这份为源（release.sh 只解包到 `~/aiplatform`，不填此目录；
      重复 scp 覆盖同名文件不影响运行中的旧栈）。
- [ ] **回退留底**（⑥ 之后失败才用得上，窗口前顺手做）：
      ① 平台镜像备份 tag——`ssh … 'docker tag aiplatform-server:ecs aiplatform-server:ecs-pre-infra && docker tag aiplatform-web:ecs aiplatform-web:ecs-pre-infra'`（⑥ 重建会覆盖原 tag，回退旧代码时 tag 打回）；
      ② 旧网关启动参数——`ssh … 'docker inspect aiplatform-gateway > ~/gateway-backup.json'`（手工容器，重建命令以它为准）；
      ③ 上面的 tar 快照（⑥ 会覆盖 `~/aiplatform/deploy/`）。
- [ ] **通知停机窗口**。窗口时长 ≈ 数据拷贝（视库大小，先 `du` 估）＋ infra 起栈（分钟级）
      ＋ release.sh 重建（server/web 构建，dev 工作区镜像不受影响、不重建）。

## 2. 停机窗口步骤序

> 每步末尾有「校验点」，不过即跳 §4 回退。①–⑤ 在服务器上操作（ssh 上去）；
> ⑥ 回开发机仓库根执行。

**① 停平台**——流量归零，PG 进入静止：

```bash
ssh -i ~/ssh/semgravio-eu-1.pem ubuntu@3.70.172.160
cd ~/aiplatform
docker compose -f deploy/server.compose.yml down
docker compose -f deploy/web.compose.yml down
# embedding 必须 rm 不能只 stop：新 server compose（#386 归位）要接管同名容器，
# 旧容器占名会让 ⑥ compose up 撞名失败（2026-11-04 实操踩过）
docker rm -f aiplatform-embedding aiplatform-gateway 2>/dev/null || true
docker ps   # 校验点：只剩 aiplatform-postgres / aiplatform-redis 在跑
```

**② 文件级拷数据**——旧卷只读挂载，拷入新 external 卷：

```bash
# 先确认旧卷实名（compose 命名惯例是 aiplatform-infra_pgdata，以 inspect 为准）
OLD_VOL=$(docker inspect aiplatform-postgres --format '{{range .Mounts}}{{.Name}}{{end}}')
echo "旧卷：$OLD_VOL"     # 校验点：非空且确为 PG 数据卷

docker volume create semgravio_pgdata >/dev/null   # external 固定名；deploy.sh 也会幂等建

# 拷食用 pinned 同款镜像，不引入新拉取；--entrypoint sh 覆盖 PG 默认启动
docker stop aiplatform-postgres   # 文件级拷贝前必须停旧 PG（静止一致性）
docker run --rm \
  -v "$OLD_VOL":/from:ro -v semgravio_pgdata:/to \
  --entrypoint sh pgvector/pgvector:0.8.7-pg16 \
  -c 'cd /from && cp -a . /to'

# 校验点①（文件级）：文件数与字节总数必须完全一致
docker run --rm \
  -v "$OLD_VOL":/from:ro -v semgravio_pgdata:/to \
  --entrypoint sh pgvector/pgvector:0.8.7-pg16 -c '
    for d in /from /to; do
      echo "$d files=$(find $d -type f | wc -l) bytes=$(find $d -type f -printf "%s\n" | awk "{s+=\$1} END {print s}")"
    done'
```

> **旧卷保留不删**——它是回退路径的根基，也是窗口后观察期的对照。窗口后任何时刻
> 都不许顺手 `docker volume rm`（收尾见 §5）。

**③ 手写 infra 凭据正本**——`~/semgravio-infra/.env` 只许手写现值，不许用自动生成的：

```bash
cd ~/semgravio-infra   # §1 已 scp 上服务器（deploy.sh/compose.yml/nginx/ 全量在此）
grep POSTGRES_PASSWORD ~/aiplatform/deploy/.env   # 取旧现值（超管密码，延用）
umask 077
cat > .env <<'EOF'
POSTGRES_USER=aiplatform
POSTGRES_PASSWORD=<上一步取到的现值，原样延用>
REDIS_PASSWORD=<openssl rand -hex 16 新设；此行同时填进平台 .env.production>
EOF
```

**④ infra 起栈**——deploy.sh 幂等：网络已在→跳过、卷已在→跳过、起栈＋等 healthy：

```bash
./deploy.sh
# 校验点②（库级）：既有库全部在、基线数字对得上（凭据从正本 .env 现场载入）
set -a; . ./.env; set +a
docker exec -e PGPASSWORD=$POSTGRES_PASSWORD semgravio-pg \
  psql -U aiplatform -d postgres -c '\l'
docker exec -e PGPASSWORD=$POSTGRES_PASSWORD semgravio-pg \
  psql -U aiplatform -d aiplatform -tAc \
  "select count(*) from wsp_workspaces; select max(version) from \"aiplatform-server_flyway_schema_history\""
# 期望：库清单＝§1 基线（含 aiplatform），两张表数字＝§1 基线
```

**⑤ 平台 env 三行改指新名**——release.sh 解包不碰 `.env*`（打包即排除），手改持久生效：

```bash
cd ~/aiplatform
sed -i \
  -e 's|aiplatform-postgres|semgravio-pg|' \
  -e 's|aiplatform-redis|semgravio-redis|' \
  aiplatform-server/.env.production
# REDIS_PASSWORD 行填 §③ 新设值（此前无此行——旧 redis 无密码；新增即新代码 #383 的要求）
grep -E 'DATASOURCE_URL|REDIS_' aiplatform-server/.env.production   # 校验点：三个新值在场
```

> 注：平台库账号 `aiplatform` 即该 PG 实例超管——历史形态，凭据延用是 #381 的明确决策
> （dbx 客户端零感知的前提）。接入契约的「分片凭据」面向后续接入服务；平台自身降权
> （经 `ensure-db-user.sh` 建低权账号、改 `SPRING_DATASOURCE_USERNAME/PASSWORD`）不在
> 本批范围，另票。

**⑥ 平台按新契约重起**——一条 release.sh 收掉剩余全部（等价手动流程见部署文档 §6/§7）：

```bash
# 开发机仓库根：
deploy/release.sh
# 它依次：infra 前置健康检查（semgravio-{pg,redis,nginx}）→ pg_dump 备份（对新卷）→
# 解包（不动 .env*）→ 幂等移除旧 aiplatform-gateway 容器 → 重建重启 server＋embedding →
# web → 平台 conf＋资产经门禁装入共享 nginx → Host 头冒烟（期望 200/401）
```

**⑦ 窗口内冒烟**——§3 清单全过才算窗口关闭。

## 3. 窗口内冒烟清单

- [ ] `deploy/server.sh smoke`：主站 200 / API 401 信封正确。
- [ ] 死子域提示页：`curl -s -i -H "Host: 9876543210.preview.<域名>" http://localhost/ | grep -E 'HTTP/|系统暂不可用'`（经共享 nginx，路由已由门禁装上）。
- [ ] 后端日志：`docker logs aiplatform-server` 见 `Started AiPlatformApplication`、**无**
      redis 连接失败（requirepass 配对成功的反面证明）。
- [ ] redis 门禁：`docker exec semgravio-redis redis-cli -a <新密码> --no-auth-warning ping`
      ＝PONG；无密码连接＝NOAUTH。
- [ ] 真浏览器走一遍部署文档 §8 全清单（SSE、预览子域、快照、验证码登录）——curl 抓不到
      SSE 类问题。
- [ ] **dbx 客户端零感知验证**：SSH 隧道照 `docs/deploy/prod-db-access.md` 原样重连——
      超管名/密码/回环端口全延用，连上即「客户端零感知」成立。

## 4. 回退路径

任一校验点不过，按此回退。**代价声明**：旧卷全程只读未动，平台存量数据零损失；
放弃的仅是窗口内新卷上的新增写入（⑥ 后失败才可能存在——后端启动触发的 Flyway
增量、冒烟产生的访客数据），重试窗口重新产生或从 §1 的 pg_dump 恢复。

```bash
# 前置判断：失败发生在 ⑥ 之前还是之后
#  · ⑥ 之前：~/aiplatform 的 deploy/ 还是旧形态，直接从 ① 开始
#  · ⑥ 之后（release.sh 已解包覆盖 deploy/、已删旧网关）：先用 §1 tar 快照恢复旧部署目录
tar -xzf ~/aiplatform-deploy-backup-pre-infra.tar.gz -C ~

# ① 停新栈（external 卷 down 不删；新旧两栈数据互不可见，无交叉污染）
cd ~/semgravio-infra && docker compose down
cd ~/aiplatform
docker compose -f deploy/server.compose.yml down
docker compose -f deploy/web.compose.yml down

# ② 平台 env 三行改回旧值（SPRING_DATASOURCE_URL/REDIS_HOST 指回 aiplatform-postgres/-redis）
sed -i \
  -e 's|semgravio-pg|aiplatform-postgres|' \
  -e 's|semgravio-redis|aiplatform-redis|' \
  aiplatform-server/.env.production
# 回退旧代码（仅当 ⑥ 已重建镜像）：docker tag aiplatform-server:ecs-pre-infra aiplatform-server:ecs
# （web 同法）——之后的 compose up 不带 --build，沿用打回的旧镜像

# ③ 旧基础设施只起 PG/redis 两个服务：embedding 已由新 server compose 管辖且容器
#    在跑，旧 infra compose 不再 up 它（避免同名互抢）；redis 起来后单独换带密码实例
docker compose -f deploy/infra.compose.yml up -d postgres redis
docker rm -f aiplatform-redis   # 换 requirepass 实例：纯缓存、重启即空，无数据代价
docker run -d --name aiplatform-redis --restart unless-stopped \
  --network webnet redis:7-alpine \
  redis-server --requirepass <REDIS_PASSWORD> --save "" --appendonly no
# ⚠ 此后手工 redis 实例与旧 infra compose 的 redis 服务同名——回退期勿再对该 compose
#   整项目 up/down（会按无密码声明重建它）；旧形态随重试窗口成功即整体淘汰

# ④ 预览网关：⑥ 之前失败则容器还在（docker start aiplatform-gateway 若曾被 stop）；
#    ⑥ 之后失败已被 release.sh 移除——按 §1 留底的 ~/gateway-backup.json 手工重建
#    （旧形态网关＝compose 之外的手工 docker run，bind-mount inode 坑随它回来，回退期忍之）

# ⑤ 旧形态冒烟（裸 curl 三项，不经 server.sh smoke——它的口径是共享 nginx 新形态）：
curl -s -o /dev/null -w "%{http_code}\n" -H "Host: <域名>" http://localhost/            # 主站 200（旧网关 default_server 应答）
curl -s -o /dev/null -w "%{http_code}\n" -H "Host: <域名>" http://localhost/api/projects # 401
curl -s -i -H "Host: 9876543210.preview.<域名>" http://localhost/ | grep -E 'HTTP/|系统暂不可用'
```

回退后对外形态原样（域名/端口/安全组从未动过）。排障定性：新栈 conf/网络问题查
`docker logs semgravio-nginx`＋`docker compose ps`；数据疑问直接对旧卷再跑一遍
§2② 的文件级比对。修复后择新窗口重跑 §2，§1 前置减半（备份/快照/留底已在）。

## 5. 窗口后收尾

- [ ] **观察期 ≥ 7 天**：期间旧卷 `aiplatform-infra_pgdata` 与新栈并存对照；观察期满且
      一切正常，人工 `docker volume rm aiplatform-infra_pgdata` 回收空间（**不许自动化
      删**，这是唯一的手动删除点）。
- [ ] 确认 `aiplatform-gateway` 容器已不在（release.sh 幂等移除；`docker ps -a` 无此名）。
- [ ] `docker images | grep pgvector`：旧浮动 tag `pgvector/pgvector:pg16` 镜像可清理
      （新栈 pin 0.8.7-pg16）。
- [ ] 开发机/运维笔记归档：窗口实际时长、拷贝字节数、任何与本文档的漂移（漂移回写本文档）。

## 6. 执行记录（2026-11-04，首次按本 SOP 切换）

- **窗口**：①–⑤ 约 10 分钟（PG 数据仅 69MB，文件级拷贝秒级）＋ ⑥ release.sh
  约 8 分钟（镜像层缓存命中大半）；数据拷贝 1478 文件 / 72,573,649 字节，源目逐字节一致。
- **基线→新栈比对全中**：4 库 / wsp_workspaces 11 行 / Flyway max('9'，字符串序) 全同。
- **漂移回写**：① 的 embedding 由 stop 改 rm -f（见 ① 注记）；其余步骤与本文档一致。
- **上线 revision**：e047ea6（含 build-bundle 资产装配路径修复——该缺陷致 #382 改版后
  打包必败，已修）。
- **遗留观察项**：旧 redis（aiplatform-redis，无密码）观察期内仍在跑（webnet 内部可达、
  无宿主端口映射，平台已不连接它）；与旧 PG 卷一并观察期满回收。
