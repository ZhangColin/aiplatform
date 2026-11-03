#!/usr/bin/env bash
# semgravio-infra 一键起栈（幂等收敛，重跑不改变现网状态、不动数据）。
# 干净 Ubuntu 一条命令从零到三容器健康；本地 Docker 排练同一条命令。
# 做的事（每步幂等，已具备即跳过）：
#   ① docker/compose 缺失时官方脚本 bootstrap（已具备时行为不变）
#   ② 幂等创建网络 webnet（共享接入）＋ previewnet（平台沙箱，nginx 双挂所需）
#   ③ 幂等创建 external 数据卷 semgravio_pgdata
#   ④ .env 缺失时生成随机凭据正本（仅全新机器；接管既有服务器必须先手写现值！）
#   ⑤ docker compose up -d ＋ 等三容器 healthy
# 用法：./deploy.sh     （大陆机器装 docker 走阿里源：INSTALL_MIRROR=Aliyun ./deploy.sh）
set -euo pipefail
cd "$(dirname "$0")"

CONTAINERS=(semgravio-pg semgravio-redis semgravio-nginx)

log() { printf '==> %s\n' "$*"; }

# ① docker / compose v2 bootstrap（官方脚本两样都装；已具备则整段跳过）
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  log "docker/compose 缺失——官方脚本 bootstrap"
  SUDO=""
  [ "$(id -u)" -eq 0 ] || SUDO="sudo"
  MIRROR=""
  [ "${INSTALL_MIRROR:-}" = "Aliyun" ] && MIRROR="--mirror Aliyun"
  curl -fsSL https://get.docker.com | $SUDO sh -s -- $MIRROR
  # 拉起当前用户进 docker 组（组权限生效需重新登录，未生效则下一步报错提示）
  $SUDO usermod -aG docker "$USER" 2>/dev/null || true
fi
docker ps >/dev/null 2>&1 || {
  echo "错误：docker 不可用。若刚 bootstrap 完，docker 组权限要重新登录才生效——重登后重跑本脚本。" >&2
  exit 1
}
docker compose version >/dev/null 2>&1 || { echo "错误：docker compose v2 仍缺失" >&2; exit 1; }

# ② 共享网络（external 固定名：跨项目引用，infra 负责幂等创建）
#    previewnet 创建归 infra 兜底（nginx 双挂所需、#382 AC 要求两网存在）；
#    沙箱容器成员管理仍归平台部署——infra 只管网络存在，不管谁进来。
for net in webnet previewnet; do
  if ! docker network inspect "$net" >/dev/null 2>&1; then
    docker network create "$net" >/dev/null
    log "已创建网络 $net"
  fi
done

# ③ 数据卷（external 固定名：down 不删、换项目名不动；已存在即跳过）
if ! docker volume inspect semgravio_pgdata >/dev/null 2>&1; then
  docker volume create semgravio_pgdata >/dev/null
  log "已创建数据卷 semgravio_pgdata"
fi

# ④ 凭据正本（服务器上唯一正本、不入 git；umask 077 防读）
if [ ! -f .env ]; then
  command -v openssl >/dev/null 2>&1 || { echo "错误：openssl 缺失（生成 .env 需要）" >&2; exit 1; }
  umask 077
  {
    echo "# semgravio-infra 凭据正本（deploy.sh 生成——全新机器专用）"
    echo "# ⚠ 接管既有服务器（如 semgravio-eu-1）必须删除本文件改为现值后再跑 deploy.sh："
    echo "#   POSTGRES_PASSWORD 延用原 deploy/.env 现值；REDIS_PASSWORD 按迁移 SOP 新设。"
    echo "POSTGRES_USER=aiplatform"
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)"
    echo "REDIS_PASSWORD=$(openssl rand -hex 16)"
  } > .env
  log "已生成 .env（随机凭据）。全新机器到此为止；接管既有服务器务必手写现值替换！"
fi

# ⑤ 起栈＋健康等待（compose 对已存在且配置未变的容器不做任何事——幂等关键）
log "起栈（docker compose up -d）"
docker compose up -d

wait_healthy() {
  local c=$1 i
  for i in $(seq 1 40); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || true)" = healthy ] && return 0
    sleep 3
  done
  return 1
}
for c in "${CONTAINERS[@]}"; do
  if ! wait_healthy "$c"; then
    echo "错误：$c 未达 healthy。看日志：docker logs $c" >&2
    exit 1
  fi
done

log "三容器 healthy：${CONTAINERS[*]}"
printf -- '---- 收敛结果 ----\n'
docker ps --filter "name=^semgravio-(pg|redis|nginx)$" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
printf -- '网络：webnet（共享接入）＋ previewnet（平台沙箱）  数据卷：semgravio_pgdata\n'
printf -- '回环：PG 127.0.0.1:5432 ／ redis 127.0.0.1:6379（带密码）——SSH 隧道直连见 README\n'
printf -- '重跑本脚本安全：幂等收敛，不重建、不动数据。\n'
