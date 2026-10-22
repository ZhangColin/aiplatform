#!/usr/bin/env bash
# ============================================================================
# #367 模型供应商/档位后台维护面·机机签名 curl 演示与操作脚本（spec #364-T3，
# ADR-0031）。两件事：
#   1. demo —— 一次性全流程演示（建通道 → 建档位 → 改档位 → 读回掩码 → 留痕 →
#      绑定即时校验负例 → 清场），全部走 t-demo* 可弃键，跑完即删，对 dev 配置
#      零副作用——验收票 #367 AC「机机签名 curl 全流程可 demo」的执行面；
#   2. 单动词 —— 真实运维操作（list/get/create-channel/rotate-key/set-channel/
#      create-tier/set-tier/traces-*/delete-*），动的是真配置行。
#
# apiKey 安全口径：请求体可携全量 key（写面唯一明文入口），回执与留痕只出掩码
# （尾 4 位）；本脚本打印的响应体经 show_response 原样展示——掩码形即全部可见
# 面，明文不回显（库内才是正本）。rotate-key 从 GET 读现 baseUrl 组 PUT 体
# （apiKey null＝保持语义不碰 key 之外的值）。
#
# 用法：./scripts/backoffice-model-channel.sh [demo|list|get-channel <key>|...]
# 凭据：BACKOFFICE_API_KEY / BACKOFFICE_API_SECRET（或 .env.local /
#   app-registry bootstrap 现取，同 backoffice-quote.sh）；写操作带操作者头
#   （OP_ID/OP_NAME 环境变量，缺省 demo 操作者——留痕必携）。
# ============================================================================
set -euo pipefail

source "$(dirname "$0")/backoffice-signed-http.sh"

OP_ID="${OP_ID:-700299}"
OP_NAME="${OP_NAME:-运营·通道demo}"
PROVIDERS_PATH="/api/backoffice/model-providers"
TIERS_PATH="/api/backoffice/model-tiers"

body_file() { mktemp -t modelchannel; }

# 临时体文件 + 内容打印与调用（method path bodyJson [extra headers...]）
call_json() {
  local method="$1" path="$2" body="$3"; shift 3
  local bf; bf=$(mktemp -t modelchannel)
  printf '%s' "$body" > "$bf"
  local resp
  resp=$(signed_call_extra "$method" "$path" "$bf" "X-User-Id: ${OP_ID}" "X-User-Name: ${OP_NAME}")
  rm -f "$bf"
  printf '%s' "$resp"
}

call_get() {
  local path="$1" resp
  resp=$(signed_call GET "$path" "")
  printf '%s' "$resp"
}

show() { show_response "$1"; }

require_ok() {
  local resp="$1" status; status="${resp##*$'\n'}"
  if [[ "$status" != 2* ]]; then
    echo "!! 非预期状态 HTTP $status（见上）" >&2; exit 1
  fi
}

mask_note() { printf '\n（apiKey 回执只出掩码——明文仅库内正本，全链无泄漏面）\n'; }

# ---------------------------------------------------------------------------
# demo：全流程（可弃键 t-demo-channel/t-demo-tier，跑完清场）
# ---------------------------------------------------------------------------
demo() {
  step "0. 通道清单（种子行——apiKey 一律掩码）"
  show "$(call_get "$PROVIDERS_PATH")"
  mask_note

  step "1. 建通道 t-demo-channel（apiKey 收全量值，回执即掩码）"
  show "$(call_json POST "$PROVIDERS_PATH" \
    '{"providerKey":"t-demo-channel","baseUrl":"https://demo.example/api/v3","apiKey":"sk-demo-full-value-0001","enabled":true}')"
  mask_note

  step "2. 建档位 t-demo-tier（挂新通道；建行即可被绑定）"
  show "$(call_json POST "$TIERS_PATH" \
    '{"tierKey":"t-demo-tier","providerKey":"t-demo-channel","modelName":"demo-model-2601","displayName":"演示档"}')"

  step "3. 改档位（升模型版本＝改一处指向，显示名随改）"
  show "$(call_json PUT "$TIERS_PATH/t-demo-tier" \
    '{"providerKey":"t-demo-channel","modelName":"demo-model-2602","displayName":"演示档 v2"}')"

  step "4. 读回掩码（通道 GET——key 只出尾 4 位）"
  show "$(call_get "$PROVIDERS_PATH/t-demo-channel")"
  mask_note

  step "5. 换 key（PUT 全量新值；null/掩码回显形＝保持现值）"
  show "$(call_json PUT "$PROVIDERS_PATH/t-demo-channel" \
    '{"baseUrl":"https://demo.example/api/v3","apiKey":"sk-demo-full-value-0002"}')"
  mask_note

  step "6. 留痕读面（前后快照＋操作者；key 快照亦掩码）"
  show "$(call_get "$PROVIDERS_PATH/t-demo-channel/traces")"
  show "$(call_get "$TIERS_PATH/t-demo-tier/traces")"

  step "7. 绑定即时校验负例（写不存在的档位键——400 PRJ_074，零写入）"
  show "$(call_json PUT "/api/backoffice/agent-configs/executor" '{"modelId":"t-no-such-tier"}')"

  step "8. 绑定合法新档（即时校验放行——演示档生效）"
  show "$(call_json PUT "/api/backoffice/agent-configs/executor" '{"modelId":"t-demo-tier"}')"

  step "9. 删除守卫（档位被绑定——409 PRJ_073）"
  show "$(call_json DELETE "$TIERS_PATH/t-demo-tier" '{}')"

  step "10. 清场（解绑 → 删档位 → 删通道）"
  show "$(call_json PUT "/api/backoffice/agent-configs/executor" '{}')"
  show "$(call_json DELETE "$TIERS_PATH/t-demo-tier" '{}')"
  show "$(call_json DELETE "$PROVIDERS_PATH/t-demo-channel" '{}')"
  step "demo 完（服务端日志同步可查：变更行 [model-channel] 均掩码，无明文 key）"
}

# ---------------------------------------------------------------------------
# 单动词（真实运维操作）
# ---------------------------------------------------------------------------
list() { show "$(call_get "$PROVIDERS_PATH")"; show "$(call_get "$TIERS_PATH")"; }

get_channel() { show "$(call_get "$PROVIDERS_PATH/$1")"; }
get_tier() { show "$(call_get "$TIERS_PATH/$1")"; }

create_channel() { # <key> <baseUrl> [apiKey]
  local body="{\"providerKey\":\"$1\",\"baseUrl\":\"$2\""
  [[ -n "${3:-}" ]] && body="$body,\"apiKey\":\"$3\""
  show "$(call_json POST "$PROVIDERS_PATH" "$body}")"
}

rotate_key() { # <key> <newApiKey>（现 baseUrl 从 GET 取，apiKey null 语义不碰其余值）
  local current; current=$(call_get "$PROVIDERS_PATH/$1")
  require_ok "$current"
  local base_url; base_url=$(printf '%s' "${current%$'\n'*}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["baseUrl"])')
  show "$(call_json PUT "$PROVIDERS_PATH/$1" \
    "{\"baseUrl\":\"${base_url}\",\"apiKey\":\"$2\"}")"
  mask_note
}

set_channel() { # <key> <baseUrl> [enabled=true|false]（不携 apiKey＝保持现值）
  local body="{\"baseUrl\":\"$2\""
  [[ -n "${3:-}" ]] && body="$body,\"enabled\":$3"
  show "$(call_json PUT "$PROVIDERS_PATH/$1" "$body}")"
}

create_tier() { # <tierKey> <providerKey> <modelName> [displayName]
  local body="{\"tierKey\":\"$1\",\"providerKey\":\"$2\",\"modelName\":\"$3\""
  [[ -n "${4:-}" ]] && body="$body,\"displayName\":\"$4\""
  show "$(call_json POST "$TIERS_PATH" "$body}")"
}

set_tier() { # <tierKey> <providerKey> <modelName> [displayName] [enabled]
  local body="{\"providerKey\":\"$2\",\"modelName\":\"$3\""
  [[ -n "${4:-}" ]] && body="$body,\"displayName\":\"$4\""
  [[ -n "${5:-}" ]] && body="$body,\"enabled\":$5"
  show "$(call_json PUT "$TIERS_PATH/$1" "$body}")"
}

traces_channel() { show "$(call_get "$PROVIDERS_PATH/$1/traces")"; }
traces_tier() { show "$(call_get "$TIERS_PATH/$1/traces")"; }

delete_channel() { show "$(call_json DELETE "$PROVIDERS_PATH/$1" '{}')"; }
delete_tier() { show "$(call_json DELETE "$TIERS_PATH/$1" '{}')"; }

usage() {
  cat <<'USAGE'
用法：backoffice-model-channel.sh <动词> [参数…]
  demo                                    全流程演示（t-demo* 可弃键，自动清场）
  list                                    通道＋档位清单（掩码形）
  get-channel <key> / get-tier <key>      单行读面
  create-channel <key> <baseUrl> [key]    建通道（apiKey 全量值；缺＝暂不配置）
  rotate-key <key> <newApiKey>            换 key（现 baseUrl 自动携带）
  set-channel <key> <baseUrl> [enabled]   改 baseUrl/停启用（apiKey 保持）
  create-tier <tierKey> <providerKey> <modelName> [displayName]
  set-tier <tierKey> <providerKey> <modelName> [displayName] [enabled]
  traces-channel <key> / traces-tier <key> 留痕读面（回滚依据）
  delete-channel <key> / delete-tier <key> 删除（被引用即 409）
环境：BACKOFFICE_API_KEY/SECRET（同 backoffice-quote.sh）、OP_ID/OP_NAME（写留痕）
USAGE
}

resolve_secret
case "${1:-}" in
  demo) demo ;;
  list) list ;;
  get-channel) get_channel "$2" ;;
  get-tier) get_tier "$2" ;;
  create-channel) shift; create_channel "$@" ;;
  rotate-key) shift; rotate_key "$@" ;;
  set-channel) shift; set_channel "$@" ;;
  create-tier) shift; create_tier "$@" ;;
  set-tier) shift; set_tier "$@" ;;
  traces-channel) traces_channel "$2" ;;
  traces-tier) traces_tier "$2" ;;
  delete-channel) delete_channel "$2" ;;
  delete-tier) delete_tier "$2" ;;
  *) usage ;;
esac
