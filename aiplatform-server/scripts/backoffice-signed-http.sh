#!/usr/bin/env bash
# ============================================================================
# 后台机机面（/api/backoffice/**）签名 HTTP 公共件：供 backoffice-quote.sh /
# backoffice-price-seed.sh source，不直接执行。cartisan-openapi 五头 HMAC
# （X-Api-Key/X-Timestamp/X-Nonce/X-Body-Digest/X-Sign）。
#
# 凭据：BACKOFFICE_API_KEY / BACKOFFICE_API_SECRET 环境变量（或 aiplatform-server/
# .env.local / cwd .env.local，不进仓库）。key 缺省 admin-console（app-registry
# 启动 seed 的预置应用）；secret 未提供时从本机 app-registry bootstrap 端点现取
# （网络边界即信任边界）。
# ============================================================================
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  echo "本文件是签名公共件，由运营脚本 source（见 backoffice-quote.sh），不直接执行" >&2
  exit 2
fi

BACKEND="${AIPLATFORM_BACKEND:-http://localhost:8888}"
APP_REGISTRY="${APP_REGISTRY:-http://localhost:8088}"
API_KEY="${BACKOFFICE_API_KEY:-admin-console}"

# secret 缺省链：环境变量 → cwd .env.local → 脚本同仓 .env.local
if [[ -z "${BACKOFFICE_API_SECRET:-}" ]]; then
  if [[ -f .env.local ]]; then
    set -a; source .env.local; set +a
  elif [[ -f "$(dirname "${BASH_SOURCE[0]}")/../.env.local" ]]; then
    set -a; source "$(dirname "${BASH_SOURCE[0]}")/../.env.local"; set +a
  fi
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

# 调用后台端点。参数：method pathWithQuery bodyFile [secretOverride tsOverride]
# 输出：body\nstatus（status 非 2xx 由调用方裁决）。头参数经 set -- 组装为位置
# 参数（bash 3.2 无空数组展开，positional 即可；引号由 "$@" 整体保留）。
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
