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
#   ⑤ 建号 helper（#384）：同一服务两遍执行幂等（角色/库清单不变、输出一致、凭据可用）；
#      新用户对自身库可读写、对他服务库连接被拒；helper 输出不泄漏超管凭据
#   ⑥ nginx 门禁（#385）：合法 conf 安装后 reload 生效、路由通；语法坏 conf 被回滚且
#      nginx 仍活、既有其它服务配置不受影响；server_name 撞车被拒（写文件前）
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
phase "0/6 前置检查"
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
phase "1/6 从零起栈（清残留 → 一键部署）"
docker compose down --remove-orphans >/dev/null 2>&1 || true
docker volume rm "$VOL" >/dev/null 2>&1 || true
rm -f .env
# 排练凭据正本 selftest 自己生成（先于 deploy.sh、自带 managed-by 标记）：deploy.sh 只
# 在 .env 缺失时生成——先生成则它跳过。好处：phase 1 中途失败（如 healthy 超时）留下的
# .env 也带标记，失败现场重跑不会被「正式凭据保护」误拒
umask 077
{
  echo '# managed-by: selftest（selftest.sh 生成的排练凭据，非本机正式正本）'
  echo 'POSTGRES_USER=aiplatform'
  echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)"
  echo "REDIS_PASSWORD=$(openssl rand -hex 16)"
} > .env
# 上次失败现场会保留自己名下已安装的 conf.d/selftest-*.conf——不清的话，起栈时 nginx
# 加载到上游不存在（dummy 后端未起）的排练配置直接起不来（主锁 conf.d 是起栈即加载）；
# certs 下的排练目录一并清
rm -f nginx/conf.d/selftest-*.conf
rm -rf nginx/certs/selftest-web

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

[ -f .env ] || die "deploy.sh 把排练 .env 弄丢了？"

# ---------------------------------------------------------------- 2. 幂等重跑
phase "2/6 幂等重跑（无容器重建、状态不变）"
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
phase "3/6 回环端口＋密码连通（宿主网络视角，等价 SSH 隧道落点）"
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

# ---------------------------------------------------------------- 4. 建号 helper
phase "4/6 建号 helper（两遍幂等＋低权限边界，#384 AC）"
# 排练服务名：走 helper 全链路（建角色/建库/授权/分片输出），留痕只落在排练数据卷里，
# 清场（phase 5）一并回收；探针一律以接入方视角走 webnet 内 DNS，不经宿主端口。
svc_a=selftesta
svc_b=selftestb
pw_a=$(openssl rand -hex 16)
pw_b=$(openssl rand -hex 16)
pg_admin() { # 超管面探针（断言对象清单/形态用）
  docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD" semgravio-pg \
    psql -U "$POSTGRES_USER" -d postgres -tAc "$1"
}
pg_as() { # 服务面探针：以指定服务用户走 webnet DNS 连指定库（SQL 走 stdin——
  # psql 变量/多语句只在 stdin 路径行为可控、-q 压住命令标签；-i 必带：无它容器
  # stdin 是 EOF，psql 空跑 exit 0 什么都不执行，探针会假绿/假空）
  local user=$1 pw=$2 db=$3 sql=$4
  docker run --rm -i --network webnet -e PGPASSWORD="$pw" "$img_pg" \
    psql -q -h semgravio-pg -p 5432 -U "$user" -d "$db" -tA 2>&1 <<<"$sql"
}

# 第一遍：输出恰为服务自身凭据分片，且不见超管凭据
out=$(DB_PASSWORD=$pw_a bash ./ensure-db-user.sh "$svc_a") \
  || die "第一遍 ensure-db-user 执行失败"
grep -qx "DB_HOST=semgravio-pg" <<<"$out" || die "分片缺 DB_HOST：$out"
grep -qx "DB_PORT=5432"         <<<"$out" || die "分片缺 DB_PORT：$out"
grep -qx "DB_NAME=$svc_a"       <<<"$out" || die "分片缺 DB_NAME：$out"
grep -qx "DB_USER=$svc_a"       <<<"$out" || die "分片缺 DB_USER：$out"
grep -qx "DB_PASSWORD=$pw_a"    <<<"$out" || die "分片缺 DB_PASSWORD：$out"
grep -q "$POSTGRES_PASSWORD"    <<<"$out" && die "helper 输出泄漏超管密码！"
ok "第一遍：输出为服务自身凭据分片，无超管凭据"

# 角色形态：可登录、无超管/建库/建角色权；库属主＝服务用户
shape=$(pg_admin "SELECT (NOT rolsuper) AND (NOT rolcreatedb) AND (NOT rolcreaterole) AND rolcanlogin
                   FROM pg_roles WHERE rolname='$svc_a'")
[ "$shape" = t ] || die "角色形态异常（应 LOGIN 且 NOSUPERUSER/NOCREATEDB/NOCREATEROLE）：$shape"
owner=$(pg_admin "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname='$svc_a'")
[ "$owner" = "$svc_a" ] || die "库属主不是服务用户：$owner"
ok "角色低权限形态正确、库属主=服务用户"

# 服务自身凭据可用：自己的库里建表＋写读（stdin 路径多语句一次走完，-q 压住命令标签）
cnt=$(pg_as "$svc_a" "$pw_a" "$svc_a" \
  'CREATE TABLE probe(x int); INSERT INTO probe VALUES (42); SELECT x FROM probe;')
[ "$cnt" = 42 ] || die "服务用户对自己库读写失败（输出：$cnt）"
ok "服务用户对自身库可读写"

# 第二遍：幂等——对象清单不变、输出一致、凭据仍可用
roles_before=$(pg_admin 'SELECT rolname FROM pg_roles ORDER BY 1')
dbs_before=$(pg_admin 'SELECT datname FROM pg_database ORDER BY 1')
out2=$(DB_PASSWORD=$pw_a bash ./ensure-db-user.sh "$svc_a") \
  || die "第二遍 ensure-db-user 执行失败"
[ "$out2" = "$out" ] || die "第二遍输出与第一遍不一致"
{ [ "$(pg_admin 'SELECT rolname FROM pg_roles ORDER BY 1')" = "$roles_before" ] \
    && [ "$(pg_admin 'SELECT datname FROM pg_database ORDER BY 1')" = "$dbs_before" ]; } \
  || die "第二遍后角色/库清单变化（有重复创建？）"
still=$(pg_as "$svc_a" "$pw_a" "$svc_a" 'SELECT 42')
[ "$still" = 42 ] || die "第二遍后原凭据不可用（输出：$still）"
ok "第二遍幂等：无重复创建、输出一致、凭据仍可用"

# 低权限边界：第二个服务建号后，svc_a 连 svc_b 的库必须被拒
# （|| true：连接被拒是预期路径，psql 非零退出不该触发 set -e；断言落在输出信息上）
DB_PASSWORD=$pw_b bash ./ensure-db-user.sh "$svc_b" >/dev/null \
  || die "为 $svc_b 建号失败"
deny=$(pg_as "$svc_a" "$pw_a" "$svc_b" 'SELECT 1' || true)
grep -q 'permission denied for database' <<<"$deny" \
  || die "svc_a 竟能连 $svc_b 的库（输出：$deny）"
ok "低权限边界：svc_a 连 $svc_b 的库被拒（permission denied for database）"

# 维护库同样不可达：低权限角色可连接面＝自己名下的库（代码评审收紧项）
mpg=$(pg_as "$svc_a" "$pw_a" postgres 'SELECT 1' || true)
grep -q 'permission denied for database' <<<"$mpg" \
  || die "svc_a 竟能连 postgres 维护库（输出：$mpg）"
ok "低权限边界：svc_a 连 postgres 维护库同样被拒"

# ---------------------------------------------------------------- 5. nginx 门禁
phase "5/6 nginx 门禁（合法生效 / 坏配置回滚 / 撞车即拒，#385 AC）"
# 接入方视角走门禁全链路：dummy 后端挂 webnet，conf 一律经 install-nginx-conf.sh 安装。
# 断言只盯外部行为：门禁裁决（退出码/回滚/拒绝）、路由是否通、nginx 是否仍活——
# 不断言脚本内部结构。留痕（conf 文件/后端容器）在清场 phase 一并回收。
gate_tmp=$(mktemp -d)
img=$img_nginx   # phase 3 已取被测容器同款镜像（pin 一致性已由 phase 1 兜住）
http_probe() {   # $1=Host 头；经宿主回环打 nginx，输出应答体
  docker run --rm --network host "$img" \
    wget -q -O - --header="Host: $1" http://127.0.0.1/ 2>/dev/null || true
}

# 自己的 dummy 后端：webnet 内 DNS 名 selftest-web，恒定应答体
# （先强清同名残留：上次失败现场保留的话，占名容器会让 docker run 直接撞名）
docker rm -f selftest-web >/dev/null 2>&1 || true
cat >"$gate_tmp/backend.conf" <<'EOF'
server {
    listen 80;
    location / { return 200 "selftest-web-ok\n"; }
}
EOF
# Git Bash 双坑分治：默认 MSYS 会把 -v 里容器内路径（/etc/...）改写成 Windows 路径；
# 整串禁用改写（MSYS_NO_PATHCONV=1）后源路径又必须 Windows 形——源显式 cygpath -w，
# 容器内路径禁用改写（export 只影响本脚本内的 docker 调用，无副作用）。
# （Linux 无 cygpath，两变量原样走 POSIX 路径）
backend_conf=$gate_tmp/backend.conf
if command -v cygpath >/dev/null 2>&1; then
  backend_conf=$(cygpath -w "$backend_conf")
  export MSYS_NO_PATHCONV=1
fi
docker run -d --name selftest-web --network webnet \
  -v "$backend_conf":/etc/nginx/conf.d/default.conf:ro \
  "$img" >/dev/null
for i in $(seq 1 20); do
  docker exec selftest-web wget -q -O /dev/null http://127.0.0.1/ 2>/dev/null && break
  sleep 1
done

# 场景一（合法）：安装成功（带可选证书目录）、reload 生效、路由通、证书落位
cat >"$gate_tmp/good.conf" <<'EOF'
server {
    listen 80;
    server_name selftest-web.test;
    location / { proxy_pass http://selftest-web:80; }
}
EOF
mkdir -p "$gate_tmp/certs"
echo dummy-cert >"$gate_tmp/certs/fullchain.pem"
echo dummy-key  >"$gate_tmp/certs/privkey.pem"
bash ./install-nginx-conf.sh selftest-web "$gate_tmp/good.conf" "$gate_tmp/certs" >/dev/null \
  || die "合法 conf（带证书目录）被门禁误拒"
[ -f nginx/conf.d/selftest-web.conf ] || die "合法 conf 安装后 conf.d 下无名下文件"
[ -f nginx/certs/selftest-web/fullchain.pem ] || die "证书未落入名下 certs/selftest-web/"
body=$(http_probe selftest-web.test)
[ "$body" = "selftest-web-ok" ] || die "合法 conf 路由不通（Host=selftest-web.test 应答：$body）"
ok "合法 conf：安装＋reload 生效，路由通（selftest-web.test → 后端应答），证书入名下目录"

# 场景二（撞车即拒）：他服务声明已占用的 server_name——写文件前拒绝，错误信息明确
cat >"$gate_tmp/collision.conf" <<'EOF'
server {
    listen 80;
    server_name selftest-web.test;
    location / { return 200 "hijack\n"; }
}
EOF
if bash ./install-nginx-conf.sh selftest-api "$gate_tmp/collision.conf" 2>"$gate_tmp/collision.err"; then
  die "server_name 撞车竟被放行（selftest-api 抢占 selftest-web.test）"
fi
grep -q 'selftest-web.test' "$gate_tmp/collision.err" \
  || die "拒绝信息未指明冲突的 server_name：$(cat "$gate_tmp/collision.err")"
[ ! -f nginx/conf.d/selftest-api.conf ] || die "撞车被拒后竟留下了文件 conf.d/selftest-api.conf"
ok "撞车即拒：写文件前拒绝、指明冲突名、不留文件"

# 场景二补（通配口径）：单侧通配只挡单层子域、双侧通配不同后缀不算撞车（nginx 语义集合无交集）
cat >"$gate_tmp/wild-ok.conf" <<'EOF'
server {
    listen 80;
    server_name *.selftest-web.test;
    location / { return 200 "wild\n"; }
}
EOF
bash ./install-nginx-conf.sh selftest-wild "$gate_tmp/wild-ok.conf" >/dev/null 2>&1 \
  || die "单侧通配 *.selftest-web.test 被误拒（与既有精确名无交集）"
cat >"$gate_tmp/wild2-ok.conf" <<'EOF'
server {
    listen 80;
    server_name *.sub.selftest-web.test;
    location / { return 200 "wild2\n"; }
}
EOF
bash ./install-nginx-conf.sh selftest-wild2 "$gate_tmp/wild2-ok.conf" >/dev/null 2>&1 \
  || die "双侧通配不同后缀被误拒（单层标签语义下无交集）"
cat >"$gate_tmp/wild-collide.conf" <<'EOF'
server {
    listen 80;
    server_name *.selftest-web.test;
    location / { return 200 "hijack-wild\n"; }
}
EOF
if bash ./install-nginx-conf.sh selftest-wild3 "$gate_tmp/wild-collide.conf" 2>"$gate_tmp/wild.err"; then
  die "通配撞车竟被放行（selftest-wild3 抢占 *.selftest-web.test）"
fi
ok "通配口径：单侧不误拒、双侧不同后缀不误拒、同后缀通配撞车即拒"

# 场景三（坏配置回滚）：语法错误 → 自己文件被回滚、nginx 不倒、其它服务不受影响
cat >"$gate_tmp/broken.conf" <<'EOF'
server {
    listen 80;
    server_name selftest-broken.test;
    location / { proxy_pass http://selftest-web:80;
EOF
if bash ./install-nginx-conf.sh selftest-broken "$gate_tmp/broken.conf" "$gate_tmp/certs" 2>"$gate_tmp/broken.err"; then
  die "语法坏 conf 竟被放行"
fi
[ ! -f nginx/conf.d/selftest-broken.conf ] || die "坏 conf 未被回滚（conf.d/selftest-broken.conf 仍在）"
[ ! -d nginx/certs/selftest-broken ] || die "坏 conf 带的证书目录未被回滚（certs/selftest-broken 仍在）"
healthz_body=$(docker run --rm --network host "$img" \
  wget -q -O - http://127.0.0.1:80/healthz 2>/dev/null || true)
[ "$healthz_body" = "semgravio-infra" ] || die "坏 conf 尝试后 nginx 已不活（healthz：$healthz_body）"
body=$(http_probe selftest-web.test)
[ "$body" = "selftest-web-ok" ] || die "坏 conf 尝试殃及既有服务（路由应答：$body）"
ok "坏配置回滚：文件已撤、nginx 仍活、既有路由不受影响"

# 场景四（资产边界）：路径穿越的服务名/不存在的 conf 一律拒，写不了别人名下的文件
if bash ./install-nginx-conf.sh ../evil "$gate_tmp/good.conf" 2>/dev/null; then
  die "路径穿越服务名 ../evil 竟被放行"
fi
if bash ./install-nginx-conf.sh selftest-web "$gate_tmp/nope.conf" 2>/dev/null; then
  die "不存在的 conf 文件竟被放行"
fi
ok "资产边界：穿越服务名/缺失文件均被拒（全程只写自己名下 conf.d/<service>.conf）"

# ---------------------------------------------------------------- 6. 清场
phase "6/6 清场（成功收尾）"
# 排练安装的自己名下文件/证书目录，直接撤（不经门禁——本就是自清理）
rm -f nginx/conf.d/selftest-*.conf
rm -rf nginx/certs/selftest-web
docker exec semgravio-nginx nginx -s reload >/dev/null 2>&1 || true
docker rm -f selftest-web >/dev/null
rm -rf "$gate_tmp"
docker compose down --remove-orphans >/dev/null
docker volume rm "$VOL" >/dev/null
rm -f .env
ok "已 down 栈、删排练卷 $VOL、删排练 .env（网络 webnet/previewnet 保留——幂等资源）"

printf '\nselftest 全部通过 ✅（起栈健康＋幂等重跑＋回环连通＋形态断言＋建号 helper 幂等/低权限＋nginx 门禁裁决）\n'
