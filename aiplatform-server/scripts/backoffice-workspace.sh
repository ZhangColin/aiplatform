#!/usr/bin/env bash
# ============================================================================
# #175 资源面联调脚本：后台沙箱观测（#173）＋四干预动作（#174）＋项目文件包。
# 前端无任何后台操作入口——后台动作一律经签名脚本（#13 spec 口径）；本脚本照
# backoffice-quote.sh 形制（签名/凭据/展示公共件共用 backoffice-signed-http.sh）。
#
# 用法：
#   ./scripts/backoffice-workspace.sh list [desired] [actual] [page] [size]
#       沙箱清单（desired：1=运行 2=休眠 3=封存；actual：1=运行中 2=已停止
#       3=无容器 4=未知；均可缺省=不过滤；actual=3 即捞漂移清单）
#   ./scripts/backoffice-workspace.sh detail <workspaceId>
#       沙箱详情（全量字段：期望态/实态/卷大小/封存信息/中间件清单）
#   ./scripts/backoffice-workspace.sh ws-of <projectId>
#       项目 → 工作区标识（排障互查锚点：四动作都要 workspaceId）
#   ./scripts/backoffice-workspace.sh wake <workspaceId>
#       唤醒（等就绪；封存态=深度唤醒，分钟级，脚本同步等结果）
#   四动作可带动作留痕（#174 append-only）：OPERATOR_ID=123 OPERATOR_NAME=张三
#       ./scripts/backoffice-workspace.sh hibernate <wsId>（→ X-User-Id/X-User-Name
#       透传头落痕动作行；缺省不带＝落空口径）
#   ./scripts/backoffice-workspace.sh hibernate <workspaceId>
#       强制休眠（立即删容器保卷；已休眠幂等）
#   ./scripts/backoffice-workspace.sh rebuild <workspaceId>
#       强制重建（rm＋幂等重建；#168 型事故标准化处置）
#   ./scripts/backoffice-workspace.sh seal <workspaceId>
#       封存（删容器→整卷打包落盘→删卷；RUNNING 起点也可）
#   ./scripts/backoffice-workspace.sh package <projectId> [输出文件]
#       项目文件包（已封存=封存包 {id}-archive.tar.gz；未封存=即时导出源码包
#       {id}-source.tar.gz，休眠中会先同步唤醒；缺省落 /tmp/）
#
# 动作端点同步执行（唤醒/封存可能分钟级——深度唤醒含解包+依赖重装）；
# run 在途拒 409 WSP_015（1015）、状态边界 400 WSP_009（1009），信封 code
# 为数字业务码（#169）。
# ============================================================================
set -euo pipefail

source "$(dirname "$0")/backoffice-signed-http.sh"

require_ws_id() {
  [[ "${1:-}" =~ ^[0-9]+$ ]] || { echo "用法：$0 $2 <workspaceId>（TSID 十进制数字）" >&2; exit 2; }
}
require_project_id() {
  [[ "${1:-}" =~ ^[0-9]+$ ]] || { echo "用法：$0 $2 <projectId>（TSID 十进制数字）" >&2; exit 2; }
}

# ---------------------------------------------------------------------------
cmd_list() {
  local desired="${1:-}" actual="${2:-}" page="${3:-1}" size="${4:-20}"
  local query="?page=${page}&size=${size}"
  [[ -n "$desired" ]] && query="${query}&desired=${desired}"
  [[ -n "$actual" ]] && query="${query}&actual=${actual}"
  show_response "$(signed_call GET "/api/backoffice/workspaces${query}")"
}

cmd_detail() {
  require_ws_id "$1" detail
  show_response "$(signed_call GET "/api/backoffice/workspaces/$1")"
}

cmd_ws_of() {
  require_project_id "$1" ws-of
  local resp
  resp=$(signed_call GET "/api/backoffice/projects/$1")
  local status="${resp##*$'\n'}"
  [[ "$status" == 2* ]] || { show_response "$resp"; exit 1; }
  python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["data"]["workspaceId"])' \
    <<< "${resp%$'\n'*}"
}

# 四动作共用：POST /{id}/<verb> → 展示动作后的观测详情（新事实）。
# 动作留痕（#174 append-only）：OPERATOR_ID / OPERATOR_NAME 环境变量 →
# X-User-Id / X-User-Name 透传头（签名只覆盖五头＋query，透传头不参与 HMAC，
# 直接追加即可；缺省不带＝动作行落空，同先例口径）
cmd_action() {
  local verb="$1"; shift
  require_ws_id "$1" "$verb"
  local path="/api/backoffice/workspaces/$1/$verb"
  # ${path}（ 必须花括号：macOS bash 3.2 把紧跟全角字符的变量名吞前导字节（set -u 报 unbound）
  step "POST ${path}（同步等结果，深度唤醒分钟级）"
  local extra=()
  [[ -n "${OPERATOR_ID:-}" ]] && extra+=(-H "X-User-Id: $OPERATOR_ID")
  [[ -n "${OPERATOR_NAME:-}" ]] && extra+=(-H "X-User-Name: $OPERATOR_NAME")
  if (( ${#extra[@]} )); then
    # 第三参是 body_file 约定（同 signed_call）：动作 POST 无 body，显式空串占位，
    # 额外头从第四参起（首版漏占位，$3 吞掉首对 -H → curl --data-binary @-H）
    show_response "$(signed_call_extra POST "$path" "" "${extra[@]}")"
  else
    show_response "$(signed_call POST "$path")"
  fi
}

cmd_package() {
  require_project_id "$1" package
  local out="${2:-/tmp/$1-$(date +%Y%m%d%H%M%S).tar.gz}"
  # projectId 先落 local——下方 set -- 装头循环会清位置参数（quote.sh cmd_package 同因）
  local project_id="$1"
  step "GET /api/backoffice/projects/$project_id/files/package → ${out}（休眠中会先同步唤醒）"

  local headers line header_file
  headers=$(sign_headers "$BACKOFFICE_API_SECRET" "/api/backoffice/projects/$project_id/files/package")
  set --
  while IFS= read -r line; do
    [[ -n "$line" ]] && set -- "$@" -H "$line"
  done <<< "$headers"

  header_file=$(mktemp)
  local http_code
  http_code=$(curl -sS -D "$header_file" -o "$out" -w '%{http_code}' \
    "$@" "$BACKEND/api/backoffice/projects/$project_id/files/package")
  if [[ "$http_code" == 2* ]]; then
    local bytes gzip_ok
    bytes=$(wc -c < "$out" | tr -d ' ')
    gzip_ok=$(gzip -t "$out" 2>/dev/null && echo gzip-ok || echo NOT-GZIP)
    printf 'HTTP %s → %s（%s 字节，%s）\n' "$http_code" "$out" "$bytes" "$gzip_ok"
    grep -i 'content-disposition' "$header_file" || true
    rm -f "$header_file"
  else
    echo "HTTP ${http_code}（错误信封如下；1016=封存包不可读、1015=run 在途拒）"
    cat "$out"; rm -f "$out" "$header_file"
    exit 1
  fi
}

cmd="${1:-}"
[[ -n "$cmd" ]] || { sed -n '2,30p' "$0"; exit 2; }
shift || true
resolve_secret
case "$cmd" in
  list)      cmd_list "$@" ;;
  detail)    cmd_detail "$@" ;;
  ws-of)     cmd_ws_of "$@" ;;
  wake)      cmd_action wake "$@" ;;
  hibernate) cmd_action hibernate "$@" ;;
  rebuild)   cmd_action rebuild "$@" ;;
  seal)      cmd_action seal "$@" ;;
  package)   cmd_package "$@" ;;
  *) echo "用法：$0 {list|detail|ws-of|wake|hibernate|rebuild|seal|package} …（详头部注释）" >&2
     exit 2 ;;
esac
