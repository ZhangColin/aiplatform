#!/usr/bin/env bash
# 预览哨兵（#422，spec #416；包装壳）：发布尾部一次性外部真浏览器冒烟——从发布机外部位点
# 走平台主站＋代表性预览链路，断言页面与关键子资源真实加载（浏览器实跑与断言在
# sentinel/preview-sentinel.mjs，本壳管依赖自检＋一次性哨兵沙箱＋清场）。
#
# 代表性预览目标的取法（查新账、不依赖用户工作区在服态）：
#   缺省＝在服务器 previewnet 起一次性静态沙箱 ws-{SENTINEL_ID}（真 TSID 为 18 位，
#   SENTINEL_ID 缺省 4242424 不撞真工作区），真浏览器打真实预览域
#   {id}.preview.semgravio.com——网关子域捕获/resolver/注入/代理/资产全链路都真走，
#   应用层真伪归 #406 终验不在此盯；结束即拆（无常驻组件）。
#   SENTINEL_PREVIEW_HOST=host[:port] 显式指定目标（如真实工作区、本地排练）＝跳过沙箱。
#
# 用法：deploy/preview-sentinel.sh（release.sh 尾部自动挂；单跑＝复验/排练）
# 变量：SCHEME（缺省 http，全站 TLS 后改 https 或覆盖）、SENTINEL_MAIN_URL、
#   SENTINEL_PREVIEW_HOST、SENTINEL_ID、SENTINEL_RESOLVE（域=IP,... 本地排练指回环）、
#   SERVER_HOST/SERVER_USER/SSH_KEY（与 release.sh 同套默认；只在起沙箱时用）
# 退出码：透传 mjs——0 绿／1 断言红（发布流水线红，回滚人工决）／2 环境态（先修环境）。
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_HOST="${SERVER_HOST:-3.70.172.160}"
SERVER_USER="${SERVER_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/ssh/semgravio-eu-1.pem}"
REMOTE="${SERVER_USER}@${SERVER_HOST}"
SCHEME="${SCHEME:-http}"
SENTINEL_ID="${SENTINEL_ID:-4242424}"
SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=15 "$REMOTE")

die() { printf '✗ %s\n' "$*" >&2; exit 2; }

# ① 依赖自检（一次性成本，装完即缓存）
command -v node >/dev/null 2>&1 || die "node 不可用（发布机需 Node ≥20）"
if [ ! -d deploy/sentinel/node_modules/playwright ]; then
  command -v pnpm >/dev/null 2>&1 || die "pnpm 不可用且 deploy/sentinel 未装依赖——先 cd deploy/sentinel && pnpm install"
  echo "首跑装依赖（playwright 库；浏览器走系统 Edge/Chrome 或既有缓存，零额外下载）"
  (cd deploy/sentinel && pnpm install --silent) || die "playwright 依赖安装失败（环境态）——网络/registry 排查后重跑"
fi

# ② 一次性哨兵沙箱（缺省路径；显式指定目标则跳过）
# 脚本执行证明令牌单源：远端 chunk 写入与本地断言期望都从这一处取值（防两处成形锁步漂移）
SENTINEL_TOKEN="loaded-$SENTINEL_ID"
SPAWNED=0
cleanup() {
  if [ "$SPAWNED" = 1 ]; then
    # 尽力清场（幂等；起沙箱中途失败也走这里——不留残留容器/临时目录在服务器）
    "${SSH[@]}" "docker rm -f ws-$SENTINEL_ID >/dev/null 2>&1; rm -rf /tmp/preview-sentinel" >/dev/null 2>&1 || true
    echo "哨兵沙箱已拆（ws-$SENTINEL_ID）"
  fi
}
trap cleanup EXIT

if [ -z "${SENTINEL_PREVIEW_HOST:-}" ]; then
  [ -f "$SSH_KEY" ] || die "找不到密钥 $SSH_KEY（起哨兵沙箱需要；或 SENTINEL_PREVIEW_HOST 指定目标免 SSH）"
  echo "-- 起一次性哨兵沙箱 ws-$SENTINEL_ID（previewnet 静态页＋/_next 资产；结束自动拆）"
  SPAWNED=1 # 先于起沙箱置位：中途任一步失败，trap 也兜住清场（远端 rm 幂等）
  # 注意 if/heredoc 形态：then 必须在 heredoc 终止符之后（then 行会先被 heredoc 吃掉）
  if "${SSH[@]}" "SID=$SENTINEL_ID TOKEN=$SENTINEL_TOKEN bash -s" <<'REMOTE'
set -euo pipefail
IMG=$(docker inspect -f '{{.Config.Image}}' semgravio-nginx)
D=/tmp/preview-sentinel
rm -rf "$D" && mkdir -p "$D/_next/static" "$D/__nextjs_font"
# 页面形态对齐真预览页的关键面：样式/脚本（no-cors 无 Origin）＋两个 crossorigin 字体
# 预载（CSS Fonts 规范 CORS 模式必带 Origin——浏览器侧真实携带 Origin 的形态）
printf '<!doctype html><html><head><title>preview-sentinel</title>%s\n%s\n%s\n%s\n</head><body>preview-sentinel-%s-ok</body></html>\n' \
  '<link rel="stylesheet" href="/_next/static/app.css">' \
  '<script src="/_next/static/chunk.js"></script>' \
  '<link rel="preload" as="font" type="font/woff2" crossorigin href="/_next/static/sentinel.woff2">' \
  '<link rel="preload" as="font" type="font/woff2" crossorigin href="/__nextjs_font/sentinel.woff2">' \
  "$SID" > "$D/index.html"
printf 'window.__sentinelChunk = %s;\n' "'$TOKEN'" > "$D/_next/static/chunk.js"
printf 'body { font-family: preview-sentinel; }\n' > "$D/_next/static/app.css"
printf 'font' > "$D/_next/static/sentinel.woff2"
printf 'font' > "$D/__nextjs_font/sentinel.woff2"
# 沙箱模拟 Next 15.2+ dev 闸门（#403 锚事故形态）：带 Origin 的 /_next* 与 /__nextjs*
# 一律 403——网关剥 Origin 在位则浏览器常态 200；网关回归透传 Origin 则 403＝哨兵红
cat > "$D/serve.conf" <<'CONF'
server {
    listen 8081;
    root /srv;
    index index.html;
    location /_next/ {
        if ($http_origin) { return 403; }
    }
    location /__nextjs {
        if ($http_origin) { return 403; }
    }
}
CONF
docker rm -f "ws-$SID" >/dev/null 2>&1 || true
docker run -d --name "ws-$SID" --network previewnet \
  -v "$D/serve.conf:/etc/nginx/conf.d/serve.conf:ro" \
  -v "$D/index.html:/srv/index.html:ro" \
  -v "$D/_next/static/chunk.js:/srv/_next/static/chunk.js:ro" \
  -v "$D/_next/static/app.css:/srv/_next/static/app.css:ro" \
  -v "$D/_next/static/sentinel.woff2:/srv/_next/static/sentinel.woff2:ro" \
  -v "$D/__nextjs_font/sentinel.woff2:/srv/__nextjs_font/sentinel.woff2:ro" \
  "$IMG" >/dev/null
i=0
until [ "$(docker inspect -f '{{.State.Running}}' "ws-$SID" 2>/dev/null)" = true ]; do
  i=$((i+1)); [ "$i" -ge 10 ] && { echo "沙箱容器 10s 未 Running" >&2; exit 1; }
  sleep 1
done
REMOTE
then
  :
else
  die "哨兵沙箱起失败（环境态）——SSH 连通/服务器 docker/previewnet 排查后重跑"
fi
  PREVIEW_HOST="$SENTINEL_ID.preview.semgravio.com"
else
  PREVIEW_HOST="$SENTINEL_PREVIEW_HOST"
fi

# ③ 浏览器实跑（断言与退出码都在 mjs；红＝非零退出即发布流水线红）
MAIN_URL="${SENTINEL_MAIN_URL:-$SCHEME://semgravio.com}"
ARGS=()
if [ -n "${SENTINEL_RESOLVE:-}" ]; then ARGS+=(--resolve "$SENTINEL_RESOLVE"); fi
if [ "$SPAWNED" = 1 ]; then ARGS+=(--preview-window "__sentinelChunk=$SENTINEL_TOKEN"); fi
node deploy/sentinel/preview-sentinel.mjs "$MAIN_URL" "$SCHEME://$PREVIEW_HOST" ${ARGS:+"${ARGS[@]}"}
