#!/bin/bash
set -e

# ========== 配置区 ==========
SERVER_USER="root"
SERVER_HOST="43.140.211.9"
SERVER_PATH="/opt/hcy/aiplatform-web"
SERVER_PASSWORD="Hcy@20260327"

# ========== 逻辑区 ==========

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

SSH_CMD="sshpass -p $SERVER_PASSWORD ssh -o StrictHostKeyChecking=no"
RSYNC_CMD="sshpass -p $SERVER_PASSWORD rsync"

# 1. 检查 sshpass
if ! command -v sshpass &>/dev/null; then
  error "sshpass 未安装"
  echo "请执行: brew install hudochenkov/sshpass/sshpass"
  exit 1
fi

# 2. rsync 到服务器
info "正在上传文件到服务器..."
$RSYNC_CMD -avz -e "ssh -o StrictHostKeyChecking=no" \
  --exclude node_modules \
  --exclude .next \
  --exclude .git \
  --exclude docs \
  --exclude .claude \
  ./ ${SERVER_USER}@${SERVER_HOST}:${SERVER_PATH}/

# 3. SSH 执行部署
info "正在远程部署..."
$SSH_CMD ${SERVER_USER}@${SERVER_HOST} "cd ${SERVER_PATH} && chmod +x deploy.sh && bash deploy.sh"

info "部署完成！"
