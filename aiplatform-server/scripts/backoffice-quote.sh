#!/usr/bin/env bash
# ============================================================================
# #29 后台机机面联调脚本：模拟后台报价/改价走 /api/backoffice/* 四端点。
# 前端无任何后台操作入口——后台动作一律经签名脚本（#13 spec 口径）。
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
# 签名/凭据/展示公共件在 backoffice-signed-http.sh（与单价种子脚本共用一套
# 签名实现，防双份漂移）。
# ============================================================================
set -euo pipefail

source "$(dirname "$0")/backoffice-signed-http.sh"

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
