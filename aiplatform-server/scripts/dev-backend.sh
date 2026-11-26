#!/usr/bin/env bash
# 后端一键启动（本地开发启动.md §3 的固化）：
# .env.local 是裸 KEY=VALUE，必须 set -a 导出后 Spring 才读得到（症状：sso.client-id 空）。
# 用法：./scripts/dev-backend.sh（在 aiplatform-server/ 下，或任意目录均可——脚本自 cd）
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env.local ]; then
  echo "缺 aiplatform-server/.env.local（SSO 七项 + DEEPSEEK_API_KEY + BOCHA_API_KEY + OPENAPI_APP_*，gitignored）" >&2
  exit 1
fi

set -a
source .env.local
set +a

exec mvn spring-boot:run
