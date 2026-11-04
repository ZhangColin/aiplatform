# semgravio-eu-1 接管式迁移 SOP（基础设施栈独立化）

> 读者：执行 eu-1 停机切换的运维。目标：平台从「自建基础设施」（旧
> `deploy/infra.compose.yml`＋手工网关容器）切到共享 infra 三件套（`deploy/infra`，
> 决议正本见 ADR-0033），**数据零丢失、超管凭据延用、客户端零感知**。
> 交付物＝脚本＋SOP＋排演（#381 out of scope）：切换由运维择期执行，本 SOP 照做即可。
>
> 服务器事实：AWS 法兰克福 `ubuntu@3.70.172.160`，密钥 `~/ssh/semgravio-eu-1.pem`
> （开发机 Git Bash）；平台部署目录 `~/aiplatform`（`REMOTE_DIR`）；infra 单元目录
> `~/semgravio-infra`（`INFRA_DIR`，发布脚本默认即此）。

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
- [ ] **通知停机窗口**。窗口时长 ≈ 数据拷贝（视库大小，先 `du` 估）＋ infra 起栈（分钟级）
      ＋ release.sh 重建（server/web 构建，dev 工作区镜像不受影响、不重建）。

## 2. 停机窗口步骤序

> 每步末尾有「校验点」，不过即跳 §4 回退。全程在服务器上操作（ssh 上去）。

**① 停平台**——流量归零，PG 进入静止：

```bash
ssh -i ~/ssh/semgravio-eu-1.pem ubuntu@3.70.172.160
cd ~/aiplatform
docker compose -f deploy/server.compose.yml down
docker compose -f deploy/web.compose.yml down
docker stop aiplatform-embedding aiplatform-gateway 2>/dev/null || true
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
mkdir -p ~/semgravio-infra   # 随新部署包来的 infra 目录；若 release.sh 尚未跑过，
                             # 从部署包/仓库 deploy/infra 整目录拷入（INFRA_DIR 默认即此）
cd ~/semgravio-infra
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

任一校验点不过，按此回退；**数据零风险**——新 infra 全程只用新卷，旧卷只读未动。

```bash
# ① 停新栈（external 卷 down 不删；新栈数据与旧栈互不可见，无交叉污染）
cd ~/semgravio-infra && docker compose down
cd ~/aiplatform
docker compose -f deploy/server.compose.yml down
docker compose -f deploy/web.compose.yml down

# ② 平台 env 三行改回旧值（SPRING_DATASOURCE_URL/REDIS_HOST 指回 aiplatform-postgres/-redis）
sed -i \
  -e 's|semgravio-pg|aiplatform-postgres|' \
  -e 's|semgravio-redis|aiplatform-redis|' \
  aiplatform-server/.env.production

# ③ 旧 redis 补密码：新后端镜像（#383）对 REDIS_HOST 强制要密码；旧 redis 是纯缓存、
#    重启即空，加 requirepass 无数据代价——这也正好预演了新形态
docker rm -f aiplatform-redis
docker run -d --name aiplatform-redis --restart unless-stopped \
  --network webnet redis:7-alpine redis-server --requirepass <REDIS_PASSWORD> --save "" --appendonly no

# ④ 旧栈起回（compose 文件仍在盘上；窗口前 tar 快照是干净正本）
docker compose -f deploy/infra.compose.yml up -d
docker compose -f deploy/server.compose.yml up -d
docker compose -f deploy/web.compose.yml up -d

# ⑤ 旧形态冒烟：server.sh smoke（容器名自适应，新旧名都认）＋浏览器抽查
```

回退后原状恢复（域名/端口/安全组从未动过）。排障定性：新栈 conf/网络问题查
`docker logs semgravio-nginx`＋`docker compose ps`；数据疑问直接对旧卷再跑一遍
§2② 的文件级比对。修复后择新窗口重跑 §2，§1 前置减半（备份与快照已在）。

## 5. 窗口后收尾

- [ ] **观察期 ≥ 7 天**：期间旧卷 `aiplatform-infra_pgdata` 与新栈并存对照；观察期满且
      一切正常，人工 `docker volume rm aiplatform-infra_pgdata` 回收空间（**不许自动化
      删**，这是唯一的手动删除点）。
- [ ] 确认 `aiplatform-gateway` 容器已不在（release.sh 幂等移除；`docker ps -a` 无此名）。
- [ ] `docker images | grep pgvector`：旧浮动 tag `pgvector/pgvector:pg16` 镜像可清理
      （新栈 pin 0.8.7-pg16）。
- [ ] 开发机/运维笔记归档：窗口实际时长、拷贝字节数、任何与本文档的漂移（漂移回写本文档）。
