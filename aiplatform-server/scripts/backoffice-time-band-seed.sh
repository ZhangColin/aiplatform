#!/usr/bin/env bash
# ============================================================================
# #497 时段规则种子幂等签名脚本：经后台管理 API「开行」端点种入 DeepSeek 峰窗
# 规则（ADR-0044 峰谷 1/4 初始化手段——写口唯一化到管理 API，种入即一单两窗口
# 行＝一个规则版本）。
#
# 数值口径（自 backoffice-price-seed.sh 头注同源自迁移）：2026-08-16 核对
# api-docs.deepseek.com/quick_start/pricing——该页 2026-08-16 起分 peak/off-peak
# 时段价（off-peak = peak 一半），peak 时段＝工作日（周一至周五）01:00–04:00、
# 06:00–10:00 UTC。ADR-0044 定案：读时归段选价，本表落时段定义、单价表带列
# 选价（本批姊妹票）。
#
# 生效起点统一 2026-08-16T00:00:00Z（DeepSeek 峰谷分价生效日，覆盖其后事件；
# 真实调窗史经管理 API 原子改窗维护）。band=1（峰）；谷不种行——读时归段
# 无窗口命中即补带归谷（引擎 #499：声明了峰窗→谷、只声明谷窗→峰）；通道无
# 生效规则行＝不看时段（NULL 带行照旧匹配，存量语义不破）。
#
# 幂等：通道（provider）已有任意规则行（含已关行）即跳过——重复执行零新插；
# 中断续跑收敛。既有行取自清单翻页全量（页大小 100，翻到取尽）。
#
# 用法：./scripts/backoffice-time-band-seed.sh
# 凭据：BACKOFFICE_API_KEY / BACKOFFICE_API_SECRET（或 .env.local /
#   app-registry bootstrap 现取，同 backoffice-price-seed.sh）；不传操作者头——
#   种入行操作者落空（同存量行口径，运营侧无「谁种的」歧义面）。
# ============================================================================
set -euo pipefail

source "$(dirname "$0")/backoffice-signed-http.sh"

EFFECTIVE_FROM="2026-08-16T00:00:00Z"
# provider：deepseek＝官方通道键（与 UsageEvent.provider / met_price_entries 同键）
PROVIDER="deepseek"
# 峰窗两窗口（一单两行行＝一个规则版本）：工作日（dayMask 31＝Mon..Fri bitmask）
# 01:00–04:00（60–240）＋06:00–10:00（360–600）UTC，band=1（峰）
WINDOWS_JSON='{"band":1,"dayMask":31,"startMinute":60,"endMinute":240},
{"band":1,"dayMask":31,"startMinute":360,"endMinute":600}'

# 该通道既有规则行数：≥1 即跳过（含已关版本）
existing_rows() {
  local page=1 resp status body
  while :; do
    resp=$(signed_call GET "/api/backoffice/time-band-rules?page=${page}&size=100")
    status="${resp##*$'\n'}"
    body="${resp%$'\n'*}"
    [[ "$status" == 2* ]] || { printf '时段规则清单读取失败（page %s）：\n%s\nHTTP %s\n' "$page" "$body" "$status" >&2; exit 1; }
    printf '%s' "$body" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for item in d["data"]["items"]:
    if item["provider"] == "'"$PROVIDER"'":
        print(item["id"])'
    local fetched
    fetched=$(printf '%s' "$body" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["data"]["items"]))')
    [[ "$fetched" -lt 100 ]] && break
    page=$((page + 1))
  done
}

open_rule() {
  # 开行（单通道一单两窗口行原子落）→ 种入成功打印回执行数，失败打印错误退出
  local resp status body body_file
  body_file=$(mktemp)
  python3 - "$PROVIDER" "$EFFECTIVE_FROM" "$WINDOWS_JSON" > "$body_file" <<'PY'
import sys
provider, effective_from, windows_json = sys.argv[1:4]
print('{"provider": "%s", "windows": [%s], "effectiveFrom": "%s"}'
      % (provider, windows_json, effective_from))
PY
  resp=$(signed_call POST "/api/backoffice/time-band-rules" "$body_file")
  rm -f "$body_file"
  status="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  if [[ "$status" != 2* ]]; then
    printf '时段规则开行失败（%s）：\n%s\nHTTP %s\n' "$PROVIDER" "$body" "$status" >&2
    exit 1
  fi
  printf '%s' "$body" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["data"]))'
}

main() {
  resolve_secret
  local existing inserted
  existing=$(existing_rows)

  if [[ -n "$existing" ]]; then
    printf '跳过 %s（通道已有 %s 行规则——含已关版本，幂等跳过；改窗走 rewindow 端点）\n' \
      "$PROVIDER" "$(grep -c . <<< "$existing")"
    inserted=0
  else
    local rows
    rows=$(open_rule)
    printf '种入 %s 峰窗规则＝工作日 01:00–04:00＋06:00–10:00 UTC（%s 窗口行，自 %s 起敞口）\n' \
      "$PROVIDER" "$rows" "$EFFECTIVE_FROM"
    inserted=1
  fi

  step "种子完成：新插 ${inserted} 版本 / 通道 ${PROVIDER}"
}

main "$@"
