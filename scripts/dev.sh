#!/usr/bin/env bash
# 前后端一键拉起（本地开发）：本仓后端（8888）与前端（3333）拉起到健康——没在跑
# 就直接起（停旧段空跑），已在跑则先停再起（换新代码）。附健康探测。
#
# 进程锚定只认本仓路径与端口（aiplatform-server/target/classes、aiplatform-web/…next dev），
# 绝不宽匹配——同机还跑着 aieducenter-identity-web 等其他 next dev，pkill -f "next dev"
# 会误伤。identity-web 本脚本不管，仅在结尾探测展示（SSO 依赖它，顺手看一眼）。
#
# 用法（仓库任意位置）：scripts/dev.sh
#   后端启动复用 aiplatform-server/scripts/dev-backend.sh（.env.local 导出逻辑在那边）
# 日志：/tmp/aiplatform-backend.log、/tmp/aiplatform-web.log（每次执行覆盖）
set -euo pipefail
cd "$(dirname "$0")/.."

BACKEND_PORT=8888
FRONTEND_PORT=3333
IDENTITY_PORT=10002
BACKEND_HEALTH="http://localhost:${BACKEND_PORT}/actuator/health"

port_pids() { lsof -ti ":$1" 2>/dev/null || true; }

http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$1" 2>/dev/null || echo 000; }

echo "== 停旧（本仓路径/端口锚定） =="
# 候选 = 端口占用者 + 本仓路径匹配；后端 mvn 父进程（classworlds）一并停
pids="$(port_pids ${BACKEND_PORT}) $(port_pids ${FRONTEND_PORT})"
pids="$pids $(pgrep -f 'aiplatform-server/target/classes' || true)"
pids="$pids $(pgrep -f 'aiplatform-web/.*next dev' || true)"
for pid in $pids; do
  ppid=$(ps -o ppid= -p "$pid" 2>/dev/null | tr -d ' ' || true)
  if [ -n "${ppid:-}" ] && ps -o command= -p "$ppid" 2>/dev/null | grep -q plexus-classworlds; then
    kill "$ppid" 2>/dev/null || true   # mvn 父随子停，不留孤儿等待
  fi
  kill "$pid" 2>/dev/null || true
done
# 等端口释放（最多 10s），滞留者 SIGKILL
for i in 1 2 3 4 5 6 7 8 9 10; do
  [ -z "$(port_pids ${BACKEND_PORT})" ] && [ -z "$(port_pids ${FRONTEND_PORT})" ] && break
  sleep 1
done
for stale in $(port_pids ${BACKEND_PORT}) $(port_pids ${FRONTEND_PORT}); do
  kill -9 "$stale" 2>/dev/null || true
done

echo "== 起新 =="
nohup aiplatform-server/scripts/dev-backend.sh > /tmp/aiplatform-backend.log 2>&1 &
( cd aiplatform-web && nohup npm run dev > /tmp/aiplatform-web.log 2>&1 & )

echo "== 健康探测（后端最长 120s / 前端 60s） ="
backend=000; frontend=000
for i in $(seq 1 60); do
  backend=$(http_code "${BACKEND_HEALTH}")
  frontend=$(http_code "http://localhost:${FRONTEND_PORT}/")
  [ "$backend" = 200 ] && [ "$frontend" != 000 ] && break
  sleep 2
done

echo "后端   :${BACKEND_PORT}  ${backend}  $([ "$backend" = 200 ] && echo OK || echo 未就绪)"
echo "前端   :${FRONTEND_PORT}  ${frontend}  $([ "$frontend" != 000 ] && echo OK || echo 未就绪)"
echo "identity:${IDENTITY_PORT}  $(http_code "http://localhost:${IDENTITY_PORT}/")（信息项，本脚本不管它）"
echo "日志：/tmp/aiplatform-backend.log /tmp/aiplatform-web.log"

[ "$backend" = 200 ] && [ "$frontend" != 000 ]
