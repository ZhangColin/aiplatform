#!/usr/bin/env bash
# ============================================================================
# #29 后台机机面联调脚本：模拟后台报价/改价走 /api/backoffice/* 四端点
# （cartisan-openapi 五头 HMAC：X-Api-Key/X-Timestamp/X-Nonce/X-Body-Digest/
# X-Sign）。前端无任何后台操作入口——后台动作一律经本脚本（#13 spec 口径）。
#
# 用法：
#   ./scripts/backoffice-quote.sh list [status] [page] [size]
#       订单清单（status：1=待报价 2=已报价 3=已支付 4=已归档 5=已取消；缺省全部；
#       page 1 基缺省 1，size 缺省 20）
#   ./scripts/backoffice-quote.sh detail <orderId>
#       订单详情（PRD 快照正文、项目名、用户昵称、金额+最新备注、状态时点组）
#   ./scripts/backoffice-quote.sh quote <orderId> <amount分> [note...]
#       提交报价（amount 单位分）；已报价态重复调用 = 改价（append-only 留痕）
#   ./scripts/backoffice-quote.sh package <orderId> [输出文件]
#       源码包 tar.gz（排除 node_modules/.env 等；缺省落 /tmp/<orderId>-source.tar.gz）
#   ./scripts/backoffice-quote.sh reject-demo
#       拒签演示：无签名 / 错签 / 过期时间戳 → 逐一 401
#
# 凭据：BACKOFFICE_API_KEY / BACKOFFICE_API_SECRET 环境变量（或仓库根 .env.local，
# 不进仓库）。key 缺省 admin-console（app-registry 启动 seed 的预置应用）；secret
# 未提供时从本机 app-registry bootstrap 端点现取（网络边界即信任边界，本机依赖
# 启动见 docs/guide/本机依赖启动.md）。
# ============================================================================
set -euo pipefail

BACKEND="${AIPLATFORM_BACKEND:-http://localhost:8888}"
APP_REGISTRY="${APP_REGISTRY:-http://localhost:8088}"
API_KEY="${BACKOFFICE_API_KEY:-admin-console}"

if [[ -z "${BACKOFFICE_API_SECRET:-}" ]] && [[ -f .env.local ]]; then
  set -a; source .env.local; set +a
fi

step() { printf '\n==> %s\n' "$*"; }

# ---------------------------------------------------------------------------
# 签名（python3：SHA-256 body 摘要 + HMAC-SHA256，stringToSign 与
# SignatureVerificationFilter 同构——键名字典序 k=v&…，query 参数计入）。
# 参数：secret pathWithQuery bodyFile timestampOverride
# 输出：五行 "Header: value"
# ---------------------------------------------------------------------------
sign_headers() {
  local secret="$1" path_with_query="$2" body_file="${3:-}" ts_override="${4:-}"
  python3 - "$API_KEY" "$secret" "$path_with_query" "$body_file" "$ts_override" <<'PY'
import hashlib, hmac, sys, time, uuid

api_key, secret, path_with_query, body_file, ts_override = sys.argv[1:6]
ts = int(ts_override or time.time())
nonce = uuid.uuid4().hex
body = open(body_file, "rb").read() if body_file else b""
digest = hashlib.sha256(body).hexdigest()

params = {"apiKey": api_key, "bodyDigest": digest, "nonce": nonce, "timestamp": str(ts)}
q = path_with_query.find("?")
if q >= 0:
    for pair in path_with_query[q + 1:].split("&"):
        kv = pair.split("=", 2)
        if len(kv) == 2:
            params[kv[0]] = kv[1]
string_to_sign = "&".join(f"{k}={v}" for k, v in sorted(params.items()))
sign = hmac.new(secret.encode(), string_to_sign.encode(), hashlib.sha256).hexdigest()
for k, v in [("X-Api-Key", api_key), ("X-Timestamp", str(ts)), ("X-Nonce", nonce),
             ("X-Body-Digest", digest), ("X-Sign", sign)]:
    print(f"{k}: {v}")
PY
}

# 调用四端点之一。参数：method pathWithQuery bodyFile [secretOverride tsOverride]
# 头参数经 set -- 组装为位置参数（bash 3.2 无空数组展开，positional 即可；引号
# 由 "$@" 整体保留）。
signed_call() {
  local method="$1" path_with_query="$2" body_file="${3:-}" \
        secret="${4:-$BACKOFFICE_API_SECRET}" ts_override="${5:-}"
  local headers line
  headers=$(sign_headers "$secret" "$path_with_query" "$body_file" "$ts_override")

  set --
  while IFS= read -r line; do
    [[ -n "$line" ]] && set -- "$@" -H "$line"
  done <<< "$headers"

  if [[ -n "$body_file" ]]; then
    set -- "$@" --data-binary "@$body_file" -H "Content-Type: application/json"
  fi

  curl -sS -w '\n%{http_code}' -X "$method" "$@" "$BACKEND$path_with_query"
}

show_response() {
  # body\nstatus → 分离展示（状态非 2xx 时高亮）
  local resp="$1"
  local status="${resp##*$'\n'}"
  local body="${resp%$'\n'*}"
  printf '%s\n' "$body" | python3 -m json.tool 2>/dev/null || printf '%s\n' "$body"
  printf 'HTTP %s\n' "$status"
  [[ "$status" == 2* ]]
}

resolve_secret() {
  if [[ -n "${BACKOFFICE_API_SECRET:-}" ]]; then
    return
  fi
  step "未设 BACKOFFICE_API_SECRET，从本机 app-registry bootstrap 端点现取（key=${API_KEY}）"
  BACKOFFICE_API_SECRET=$(curl -sS "$APP_REGISTRY/api/app-registry/api-keys/${API_KEY}" \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["apiSecret"])')
  : "${BACKOFFICE_API_SECRET:?取不到 apiSecret（app-registry 未启动？或显式设 BACKOFFICE_API_SECRET）}"
}

require_order_id() {
  [[ "${1:-}" =~ ^[0-9]+$ ]] || { echo "用法：$0 $2 <orderId>（TSID 十进制数字）" >&2; exit 2; }
}

# ---------------------------------------------------------------------------
cmd_list() {
  local status="${1:-}" page="${2:-1}" size="${3:-20}"
  local query="?page=${page}&size=${size}"
  [[ -n "$status" ]] && query="?status=${status}&page=${page}&size=${size}"
  show_response "$(signed_call GET "/api/backoffice/orders${query}")"
}

cmd_detail() {
  require_order_id "$1" detail
  show_response "$(signed_call GET "/api/backoffice/orders/$1")"
}

cmd_quote() {
  require_order_id "$1" quote
  local amount="$2"
  [[ "$amount" =~ ^[0-9]+$ ]] || { echo "用法：$0 quote <orderId> <amount分> [note...]" >&2; exit 2; }
  local note="${3:-}"
  local body
  body=$(python3 -c 'import json,sys; print(json.dumps({"amount": int(sys.argv[1]), "note": sys.argv[2] or None}, ensure_ascii=False))' \
    "$amount" "$note")
  local body_file
  body_file=$(mktemp)
  printf '%s' "$body" > "$body_file"
  show_response "$(signed_call POST "/api/backoffice/orders/$1/quote" "$body_file")"
  rm -f "$body_file"
}

cmd_package() {
  require_order_id "$1" package
  # orderId 与输出路径先落 local——下方 set -- 复用位置参数装 curl 头，$1 会被清掉
  local oid="$1" out="${2:-/tmp/$1-source.tar.gz}"
  local headers line
  headers=$(sign_headers "$BACKOFFICE_API_SECRET" "/api/backoffice/orders/$oid/source-package" "")

  set --
  while IFS= read -r line; do
    [[ -n "$line" ]] && set -- "$@" -H "$line"
  done <<< "$headers"

  curl -sS "$@" -o "$out" -w "HTTP %{http_code} → ${out}（%{size_download} 字节）\n" \
    "$BACKEND/api/backoffice/orders/$oid/source-package"
}

cmd_reject_demo() {
  step "① 无签名（裸请求）→ 预期 401 Signature required"
  curl -sS -w '\nHTTP %{http_code}\n' "$BACKEND/api/backoffice/orders"

  step "② 错签（错 secret）→ 预期 401 Signature mismatch"
  show_response "$(signed_call GET "/api/backoffice/orders" /dev/null "wrong-secret-at-all")" \
    || true

  step "③ 过期时间戳（1 小时前）→ 预期 401 Timestamp expired"
  show_response "$(signed_call GET "/api/backoffice/orders" /dev/null "" "$(( $(date +%s) - 3600 ))")" \
    || true
}

main() {
  local cmd="${1:-}"
  [[ -n "$cmd" ]] || { sed -n '2,30p' "$0"; exit 2; }
  if [[ "$cmd" == "reject-demo" ]]; then
    resolve_secret
    cmd_reject_demo
    return
  fi
  resolve_secret
  case "$cmd" in
    list)    cmd_list "${2:-}" "${3:-1}" "${4:-20}" ;;
    detail)  cmd_detail "${2:-}" ;;
    quote)
      require_order_id "${2:-}" quote
      local oid="${2:-}" amount="${3:-}"
      shift 3
      cmd_quote "$oid" "$amount" "$*"
      ;;
    package) cmd_package "${2:-}" "${3:-}" ;;
    *)       echo "未知子命令：$cmd（list / detail / quote / package / reject-demo）" >&2; exit 2 ;;
  esac
}

main "$@"
