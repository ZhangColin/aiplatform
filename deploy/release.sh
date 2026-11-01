#!/usr/bin/env bash
# 一键发布 semgravio-eu-1（AWS 法兰克福）：打包 → scp → 服务器备份/解包/重建重启 → 冒烟验收。
# 仓库根执行：deploy/release.sh（PowerShell 里：bash deploy/release.sh）
# 流程对齐 deploy/部署文档.md §11 并补两处：infra 一并 up --build（embedding 脚本改动才生效）、
# 网关重建（bind-mount 的 nginx conf 解包后换了 inode，容器内 reload 只能拿到旧文件，必须重建）。
# 解包不含 .env*（打包即排除），现网三份 env 不被覆盖。
#
# 可覆盖变量（默认＝法兰克福现网）：
#   SERVER_HOST=3.70.172.160   SERVER_USER=ubuntu   SSH_KEY=$HOME/ssh/semgravio-eu-1.pem
#   REMOTE_DIR=aiplatform（相对服务器 $HOME；绝对路径以 / 开头也可）
# 常用开关：SKIP_BUILD=1 复用 dist/ 里最新的包只发布；SKIP_BACKUP=1 跳过部署前 pg_dump。
# 服务器侧日常运维（看日志/重启/备份/冒烟）用 deploy/server.sh。
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_HOST="${SERVER_HOST:-3.70.172.160}"
SERVER_USER="${SERVER_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/ssh/semgravio-eu-1.pem}"
REMOTE_DIR="${REMOTE_DIR:-aiplatform}"
REMOTE="${SERVER_USER}@${SERVER_HOST}"

echo "== 0/4 前置检查 =="
[ -f "$SSH_KEY" ] || { echo "错误：找不到密钥 $SSH_KEY（可用 SSH_KEY=... 覆盖）"; exit 1; }
ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=15 "$REMOTE" true

echo "== 1/4 打包 =="
if [ "${SKIP_BUILD:-0}" = "1" ]; then
  BUNDLE=$(ls -t dist/aiplatform-deploy-*.tar.gz 2>/dev/null | head -1 || true)
  [ -n "$BUNDLE" ] || { echo "错误：dist/ 没有现成包；去掉 SKIP_BUILD 全新打包，或先跑 deploy/build-bundle.sh"; exit 1; }
  echo "复用现有包：$BUNDLE"
else
  BUNDLE=$(deploy/build-bundle.sh | tee /dev/stderr | tail -n 1)
  [ -n "$BUNDLE" ] || exit 1
fi
BASENAME=$(basename "$BUNDLE")

echo "== 2/4 上传（$(du -h "$BUNDLE" | cut -f1) → $REMOTE） =="
scp -i "$SSH_KEY" "$BUNDLE" "$REMOTE:"

echo "== 3/4 服务器：备份 → 解包 → 重建重启 → 冒烟 =="
ssh -i "$SSH_KEY" -o BatchMode=yes "$REMOTE" \
  "BUNDLE_FN=$BASENAME REMOTE_DIR=$REMOTE_DIR SKIP_BACKUP=${SKIP_BACKUP:-0} bash -s" <<'REMOTE'
set -euo pipefail
if [ "${REMOTE_DIR:0:1}" = "/" ]; then cd "$REMOTE_DIR"; else cd "$HOME/$REMOTE_DIR"; fi

echo "-- 部署前 PG 备份"
if [ "$SKIP_BACKUP" != "1" ]; then
  BACKUP="$HOME/backup-$(date +%F-%H%M).sql.gz"
  docker exec aiplatform-postgres pg_dump -U aiplatform aiplatform | gzip > "$BACKUP"
  echo "   $BACKUP ($(du -h "$BACKUP" | cut -f1))"
fi

echo "-- 解包 $BUNDLE_FN（不含 .env*，现网 env 不被覆盖）"
tar -xzf "$HOME/$BUNDLE_FN" --strip-components=1
rm "$HOME/$BUNDLE_FN"
echo "   REVISION: $(cat REVISION)"

echo "-- 重建重启（infra → server → web；build 有层缓存，无改动的秒过）"
docker compose -f deploy/infra.compose.yml up -d --build
docker compose -f deploy/server.compose.yml up -d --build
docker compose -f deploy/web.compose.yml up -d --build

echo "-- 重建网关（bind-mount conf 已换新文件，须重建容器才挂上新 inode）"
docker rm -f aiplatform-gateway 2>/dev/null || true
docker run -d --name aiplatform-gateway --restart unless-stopped --network previewnet \
  -p 80:80 -p 8080:80 \
  -v "$PWD/deploy/nginx.demo.conf:/etc/nginx/nginx.conf:ro" \
  -v "$PWD/gateway/gateway-proxy.conf:/etc/nginx/gateway-proxy.conf:ro" \
  -v "$PWD/gateway/gateway-inject.conf:/etc/nginx/gateway-inject.conf:ro" \
  -v "$PWD/gateway/unavailable.html:/etc/nginx/unavailable.html:ro" \
  -v "$PWD/workspace/annotation.js:/etc/nginx/annotation.js:ro" \
  nginx:stable-alpine >/dev/null
docker network connect webnet aiplatform-gateway 2>/dev/null || true
docker exec aiplatform-gateway nginx -t

echo "-- 等后端就绪（actuator/health，最长 180s；期间 Flyway 在跑迁移的话属正常）"
i=0
until docker exec aiplatform-server curl -sf -o /dev/null http://localhost:8888/actuator/health; do
  i=$((i+1))
  [ "$i" -ge 90 ] && { echo "后端 180s 未就绪；查日志：deploy/server.sh logs server"; exit 1; }
  sleep 2
done

echo "-- 冒烟（经网关 80）"
echo "   主站 /        : $(curl -s -o /dev/null -w '%{http_code}' http://localhost/)（期望 200）"
echo "   /api/projects : $(curl -s -o /dev/null -w '%{http_code}' http://localhost/api/projects)（期望 401）"
docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'aiplatform|ws-'
echo "   注：aiplatform-web 显示 unhealthy 是既知误报（容器内 wget 被拒、外部实通），见部署文档 §9"
REMOTE

echo "== 4/4 完成：$BASENAME 已上线 =="
