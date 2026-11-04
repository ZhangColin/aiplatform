#!/usr/bin/env bash
# 服务器运维小工具（semgravio-eu-1；连接参数与 deploy/release.sh 同套默认，可同法覆盖；
# 本脚本只在本地拼命令、docker 一律在服务器侧执行）：
#   deploy/server.sh status           容器概览（aiplatform-* ＋ semgravio-* infra ＋ 工作区 ws-*）
#   deploy/server.sh logs [svc]       跟日志，svc=server|web|nginx|postgres|redis|embedding|ws-<id>（缺省 server）
#                                     （nginx＝共享入口 semgravio-nginx；postgres/redis 容器名跨迁移自适应：
#                                     旧名/semgravio-* 谁运行用谁。预览网关已退役 #386，无 gateway）
#   deploy/server.sh restart          重启后端＋前端（不重建镜像、不动 infra 与 nginx 配置，秒级）
#   deploy/server.sh dump             pg_dump 到服务器 ~/backup-<时间>.sql.gz（PG 容器名同上自适应）
#   deploy/server.sh smoke            主站/API 冒烟（经共享 nginx，Host 头命中平台主站块；期望 200/401）
#   deploy/server.sh sh               登服务器交互 shell
# 换版上线（打包＋发布＋重建）不是这里，用 deploy/release.sh。
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_HOST="${SERVER_HOST:-3.70.172.160}"
SERVER_USER="${SERVER_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/ssh/semgravio-eu-1.pem}"
REMOTE="${SERVER_USER}@${SERVER_HOST}"
SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes "$REMOTE")

case "${1:-help}" in
  status)
    "${SSH[@]}" "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | grep -E 'aiplatform|semgravio|ws-' || true"
    echo "（web 的 unhealthy 是既知误报，见部署文档 §9）"
    ;;
  logs)
    case "${2:-server}" in
      server) C=aiplatform-server ;; web) C=aiplatform-web ;; nginx) C=semgravio-nginx ;;
      # PG/redis 容器名跨迁移自适应（#382 更名 semgravio-pg/redis，现网仍跑旧名）：谁运行用谁，
      # 迁移窗口前后免改可用。#386 起预览路由在共享 nginx（semgravio-nginx）里，无独立网关容器。
      postgres) C='$(docker ps --format "{{.Names}}" | grep -xE "aiplatform-postgres|semgravio-pg" | head -n1)' ;;
      redis)    C='$(docker ps --format "{{.Names}}" | grep -xE "aiplatform-redis|semgravio-redis" | head -n1)' ;;
      embedding) C=aiplatform-embedding ;;
      *) C="$2" ;;
    esac
    exec "${SSH[@]}" "docker logs -f --tail 200 $C"
    ;;
  restart)
    "${SSH[@]}" 'docker restart aiplatform-web aiplatform-server >/dev/null && echo 已重启 web/server（nginx 配置热更走门禁，无须重启）'
    ;;
  dump)
    "${SSH[@]}" 'PG_C=$(docker ps --format "{{.Names}}" | grep -xE "aiplatform-postgres|semgravio-pg" | head -n1); docker exec "$PG_C" pg_dump -U aiplatform aiplatform | gzip > ~/backup-$(date +%F-%H%M).sql.gz && ls -t ~/backup-*.sql.gz | head -1'
    ;;
  smoke)
    "${SSH[@]}" 'echo "主站 /        : $(curl -s -o /dev/null -w "%{http_code}" -H "Host: semgravio.com" http://localhost/)（期望 200）"; echo "/api/projects : $(curl -s -o /dev/null -w "%{http_code}" -H "Host: semgravio.com" http://localhost/api/projects)（期望 401）"'
    ;;
  sh)
    exec "${SSH[@]}"
    ;;
  *)
    grep '^# ' "$0" | tail -n +2
    ;;
esac
