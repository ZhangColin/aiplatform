#!/usr/bin/env bash
# 平台全栈排练（#386 AC：本地全栈行为与现网一致）——infra ＋ 平台起来后，经共享 nginx
# 断言主站与 {id}.preview 动态路由（注入＋纯代理）、资产单源、redis requirepass 下平台连接。
#
#   STUB=0（默认，全栈）：真 server（含 embedding 归位后的服务）＋真 web 镜像构建起栈——
#     慢（首次构建数十分钟）但 redis AC 走后端真实配置链路；后端/embedding 相关断言启用。
#   STUB=1（快路径）：web 用假后端替身（previewnet/webnet 路由断言全量照跑），
#     不构建 server/web/embedding——日常改 conf/块文件后回归用。
#
# 断言清单（全部外部行为，对应 issue #386/#396 验收）：
#   ① 主站 semgravio.com → web（200）
#   ② 主预览 {id}.preview.semgravio.com → ws-{id}：HTML 注入标注脚本恰一次（注入类目标）
#   ③ 快照 snap-{vid}.preview.semgravio.com → snap-{vid}：纯代理不注入（纯代理类目标）
#   ④ 标注脚本资产由共享 nginx 自持可访问，且与仓内单源逐字节一致
#   ⑤ 死子域 → 平台风格自恢复提示页（5xx＋文案＋no-store＋recovery 脚本、不注入圈注）
#   ⑥ redis requirepass：平台凭据分片 PONG、无密码 NOAUTH（全栈模式下后端 env 同值真实连接）
#   ⑦（全栈）后端健康、embedding 归位可服务、/api 经主站反代 401
#   ⑧ 浏览器头组合探针（#396）：Host＋Origin(预览域)＋Referer＋Sec-Fetch 打 /_next 资产
#      非 403；沙箱回显 X-Echo-Origin——/_next 上游无 Origin（网关剥）、非 /_next 原样透传
#
# ⚠ 共享资源协调：本排练占用宿主 80/443/5432/6379 与容器名 semgravio-{pg,redis,nginx}/
# aiplatform-{server,web,embedding}——与 deploy/infra/selftest.sh 及同仓并行会话的 docker
# 排练互斥。跑之前确认没有其他会话正在用（同仓并行 /implement 先互相 SendMessage 协调）。
#
# 失败时现场保留（trap 提示排查/清理命令）；成功全量清场（含自建 infra 栈与凭据正本）。
# 用法（仓库根）：deploy/rehearsal.sh     快路径：STUB=1 deploy/rehearsal.sh
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
STUB="${STUB:-0}"
INFRA_DIR=deploy/infra

ok()  { printf '  ✓ %s\n' "$*"; }
die() { printf '✗ %s\n' "$*" >&2; exit 1; }
phase(){ printf '\n== %s ==\n' "$*"; }

infra_running() { # 三容器 healthy 才算「infra 已在场可复用」
  local c
  for c in semgravio-pg semgravio-redis semgravio-nginx; do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || true)" = healthy ] || return 1
  done
}

# ---------------------------------------------------------------- 0. 前置
phase "0/7 前置检查（docker/端口/容器名）"
docker info >/dev/null 2>&1 || die "docker 不可用"
command -v curl >/dev/null 2>&1 || die "curl 不可用"
command -v openssl >/dev/null 2>&1 || die "openssl 不可用"

INFRA_OWNED=0
if infra_running; then
  ok "infra 三容器已在场且 healthy——复用，收尾不拆"
else
  for p in 80 443 5432 6379; do
    if (exec 3<>/dev/tcp/127.0.0.1/"$p") 2>/dev/null; then
      exec 3>&- 3<&-
      die "端口 $p 被占用且 infra 未在场。先停占用者（本地 dev 栈/其它排练）再跑"
    fi
  done
  for c in semgravio-pg semgravio-redis semgravio-nginx; do
    docker inspect "$c" >/dev/null 2>&1 \
      && die "容器名 $c 被占用（非健康 infra）。先 docker rename $c 让位再跑"
  done
  INFRA_OWNED=1
fi
ok "前置通过（infra 复用/自建：INFRA_OWNED=$INFRA_OWNED）"

trap 'printf "\n✗ 排练失败，现场保留供排查（%s 行）。\n" "${LINENO:-?}"
      printf "排查：docker compose -f deploy/server.compose.yml ps ／ docker logs aiplatform-server 等\n"
      printf "清场：rm -f deploy/infra/nginx/conf.d/aiplatform.conf; rm -rf deploy/infra/nginx/conf.d/aiplatform.assets\n"
      printf "       docker rm -f ws-4242 rehearsal-snap-777 aiplatform-web 2>/dev/null\n"
      printf "       docker compose -f deploy/server.compose.yml down --remove-orphans; docker compose -f deploy/web.compose.yml down --remove-orphans\n"
      printf "       （自建 infra 时）cd deploy/infra && docker compose down --remove-orphans && docker volume rm semgravio_pgdata && rm -f .env\n"' ERR

# Git Bash 单文件 bind-mount 口径（同 deploy/infra/selftest.sh）：容器内路径禁 MSYS 改写、
# 源路径显式 Windows 形；Linux 原样走 POSIX。
win_mount() { # $1=宿主文件 $2=容器路径 → 输出 -v 参数字符串（已按平台整形）
  local src=$1 dst=$2
  if command -v cygpath >/dev/null 2>&1; then
    printf '%s:%s:ro' "$(cygpath -w "$src")" "$dst"
  else
    printf '%s:%s:ro' "$src" "$dst"
  fi
}
command -v cygpath >/dev/null 2>&1 && export MSYS_NO_PATHCONV=1

# ---------------------------------------------------------------- 1. infra
phase "1/7 共享基础设施（${INFRA_OWNED:+自建起栈}/复用）"
if [ "$INFRA_OWNED" = 1 ]; then
  (cd "$INFRA_DIR" && {
    # 排练凭据正本自带 managed-by 标记（同 selftest 口径）：失败现场重跑不被误拒
    umask 077
    {
      echo '# managed-by: rehearsal（rehearsal.sh 生成的排练凭据，非本机正式正本）'
      echo 'POSTGRES_USER=aiplatform'
      echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)"
      echo "REDIS_PASSWORD=$(openssl rand -hex 16)"
    } > .env
    ./deploy.sh
  })
  ok "infra 自建起栈完成"
else
  ok "infra 复用在场栈"
fi
set -a; . "$INFRA_DIR/.env"; set +a
: "${POSTGRES_PASSWORD:?}" "${REDIS_PASSWORD:?}"
probe_img=$(docker inspect -f '{{.Config.Image}}' semgravio-nginx)

# ---------------------------------------------------------------- 2. 平台 env
phase "2/7 平台 env.production（infra 凭据分片＋本地 .env.local 的 key）"
[ -f aiplatform-server/.env.local ] || die "缺 aiplatform-server/.env.local（key 来源，见 deploy/env.server.example）"
SERVER_ENV=aiplatform-server/.env.production
WEB_ENV=aiplatform-web/.env.production

write_envs() { # 排练 env 由本脚本全权生成（凭据分片随当前 infra .env，key 来自 .env.local）
  # 偏差声明：DB 用户用 infra 超管 aiplatform（与 env.server.example/eu-1 现网同口径），
  # 非 ensure-db-user.sh 分片——分片凭据是接入目标态（deploy/infra/README.md「凭据模型」），
  # 但本排练的判据是「行为与现网一致」，且 Flyway V1 建 vector 扩展在全新排练卷上需要
  # 库属主级权限；迁分片模型时排练 env 同步改（届时 ensure-db-user 对齐即可，不改本脚本结构）
  {
    echo "# managed-by: rehearsal（rehearsal.sh 生成；正式部署请以 deploy/env.server.example 为准）"
    echo "SPRING_PROFILES_ACTIVE=prod"
    echo "SPRING_DATASOURCE_URL=jdbc:postgresql://semgravio-pg:5432/aiplatform"
    echo "SPRING_DATASOURCE_USERNAME=aiplatform"
    echo "SPRING_DATASOURCE_PASSWORD=$POSTGRES_PASSWORD"
    echo "REDIS_HOST=semgravio-redis"
    echo "REDIS_PORT=6379"
    echo "REDIS_PASSWORD=$REDIS_PASSWORD"
    echo "EMBEDDING_URL=http://aiplatform-embedding:9091"
    echo "PREVIEW_BASE=preview.semgravio.com"
    echo "APP_WORKSPACE_PREVIEW_SCHEME=http"
    echo "SSO_COOKIE_SECURE=false"
    grep -E "^(VOLCENGINE_API_KEY|DEEPSEEK_API_KEY|BOCHA_API_KEY|IMAGE_GEN_PROVIDER|ARK_API_KEY|RESEND_API_KEY|MAIL_FROM)=" aiplatform-server/.env.local || true
  } > "$SERVER_ENV"
  cp deploy/env.web.example "$WEB_ENV"
}

ENV_CREATED=0
if [ "$INFRA_OWNED" = 1 ]; then
  # 自建 infra＝本排练全权拥有现场：env 始终重写（凭据正本是本轮新生成的随机值，
  # 上一轮失败现场留下的旧 env 必须被覆盖——踩过：旧 DB 密码导致 28P01 认证失败）
  write_envs
  ENV_CREATED=1
  ok "已（重）写排练 env（自建 infra，凭据分片来自本轮 .env）"
elif [ ! -f "$SERVER_ENV" ]; then
  write_envs
  ENV_CREATED=1
  ok "已生成排练 env（凭据分片来自在场 infra .env，key 来自 .env.local）"
else
  grep -q "^SPRING_DATASOURCE_PASSWORD=$POSTGRES_PASSWORD\$" "$SERVER_ENV" \
    || die "既有 $SERVER_ENV 的 DB 密码与在场 infra .env 不匹配（旧排练残留？）——删掉它或改齐后再跑"
  grep -q "^REDIS_PASSWORD=$REDIS_PASSWORD\$" "$SERVER_ENV" \
    || die "既有 $SERVER_ENV 的 redis 密码与在场 infra .env 不匹配——删掉它或改齐后再跑"
  ok "复用既有 $SERVER_ENV（分片凭据与在场 infra 核对一致）"
fi

# ---------------------------------------------------------------- 3. 平台起栈
phase "3/7 平台起栈（STUB=$STUB）"
if [ "$STUB" = 1 ]; then
  docker rm -f aiplatform-web >/dev/null 2>&1 || true
  stub_tmp=$(mktemp -d)
  cat >"$stub_tmp/stub.conf" <<'EOF'
server {
    listen 3333;
    location / { return 200 "rehearsal-stub-web\n"; }
}
EOF
  docker run -d --name aiplatform-web --network webnet \
    -v "$(win_mount "$stub_tmp/stub.conf" /etc/nginx/conf.d/stub.conf)" \
    "$probe_img" >/dev/null
  ok "假 web 替身已起（aiplatform-web:3333 恒定应答）"
else
  docker compose -f deploy/server.compose.yml up -d --build
  docker compose -f deploy/web.compose.yml up -d --build
  # 等后端健康（Flyway 首启迁移可能较久）
  i=0
  until docker exec aiplatform-server curl -sf -o /dev/null http://localhost:8888/actuator/health 2>/dev/null; do
    i=$((i+1)); [ "$i" -ge 150 ] && die "后端 300s 未就绪：docker logs aiplatform-server"
    sleep 2
  done
  ok "后端 healthy（requirepass redis 同配置链路上已真实连接）"
fi

# ---------------------------------------------------------------- 4. conf 经门禁安装
phase "4/7 平台 conf 经门禁装入共享 nginx"
assets_tmp=$(mktemp -d)
bash deploy/nginx/assemble-assets.sh "$assets_tmp/assets" "$ROOT"
(cd "$INFRA_DIR" && ./install-nginx-conf.sh aiplatform \
  "$ROOT/deploy/nginx/aiplatform.conf" "" "$assets_tmp/assets")
ok "conf.d/aiplatform.conf ＋ conf.d/aiplatform.assets/ 已生效"

# ---------------------------------------------------------------- 5. 假沙箱
phase "5/7 假沙箱容器（previewnet，验证动态路由两类目标）"
sandbox_tmp=$(mktemp -d)
mk_sandbox() { # $1=容器名（＝previewnet DNS 名） $2=网络别名(可空) $3=应答标记
  local name=$1 alias=$2 marker=$3 d=$sandbox_tmp/$1
  mkdir -p "$d"
  printf '<html><head><title>%s</title></head><body>%s</body></html>' "$name" "$marker" >"$d/index.html"
  # #396 /_next 探针资产：浏览器头组合打 /_next/ 时有真实文件可 200（缺文件只能验到
  # 404「非 403」的弱断言，探不到 200 形态）
  printf 'console.log("%s-next-chunk");\n' "$name" >"$d/chunk.js"
  cat >"$d/serve.conf" <<EOF
server {
    listen 8081;
    root /srv;
    index index.html;
    # #396 回显探针：收到的 Origin 回显为响应头（always＝任意状态码都带；变量为空时
    # nginx 整头不下发）——「网关把什么转发给了上游」经响应可观测（同 #394 冒烟 stub
    # 上游的 X-Echo-Origin 手法，排练以沙箱容器 nginx 承载）
    add_header X-Echo-Origin \$http_origin always;
}
EOF
  docker rm -f "$name" >/dev/null 2>&1 || true
  local netargs=(--network previewnet)
  [ -n "$alias" ] && netargs+=(--network-alias "$alias")
  docker run -d --name "$name" "${netargs[@]}" \
    -v "$(win_mount "$d/serve.conf" /etc/nginx/conf.d/serve.conf)" \
    -v "$(win_mount "$d/index.html" /srv/index.html)" \
    -v "$(win_mount "$d/chunk.js" /srv/_next/static/chunk.js)" \
    "$probe_img" >/dev/null
}
# 主预览按容器名 DNS（ws-{id}）：名字必须恰为 ws-4242；id 取非常规值避免撞真工作区
mk_sandbox ws-4242 "" "rehearsal-ws-4242"
# 快照按网络别名（snap-{vid}）：容器名随意、别名必须恰为 snap-777
mk_sandbox rehearsal-snap-777 snap-777 "rehearsal-snap-777"
sleep 1
ok "ws-4242（主预览，容器名 DNS）＋ rehearsal-snap-777/snap-777（快照，网络别名）已起（含 /_next 探针资产＋Origin 回显）"

# ---------------------------------------------------------------- 6. 行为断言
phase "6/7 行为断言（主站/注入/纯代理/资产/提示页/redis＋浏览器头探针）"
H() { curl -s -H "Host: $1" "http://127.0.0.1$2"; }
Hi() { curl -s -i -H "Host: $1" "http://127.0.0.1$2"; }

# ① 主站（重试至 web 就绪；应答体走变量不落盘——MSYS_NO_PATHCONV 下 curl 吃 POSIX 路径会写失败）
i=0
main_code=""
main_body=""
while [ "$i" -lt 30 ]; do
  resp=$(curl -s -w $'\n%{http_code}' -H "Host: semgravio.com" http://127.0.0.1/)
  main_code=${resp##*$'\n'}
  main_body=${resp%$'\n'*}
  [ "$main_code" = 200 ] && break
  i=$((i+1)); sleep 2
done
[ "$main_code" = 200 ] || die "主站 semgravio.com 未 200（最后状态 $main_code）"
if [ "$STUB" = 1 ]; then
  grep -q "rehearsal-stub-web" <<<"$main_body" || die "主站应答非假替身内容"
fi
ok "① 主站 semgravio.com → web：200"

# ② 主预览注入（恰一次）
body=$(H 4242.preview.semgravio.com /)
grep -q "rehearsal-ws-4242" <<<"$body" || die "主预览未路由到 ws-4242（应答头 200 字：${body:0:80}）"
n=$(grep -o 'data-aiplatform="annotation"' <<<"$body" | wc -l)
[ "$n" -eq 1 ] || die "标注脚本注入次数应为恰 1，实际 $n"
ok "② 主预览 {id}.preview → ws-{id}：路由通＋注入恰一次"

# ④ 资产单源：共享 nginx 应答与仓内 annotation.js 逐字节一致
curl -s -H "Host: 4242.preview.semgravio.com" http://127.0.0.1/.aiplatform/annotation.js \
  | cmp -s - aiplatform-server/src/main/resources/docker/workspace/annotation.js \
  || die "annotation.js 与仓内单源不一致（资产装配劈叉？）"
ok "④ 标注脚本资产可访问且与单源逐字节一致"

# ③ 快照纯代理（不注入）
body=$(H snap-777.preview.semgravio.com /)
grep -q "rehearsal-snap-777" <<<"$body" || die "快照未路由到 snap-777"
! grep -q 'data-aiplatform="annotation"' <<<"$body" || die "快照不应注入圈注脚本（只逛不换）"
ok "③ 快照 snap-{vid}.preview → snap-{vid}：纯代理不注入"

# ⑤ 死子域自恢复提示页
raw=$(Hi 999999999999.preview.semgravio.com /)
code=$(head -1 <<<"$raw" | awk '{print $2}')
[ "$code" -ge 500 ] && [ "$code" -le 599 ] || die "死子域应回 5xx（实际 $code）"
grep -q "系统暂不可用，可能正在启动或休眠中" <<<"$raw" || die "提示页文案缺失"
grep -qi "cache-control: no-store" <<<"$raw" || die "提示页缺 no-store"
grep -q 'data-aiplatform="recovery"' <<<"$raw" || die "提示页缺自恢复脚本标记"
! grep -q 'data-aiplatform="annotation"' <<<"$raw" || die "提示页不应注入圈注"
ok "⑤ 死子域 → 5xx＋平台风格自恢复提示页（no-store/不注入）"

# ⑧ #396 浏览器头组合探针：真实浏览器头形态（Host＋Origin=预览域＋Referer＋Sec-Fetch
# 三件套）打 /_next 资产——网关剥 Origin 若退化，即回到「Next 15.2+ dev 跨源闸门对带
# 非白名单 Origin 的 /_next/ 一律 403」（页面卡加载、HMR 握手断）形态；「上游收到了
# 什么」经沙箱回显头 X-Echo-Origin 可观测：/_next/ 无回显（网关剥）、非 /_next/ 有
# 回显（原样透传——Server Actions CSRF/AI 自写 Origin 校验零扰动的守门断言）。
# 探针重试收口（⑦ 先例：高负载下单发 curl 可能瞬时空应答假红——重试而非单发定生死）
browser_probe() { # $1=Host $2=path $3=Sec-Fetch-Dest $4=Sec-Fetch-Mode → 头+体应答（重试至非空）
  local host=$1 path=$2 dest=$3 mode=$4 resp="" i=0
  while [ "$i" -lt 10 ] && [ -z "$resp" ]; do
    resp=$(curl -s -i \
      -H "Host: $host" \
      -H "Origin: http://$host" \
      -H "Referer: http://$host/" \
      -H "Sec-Fetch-Dest: $dest" \
      -H "Sec-Fetch-Mode: $mode" \
      -H "Sec-Fetch-Site: same-origin" \
      -H "User-Agent: Mozilla/5.0 (rehearsal #396 browser-shape probe)" \
      "http://127.0.0.1$path") || true
    i=$((i+1)); [ -z "$resp" ] && sleep 1
  done
  [ -n "$resp" ] || die "浏览器头探针空应答（$host$path，重试 10 次——现场保留排查）"
  printf '%s' "$resp"
}
PREVIEW_HOST=4242.preview.semgravio.com
# /_next 资产（crossorigin/module 加载 chunk 的真实头形态：Dest=script／Mode=cors）
next_resp=$(browser_probe "$PREVIEW_HOST" /_next/static/chunk.js script cors)
next_code=$(head -1 <<<"$next_resp" | awk '{print $2}')
[ "$next_code" = 200 ] \
  || die "带预览域 Origin 打 /_next 资产应 200 非 403（实际 $next_code——403＝网关剥 Origin 退化；其它状态查路由）"
grep -q "ws-4242-next-chunk" <<<"$next_resp" || die "/_next 资产应答体非沙箱内容（路由劈叉？）"
! grep -qi '^x-echo-origin:' <<<"$next_resp" \
  || die "/_next/ 请求上游应收不到 Origin（回显头不应出现——网关剥 Origin 退化）"
# 非 /_next 页面路由（浏览器导航真实头形态：Dest=document／Mode=navigate）：Origin 原样透传
page_resp=$(browser_probe "$PREVIEW_HOST" / document navigate)
grep -qi "^x-echo-origin: http://$PREVIEW_HOST" <<<"$page_resp" \
  || die "非 /_next 请求上游应收到 Origin（原样透传——透传不误伤是硬约束）"
ok "⑧ 浏览器头组合：/_next 非 403 且上游无 Origin（网关剥）、非 /_next Origin 原样透传"

# ⑥ redis requirepass：分片凭据 PONG、无密码 NOAUTH（平台视角，webnet 内 DNS）
redis_img=$(docker inspect -f '{{.Config.Image}}' semgravio-redis)
pong=$(docker run --rm --network webnet "$redis_img" \
  redis-cli -h semgravio-redis -a "$REDIS_PASSWORD" --no-auth-warning ping 2>/dev/null || true)
[ "$pong" = PONG ] || die "平台 redis 分片凭据未 PONG（输出：$pong）"
noauth=$(docker run --rm --network webnet "$redis_img" \
  redis-cli -h semgravio-redis --no-auth-warning ping 2>&1 || true)
grep -q NOAUTH <<<"$noauth" || die "redis 无密码竟未拒（requirepass 未生效：$noauth）"
ok "⑥ redis requirepass：分片凭据 PONG、无密码 NOAUTH"

# ⑦ 全栈附加：后端健康/embedding 归位/api 反代
if [ "$STUB" != 1 ]; then
  # actuator 经 docker exec 探活：高负载（并发构建）下 exec/curl 可能瞬时失败（踩过
  # exit 23 假红），重试收口而非单发定生死
  i=0
  until docker exec aiplatform-server curl -sf -o /dev/null http://localhost:8888/actuator/health 2>/dev/null; do
    i=$((i+1)); [ "$i" -ge 10 ] && die "后端 actuator 不健康（重试 10 次仍失败）"
    sleep 2
  done
  # embedding /health：容器内自探（镜像已带 curl），重试收口——新起探针容器在高负载下
  # 同样可能瞬时失败（踩过 wget 探针空应答假红）
  i=0
  until eh=$(docker exec aiplatform-embedding curl -sf http://localhost:9091/health 2>/dev/null); do
    i=$((i+1)); [ "$i" -ge 10 ] && die "embedding（平台侧部署物）/health 异常：$eh"
    sleep 2
  done
  grep -q '"ok":true' <<<"$eh" || die "embedding /health 应答异常：$eh"
  # api 经主站反代：状态码走变量拆分（宿主 curl 不得带 -o POSIX 路径——MSYS_NO_PATHCONV
  # 下 curl.exe 写 /dev/null 恒 exit 23，踩过三次）
  api_resp=$(curl -s -w $'\n%{http_code}' -H "Host: semgravio.com" http://127.0.0.1/api/projects)
  api=${api_resp##*$'\n'}
  [ "$api" = 401 ] || die "主站 /api/projects 期望 401（实际 ${api:-空/请求失败}）"
  ! docker logs aiplatform-server 2>&1 | grep -qE "RedisConnection|NOAUTH" \
    || die "后端日志出现 redis 连接失败（requirepass 下未正常连接）"
  ok "⑦ 后端 healthy、embedding 归位可服务、/api 经主站反代 401、无 redis 连接失败日志"
fi

# ---------------------------------------------------------------- 7. 清场
phase "7/7 清场"
rm -f "$INFRA_DIR/nginx/conf.d/aiplatform.conf"
rm -rf "$INFRA_DIR/nginx/conf.d/aiplatform.assets"
docker exec semgravio-nginx nginx -s reload >/dev/null 2>&1 || true
docker rm -f ws-4242 rehearsal-snap-777 >/dev/null
if [ "$STUB" = 1 ]; then
  docker rm -f aiplatform-web >/dev/null
  rm -rf "$stub_tmp"
else
  docker compose -f deploy/server.compose.yml down --remove-orphans >/dev/null
  docker compose -f deploy/web.compose.yml down --remove-orphans >/dev/null
fi
rm -rf "$assets_tmp" "$sandbox_tmp"
if [ "$ENV_CREATED" = 1 ]; then
  rm -f "$SERVER_ENV" "$WEB_ENV"
  ok "已删排练 env（$SERVER_ENV / $WEB_ENV）"
fi
if [ "$INFRA_OWNED" = 1 ]; then
  (cd "$INFRA_DIR" && docker compose down --remove-orphans >/dev/null \
    && docker volume rm semgravio_pgdata >/dev/null && rm -f .env)
  ok "已拆自建 infra 栈（含排练卷与 .env；网络 webnet/previewnet 保留——幂等资源）"
fi

printf '\n排练全部通过 ✅（主站＋注入＋纯代理＋资产单源＋提示页＋redis requirepass＋浏览器头剥Origin%s）\n' \
  "$([ "$STUB" != 1 ] && echo '＋全栈后端/embedding' || echo '')"
