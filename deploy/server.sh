#!/usr/bin/env bash
# 服务器运维小工具（semgravio-eu-1；连接参数与 deploy/release.sh 同套默认，可同法覆盖）：
#   deploy/server.sh status           容器概览（aiplatform-* ＋ 工作区 ws-*）
#   deploy/server.sh logs [svc]       跟日志，svc=server|web|gateway|postgres|redis|embedding|ws-<id>（缺省 server）
#   deploy/server.sh restart          重启后端＋前端＋网关（不重建镜像、不动 infra，秒级）
#   deploy/server.sh dump             pg_dump 到服务器 ~/backup-<时间>.sql.gz
#   deploy/server.sh smoke            主站/API 冒烟（200/401）
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
    "${SSH[@]}" "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Image}}' | grep -E 'aiplatform|ws-' || true"
    echo "（web 的 unhealthy 是既知误报，见部署文档 §9）"
    ;;
  logs)
    case "${2:-server}" in
      server) C=aiplatform-server ;; web) C=aiplatform-web ;; gateway) C=aiplatform-gateway ;;
      postgres) C=aiplatform-postgres ;; redis) C=aiplatform-redis ;; embedding) C=aiplatform-embedding ;;
      *) C="$2" ;;
    esac
    exec "${SSH[@]}" "docker logs -f --tail 200 '$C'"
    ;;
  restart)
    "${SSH[@]}" 'docker restart aiplatform-gateway aiplatform-web aiplatform-server >/dev/null && echo 已重启 gateway/web/server'
    ;;
  dump)
    "${SSH[@]}" 'docker exec aiplatform-postgres pg_dump -U aiplatform aiplatform | gzip > ~/backup-$(date +%F-%H%M).sql.gz && ls -t ~/backup-*.sql.gz | head -1'
    ;;
  smoke)
    "${SSH[@]}" 'echo "主站 /        : $(curl -s -o /dev/null -w "%{http_code}" http://localhost/)（期望 200）"; echo "/api/projects : $(curl -s -o /dev/null -w "%{http_code}" http://localhost/api/projects)（期望 401）"'
    ;;
  sh)
    exec "${SSH[@]}"
    ;;
  *)
    grep '^# ' "$0" | tail -n +2
    ;;
esac
