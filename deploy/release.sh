#!/usr/bin/env bash
# 一键发布 semgravio-eu-1（AWS 法兰克福）：打包 → scp → 服务器备份/解包/重建重启 → 冒烟验收。
# 仓库根执行：deploy/release.sh（PowerShell 里：bash deploy/release.sh）
# 平台是 infra 的普通接入方（#386）：发布前置检查共享基础设施（semgravio-{pg,redis,nginx}
# 健康＋webnet/previewnet 在），缺失/不健康即中止并提示先跑 infra 起栈；预览路由/主站反代
# 的平台 conf 经门禁 install-nginx-conf.sh 安装（conf.d 目录挂载＝热更，不再有网关
# 容器重建段——手工 docker run 特例已退役）。embedding 归 server compose 同栈编排。
# 解包不含 .env*（打包即排除），现网 env 不被覆盖。
#
# 可覆盖变量（默认＝法兰克福现网）：
#   SERVER_HOST=3.70.172.160   SERVER_USER=ubuntu   SSH_KEY=$HOME/ssh/semgravio-eu-1.pem
#   REMOTE_DIR=aiplatform（相对服务器 $HOME；绝对路径以 / 开头也可）
#   INFRA_DIR=semgravio-infra（相对服务器 $HOME；共享 infra 单元目录，门禁脚本所在）
# 常用开关：SKIP_BUILD=1 复用 dist/ 里最新的包只发布；SKIP_BACKUP=1 跳过部署前 pg_dump。
# 服务器侧日常运维（看日志/重启/备份/冒烟）用 deploy/server.sh。
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_HOST="${SERVER_HOST:-3.70.172.160}"
SERVER_USER="${SERVER_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/ssh/semgravio-eu-1.pem}"
REMOTE_DIR="${REMOTE_DIR:-aiplatform}"
INFRA_DIR="${INFRA_DIR:-semgravio-infra}"
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

echo "== 3/4 服务器：infra 前置检查 → 备份 → 解包 → 重建重启 → conf 门禁安装 → 冒烟 =="
ssh -i "$SSH_KEY" -o BatchMode=yes "$REMOTE" \
  "BUNDLE_FN=$BASENAME REMOTE_DIR=$REMOTE_DIR INFRA_DIR=$INFRA_DIR SKIP_BACKUP=${SKIP_BACKUP:-0} bash -s" <<'REMOTE'
set -euo pipefail
if [ "${REMOTE_DIR:0:1}" = "/" ]; then cd "$REMOTE_DIR"; else cd "$HOME/$REMOTE_DIR"; fi
if [ "${INFRA_DIR:0:1}" = "/" ]; then INFRA_PATH="$INFRA_DIR"; else INFRA_PATH="$HOME/$INFRA_DIR"; fi

echo "-- infra 前置健康检查（缺失/不健康即中止——先在服务器上跑 infra 起栈：cd $INFRA_PATH && ./deploy.sh）"
infra_bad=0
for c in semgravio-pg semgravio-redis semgravio-nginx; do
  st=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo missing)
  if [ "$st" != "healthy" ]; then
    echo "   ✗ $c：$st"
    infra_bad=1
  else
    echo "   ✓ $c healthy"
  fi
done
for net in webnet previewnet; do
  if ! docker network inspect "$net" >/dev/null 2>&1; then
    echo "   ✗ 网络 $net 缺失"
    infra_bad=1
  fi
done
[ "$infra_bad" = 0 ] || { echo "infra 前置检查未过：先跑共享基础设施起栈（$INFRA_PATH/deploy.sh，见 deploy/infra/README.md），再重跑发布"; exit 1; }

echo "-- 部署前 PG 备份"
if [ "$SKIP_BACKUP" != "1" ]; then
  # PG 容器名跨迁移自适应（旧 aiplatform-postgres / 新 semgravio-pg，谁运行用谁）
  PG_C=$(docker ps --format "{{.Names}}" | grep -xE "aiplatform-postgres|semgravio-pg" | head -n1)
  [ -n "$PG_C" ] || { echo "找不到 PG 容器（旧/新名均无）"; exit 1; }
  BACKUP="$HOME/backup-$(date +%F-%H%M).sql.gz"
  docker exec "$PG_C" pg_dump -U aiplatform aiplatform | gzip > "$BACKUP"
  echo "   $BACKUP ($(du -h "$BACKUP" | cut -f1))"
fi

echo "-- 解包 $BUNDLE_FN（不含 .env*，现网 env 不被覆盖）"
tar -xzf "$HOME/$BUNDLE_FN" --strip-components=1
rm "$HOME/$BUNDLE_FN"
echo "   REVISION: $(cat REVISION)"

echo "-- 退役旧预览网关容器（手工 docker run 特例，#386 起部署面不再有它；幂等）"
if docker inspect aiplatform-gateway >/dev/null 2>&1; then
  docker rm -f aiplatform-gateway >/dev/null && echo "   已移除 aiplatform-gateway"
else
  echo "   不在场（已是共享 nginx 形态）"
fi

echo "-- 重建重启（server＋embedding 同栈 → web；build 有层缓存，无改动的秒过）"
docker compose -f deploy/server.compose.yml up -d --build
docker compose -f deploy/web.compose.yml up -d --build

echo "-- 平台 conf 经门禁安装（撞车即拒/语法门禁/失败回滚；conf.d 挂载热更，无需重建任何容器）"
"$INFRA_PATH/install-nginx-conf.sh" aiplatform "$PWD/deploy/nginx/aiplatform.conf" "" "$PWD/deploy/nginx/assets"

echo "-- 等后端就绪（actuator/health，最长 180s；期间 Flyway 在跑迁移的话属正常）"
i=0
until docker exec aiplatform-server curl -sf -o /dev/null http://localhost:8888/actuator/health; do
  i=$((i+1))
  [ "$i" -ge 90 ] && { echo "后端 180s 未就绪；查日志：deploy/server.sh logs server"; exit 1; }
  sleep 2
done

echo "-- 等前端就绪（经共享 nginx 探主站，最长 60s）"
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' -H 'Host: semgravio.com' http://localhost/)" = "200" ]; do
  i=$((i+1))
  [ "$i" -ge 30 ] && { echo "前端 60s 未就绪；查日志：deploy/server.sh logs web"; exit 1; }
  sleep 2
done

echo "-- 冒烟（经共享 nginx 80，Host 头命中平台主站块）"
echo "   主站 /        : $(curl -s -o /dev/null -w '%{http_code}' -H 'Host: semgravio.com' http://localhost/)（期望 200）"
echo "   /api/projects : $(curl -s -o /dev/null -w '%{http_code}' -H 'Host: semgravio.com' http://localhost/api/projects)（期望 401）"
docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'aiplatform|semgravio|ws-'
echo "   注：aiplatform-web 显示 unhealthy 是既知误报（容器内 wget 被拒、外部实通），见部署文档 §9"
REMOTE

echo "== 4/4 完成：$BASENAME 已上线 =="
