#!/usr/bin/env bash
# ============================================================================
# 用户旅程冒烟：从用户视角走最短黄金路径——「一句话建项目 → 指令区等 BA 首问
# → 指令区发言 → 等回应」。任何一步超时/报错即红（exit 1），全程绿才 exit 0。
#
# 红的判据对应用户可感知症状，而非"没抛异常"：
#   - 创建后 WAIT 秒内无 question-raised  → 「进入对话界面没反应」
#   - 发言后 WAIT 秒内无新一轮智能体活动 → 「发消息半天没反应」
#
# SSE 通道带热缓冲回放（docs/spec/SSE事件清单.md），晚订阅也能看到已发帧，
# 因此断言直接 grep 捕获文件，不依赖订阅时机。
#
# 用法（从任意目录）：
#   ./scripts/user-journey.sh                 # 走完自动 DELETE 清理现场
#   WAIT=180 ./scripts/user-journey.sh        # 放宽等智能体回应的时限
#   CLEANUP=0 ./scripts/user-journey.sh       # 失败时保留项目供事后排查
#
# 前提：本服务（8888，带 SSO_*/DEEPSEEK_API_KEY 启动）与 identity 在跑；
# 凭据读仓库根 .env.local（gitignored）。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."   # → aiplatform-server/

BACKEND="${AIPLATFORM_BACKEND:-http://localhost:8888}"
JAR="${JAR:-/tmp/aiplatform-user-journey-jar}"
SSE_FILE="${SSE_FILE:-/tmp/aiplatform-user-journey-sse.txt}"
# 智能体一轮要走真实 LLM 调用（DeepSeek reasoning 轮实测 1~2 分钟）；失败路径由
# error 帧快速判红（秒级），长等待只发生在健康路径，不拖慢红的反馈
WAIT="${WAIT:-180}"
REQ="${REQ:-冒烟：给一家手工咖啡烘焙店做一个介绍官网，展示豆子产地和冲煮方法，访客可以留言}"
CLEANUP="${CLEANUP:-1}"

FAIL() { printf 'FAIL ✗ %s\n' "$*"; exit 1; }
PASS() { printf 'PASS ✓ %s\n' "$*"; }

# 等捕获文件出现匹配行（2s 轮询 grep，超时即红）
wait_for() { # $1=文件 $2=正则 $3=时限秒 $4=失败描述
  local deadline=$((SECONDS + $3))
  while (( SECONDS < deadline )); do
    grep -q "$2" "$1" 2>/dev/null && return 0
    sleep 2
  done
  return 1
}

# 等成功行；期间出现 error 帧即刻判红（智能体 error 对本旅程即终态，
# 干等满超时只会浪费几分钟——红要红得快）
wait_or_error() { # $1=文件 $2=成功正则 $3=时限秒
  local deadline=$((SECONDS + $3))
  while (( SECONDS < deadline )); do
    grep -q '"type":"error"' "$1" 2>/dev/null && return 2
    grep -q "$2" "$1" 2>/dev/null && return 0
    sleep 2
  done
  return 1
}

# 展示捕获到的平台帧类型序列（排 heartbeat，方便肉眼定位卡在哪一步）
frame_summary() {
  printf '  SSE 帧序列：'
  grep -o '"type":"[a-z-]*"' "$1" 2>/dev/null | sed 's/"type":"//;s/"//' | tr '\n' ' ' || true
  printf '\n'
}

# 0) 依赖在线
curl -sf -m 5 "$BACKEND/actuator/health" | grep -q '"UP"' || FAIL "后端不可达：$BACKEND/actuator/health"

# 1) 登录（真实 OIDC 授权码链，产出 cookie jar）
set -a; source ../.env.local; set +a
./scripts/login.sh "$JAR" >/tmp/aiplatform-user-journey-login.log 2>&1 \
  || { tail -5 /tmp/aiplatform-user-journey-login.log; FAIL "登录链失败（详见 /tmp/aiplatform-user-journey-login.log）"; }
PASS "登录 OK（OIDC 授权码全链）"

# 2) 一句话建项目（用户动作：首页输入需求点创建）
RESP=$(curl -sf -m 15 -b "$JAR" -H 'Content-Type: application/json' \
  -d "{\"requirement\":\"$REQ\"}" "$BACKEND/api/projects") \
  || FAIL "POST /api/projects 失败"
PID=$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["project"]["id"])')
RUN0=$(printf '%s' "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["runId"])')
PASS "创建项目 OK（projectId=$PID, 首个 BA run=${RUN0}）"

cleanup() {
  if [[ "$CLEANUP" == 1 && -n "${PID:-}" ]]; then
    curl -sf -m 30 -X DELETE -b "$JAR" "$BACKEND/api/projects/$PID" >/dev/null \
      && echo "（现场已清理：DELETE /api/projects/$PID 级联）" \
      || echo "（清理失败：项目 $PID 残留，可手动 DELETE）"
  fi
}
trap cleanup EXIT

# 3) 指令区等 BA 首问（用户感知：对话界面要"动起来"——出现问答卡）
: >"$SSE_FILE"
curl -sN -m $((WAIT + 10)) -b "$JAR" "$BACKEND/api/agent-events?projectId=$PID" >"$SSE_FILE" 2>/dev/null &
SSE_CURL=$!
RC=0; wait_or_error "$SSE_FILE" '"type":"question-raised"' "$WAIT" || RC=$?
if (( RC == 0 )); then
  PASS "BA 首问 OK（question-raised 在 ${WAIT}s 内出现）"
else
  frame_summary "$SSE_FILE"
  grep -q '"type":"error"' "$SSE_FILE" && \
    grep '"type":"error"' "$SSE_FILE" | tail -1 | sed 's/^.*"message":"/  error 帧：/;s/,"runId.*//'
  (( RC == 2 )) && FAIL "BA run 即死（见上方 error 帧）——对应症状「进入对话界面没反应」"
  FAIL "BA 首问 ${WAIT}s 未出现——对应症状「进入对话界面没反应」"
fi

# 4) 作答（用户动作：问答挂起时在输入框打字——与真实 UI 同通道，见
#    command-area.tsx「挂起中输入即作答」；engineRef 作 qid、toolCalls 原样回传）
QFRAME=$(grep '"type":"question-raised"' "$SSE_FILE" | tail -1 | sed 's/^data://')
ANSWER_JSON=$(printf '%s' "$QFRAME" | python3 -c '
import json, sys
f = json.load(sys.stdin)["payload"]
print(json.dumps({"runId": f["runId"], "toolCalls": f["data"]["toolCalls"],
                  "answer": "测试作答：以线上预约为主，到店少量walk-in，请继续"}))')
QID=$(printf '%s' "$QFRAME" | python3 -c 'import json,sys; print(json.load(sys.stdin)["payload"]["engineRef"])')
curl -sf -m 15 -b "$JAR" -H 'Content-Type: application/json' -d "$ANSWER_JSON" \
  "$BACKEND/api/projects/$PID/questions/$QID/answer" >/dev/null \
  || FAIL "POST /questions/$QID/answer 失败（400/409/5xx）"
PASS "作答已提交（qid=${QID}）"

# 5) 等续跑回应：resume 续在同一 run 收口（无新 run-start），判据 = 作答后
#    出现下一张问答卡（每轮一问）或 run 收口帧。只看作答落点之后新增的行。
MSG_MARK=$(wc -l <"$SSE_FILE")
reply_seen=1
deadline=$((SECONDS + WAIT))
while (( SECONDS < deadline )); do
  if sed -n "$((MSG_MARK + 1)),\$p" "$SSE_FILE" 2>/dev/null | grep -q '"type":"question-raised"\|"type":"run-finish"'; then
    reply_seen=0; break
  fi
  sleep 2
done
if (( reply_seen == 0 )); then
  PASS "作答续跑 OK（作答后出现下一问或收口）"
else
  frame_summary "$SSE_FILE"
  FAIL "作答后 ${WAIT}s 无续跑活动——对应症状「发消息半天没反应」"
fi

kill $SSE_CURL 2>/dev/null || true
printf '\n用户旅程全绿 ✓（创建 → BA 首问 → 发言 → 回应）\n'
