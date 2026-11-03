#!/usr/bin/env bash
# infra 自测：本地 Docker 从零排练整套起栈契约（兼作 eu-1 停机迁移前的排演工具）。
# 验收断言（对应 issue #382 AC，只断言外部行为）：
#   ① 干净环境一条命令起栈：三容器健康、webnet/previewnet 两网存在
#   ② 部署脚本重跑幂等：容器不重建（ID 不变）、卷不动（CreatedAt 不变）
#   ③ 回环连通：PG 超管 127.0.0.1:5432 可连且是超管；redis 127.0.0.1:6379 带密码 PONG、
#      无密码被拒（requirepass 生效）；nginx :80/healthz 200
#   ④ 形态：镜像 pin 精确版本（运行实例==compose 声明、tag 带版本号）、compose 项目名
#      semgravio-infra、redis 无持久卷、nginx 双挂两网绑 80/443、部署物无旧项目名残留、
#      无平台专属服务
# 用法：deploy/infra/selftest.sh（要求 docker 可用、80/443/5432/6379 空闲、semgravio-{pg,redis,nginx}
# 三个容器名无占位——本地 dev 栈占端口时先停 aiplatform-pg / semgravio-dev-redis /
# aiplatform-gateway；名字被占的容器先 docker rename 让位）。
# 失败时现场保留供排查；成功则清场（down＋删排练卷＋删排练 .env）。
set -euo pipefail
cd "$(dirname "$0")"

CONTAINERS=()
VOL=semgravio_pgdata
PROJECT=semgravio-infra

ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }
phase(){ printf '\n== %s ==\n' "$*"; }

trap 'printf "\n✗ selftest 失败，现场已保留（%s 行）。\n" "${LINENO:-?}"
      printf "排查：docker compose ps ／ docker logs %s 等\n清理：docker compose down --remove-orphans && docker volume rm %s && rm -f .env\n" "${CONTAINERS[*]}" "$VOL"' ERR

# ---------------------------------------------------------------- 0. 前置
phase "0/4 前置检查"
docker info >/dev/null 2>&1 || die "docker 不可用"
# 容器名从 compose 派生（与 deploy.sh 静态清单同源、单一事实在 compose.yml）；
# 凭据给占位值只为绕过 compose 的 :? 必填校验——服务名单不依赖凭据内容
mapfile -t CONTAINERS < <(POSTGRES_PASSWORD=x REDIS_PASSWORD=y docker compose config --services | sort)
[ "${#CONTAINERS[@]}" -eq 3 ] || die "compose 服务数异常：${#CONTAINERS[@]}（应恰为三件套）"
for p in 80 443 5432 6379; do
  if (exec 3<>/dev/tcp/127.0.0.1/"$p") 2>/dev/null; then
    exec 3>&- 3<&-
    printf '✗ 端口 %s 已被占用。当前占用者：\n' "$p" >&2
    docker ps --format '{{.Names}}\t{{.Ports}}' | grep -E ":${p}->" >&2 || true
    die "先停占用容器（本地 dev 栈：aiplatform-pg / semgravio-dev-redis / aiplatform-gateway）再跑 selftest"
  fi
done
ok "80/443/5432/6379 空闲"

# 容器名占位预检：占着名字的容器若不是本单元的，明确报错并给出 rename 方案
# （教训：停着的容器占名，端口检查发现不了，compose up 才会撞上）
for c in "${CONTAINERS[@]}"; do
  if docker inspect "$c" >/dev/null 2>&1; then
    owner=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$c" || true)
    [ "$owner" = "$PROJECT" ] && continue
    die "容器名 $c 已被占用（占主 compose 项目：${owner:-无 label 的孤儿容器}）。
    infra 拥有 semgravio-{pg,redis,nginx} 名：先 docker rename $c <别的名> 让位再跑 selftest"
  fi
done
ok "容器名 semgravio-{pg,redis,nginx} 无占位"

# 正式凭据正本保护：.env 存在但非 selftest 所生 → 这是真机器，拒绝清场排练
if [ -f .env ] && ! grep -q 'managed-by: selftest' .env; then
  die ".env 是本机正式凭据正本（非 selftest 生成）。selftest 会清场重建、有毁数据风险，拒绝执行"
fi

# ---------------------------------------------------------------- 1. 从零起栈
phase "1/4 从零起栈（清残留 → 一键部署）"
docker compose down --remove-orphans >/dev/null 2>&1 || true
docker volume rm "$VOL" >/dev/null 2>&1 || true
rm -f .env

bash ./deploy.sh

for c in "${CONTAINERS[@]}"; do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$c")" = healthy ] || die "$c 未达 healthy"
done
ok "三容器 healthy：${CONTAINERS[*]}"

[ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' semgravio-pg)" = "$PROJECT" ] \
  || die "compose 项目名不是 $PROJECT"
ok "compose 项目名 $PROJECT"

for net in webnet previewnet; do
  docker network inspect "$net" >/dev/null 2>&1 || die "网络 $net 不存在"
done
ok "webnet＋previewnet 均在"

local_services=$(docker compose config --services | sort | tr '\n' ' ' | xargs)
[ "$local_services" = "semgravio-nginx semgravio-pg semgravio-redis" ] \
  || die "服务集应为三件套，实际：$local_services（不得混入平台专属服务）"
ok "仅三件套，无平台专属服务"

# pin 断言两式：①运行实例镜像==compose 声明（防止漂移）；②每个 tag 带版本号（拒绝
# :pg16 / :7-alpine / :stable-alpine / :latest 浮动 tag）。精确版本号唯一事实在 compose.yml。
live_imgs=$(for c in "${CONTAINERS[@]}"; do docker inspect -f '{{.Config.Image}}' "$c"; done | sort | tr '\n' ' ' | xargs)
cfg_imgs=$(POSTGRES_PASSWORD=x REDIS_PASSWORD=y docker compose config --images | sort | tr '\n' ' ' | xargs)
[ "$live_imgs" = "$cfg_imgs" ] || die "运行实例镜像与 compose 声明不一致：live=$live_imgs cfg=$cfg_imgs"
for img in $live_imgs; do
  [[ "$img" =~ :[0-9] ]] || die "镜像未 pin 精确版本（浮动 tag）：$img"
done
ok "镜像 pin 精确版本且运行实例==compose 声明：$live_imgs"

docker port semgravio-pg 5432     | grep -q '^127.0.0.1:5432$' || die "PG 未绑宿主回环 5432"
docker port semgravio-redis 6379  | grep -q '^127.0.0.1:6379$' || die "redis 未绑宿主回环 6379"
docker port semgravio-nginx 80    >/dev/null                   || die "nginx 未绑宿主 80"
docker port semgravio-nginx 443   >/dev/null                   || die "nginx 未绑宿主 443"
ok "端口形态：PG/redis 回环，nginx 80/443"

nginx_nets=$(docker inspect -f '{{range $k,$_ := .NetworkSettings.Networks}}{{$k}} {{end}}' semgravio-nginx)
grep -qw webnet     <<<"$nginx_nets" || die "nginx 未挂 webnet"
grep -qw previewnet <<<"$nginx_nets" || die "nginx 未挂 previewnet"
ok "nginx 双挂 webnet＋previewnet"

# 「无持久卷、仅缓存」的声明层断言：compose 不声明 redis 持久卷（只有 pgdata）。
# 运行时持久化全关的行为断言见 phase 3（redis 镜像自带 VOLUME /data 声明，匿名卷
# 是 docker 行为且不会写入任何数据，行为才是真契约）。
declared_vols=$(docker compose config --volumes | tr '\n' ' ' | xargs)
[ "$declared_vols" = "pgdata" ] || die "compose 声明的卷应只有 pgdata，实际：$declared_vols"
ok "compose 层仅声明 PG 数据卷（redis 无持久卷）"

# 旧 infra compose 的项目名不得出现在任何部署物里（模式运行时拼接——源码不留字面量；
# selftest.sh 自身按约定豁免）
old_pn='aiplatform'"'"'-infra'
if grep -rq --exclude=selftest.sh "$old_pn" . 2>/dev/null; then die "部署物残留旧 infra 项目名"; fi
ok "部署物无旧项目名残留"

[ -f .env ] || die "deploy.sh 未生成 .env"
printf '# managed-by: selftest\n' >> .env   # 标记排练凭据，供重复执行与清场保护

# ---------------------------------------------------------------- 2. 幂等重跑
phase "2/4 幂等重跑（无容器重建、状态不变）"
ids_before=$(for c in "${CONTAINERS[@]}"; do docker inspect -f '{{.Id}}' "$c"; done)
vol_created_before=$(docker volume inspect -f '{{.CreatedAt}}' "$VOL")

bash ./deploy.sh

ids_after=$(for c in "${CONTAINERS[@]}"; do docker inspect -f '{{.Id}}' "$c"; done)
[ "$ids_before" = "$ids_after" ] || die "重跑后容器被重建（ID 变化）"
ok "三容器 ID 不变（未重建）"

[ "$(docker volume inspect -f '{{.CreatedAt}}' "$VOL")" = "$vol_created_before" ] || die "数据卷被动过"
ok "数据卷未动"

for c in "${CONTAINERS[@]}"; do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$c")" = healthy ] || die "重跑后 $c 不再 healthy"
done
ok "重跑后全部仍 healthy"

# ---------------------------------------------------------------- 3. 回环连通
phase "3/4 回环端口＋密码连通（宿主网络视角，等价 SSH 隧道落点）"
set -a; . ./.env; set +a
: "${POSTGRES_USER:?}" "${POSTGRES_PASSWORD:?}" "${REDIS_PASSWORD:?}"

# 探针客户端直接用被测容器同款镜像（pin 一致性已由 phase 1 兜住，这里不再写字面量）
img_pg=$(docker inspect -f '{{.Config.Image}}' semgravio-pg)
img_redis=$(docker inspect -f '{{.Config.Image}}' semgravio-redis)
img_nginx=$(docker inspect -f '{{.Config.Image}}' semgravio-nginx)

# PG：超管经回环 5432 可连、可访问所有库（rolsuper=t）
pg_out=$(docker run --rm --network host -e PGPASSWORD="$POSTGRES_PASSWORD" \
  "$img_pg" \
  psql -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d postgres -tAc \
  "SELECT rolsuper FROM pg_roles WHERE rolname='$POSTGRES_USER'")
[ "$pg_out" = t ] || die "PG 超管经回环 5432 连接失败或非超管（输出：$pg_out）"
dbs=$(docker run --rm --network host -e PGPASSWORD="$POSTGRES_PASSWORD" \
  "$img_pg" \
  psql -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d postgres -tAc 'SELECT count(*) FROM pg_database')
[ "$dbs" -ge 2 ] || die "超管应能看到全部库（pg_database 行数异常：$dbs）"
ok "PG 超管回环 5432 可连、全库可见"

# redis：带密码 PONG；无密码必须被拒（requirepass 生效的证明）
pong=$(docker run --rm --network host "$img_redis" \
  redis-cli -h 127.0.0.1 -p 6379 -a "$REDIS_PASSWORD" --no-auth-warning ping 2>/dev/null || true)
[ "$pong" = PONG ] || die "redis 带密码回环 6379 未 PONG（输出：$pong）"
noauth=$(docker run --rm --network host "$img_redis" \
  redis-cli -h 127.0.0.1 -p 6379 --no-auth-warning ping 2>&1 || true)
grep -q 'NOAUTH' <<<"$noauth" || die "redis 无密码竟未被拒（requirepass 未生效：$noauth）"
ok "redis 回环 6379 带密码 PONG、无密码 NOAUTH"

# 「无持久卷、仅缓存」的行为层断言：持久化全关（save 空串＋appendonly no），
# 重启/重建即空——这才是纯缓存语义的证明
save_val=$(docker run --rm --network host "$img_redis" \
  redis-cli -h 127.0.0.1 -p 6379 -a "$REDIS_PASSWORD" --no-auth-warning \
  CONFIG GET save 2>/dev/null | tail -1)
ao_val=$(docker run --rm --network host "$img_redis" \
  redis-cli -h 127.0.0.1 -p 6379 -a "$REDIS_PASSWORD" --no-auth-warning \
  CONFIG GET appendonly 2>/dev/null | tail -1)
{ [ -z "$save_val" ] && [ "$ao_val" = "no" ]; } \
  || die "redis 持久化未全关（save='${save_val}' appendonly='${ao_val}'）"
ok "redis 持久化全关（纯缓存语义）"

# nginx：80/healthz 200 且回 infra 应答体
healthz_body=$(docker run --rm --network host "$img_nginx" \
  wget -q -O - http://127.0.0.1:80/healthz 2>/dev/null || true)
[ "$healthz_body" = "semgravio-infra" ] || die "nginx /healthz 应答异常（输出：$healthz_body）"
ok "nginx 回环 80 /healthz 应答 semgravio-infra"

# ---------------------------------------------------------------- 4. 清场
phase "4/4 清场（成功收尾）"
docker compose down --remove-orphans >/dev/null
docker volume rm "$VOL" >/dev/null
rm -f .env
ok "已 down 栈、删排练卷 $VOL、删排练 .env（网络 webnet/previewnet 保留——幂等资源）"

printf '\nselftest 全部通过 ✅（起栈健康＋幂等重跑＋回环连通＋形态断言）\n'
