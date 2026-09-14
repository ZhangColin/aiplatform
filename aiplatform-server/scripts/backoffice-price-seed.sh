#!/usr/bin/env bash
# ============================================================================
# #165 单价表种子幂等签名脚本：经后台管理 API「开行」端点种入六行种子。
# 启动 Seeder 已退役（写口唯一化到管理 API）——本脚本是单价表唯一初始化手段；
# 忘初始化的护栏＝unpriced 全局警示读面（用量驱动，自然暴露漏配价）。
#
# 数值口径（自 PriceEntrySeeder 原样迁移，A6 §1 纪律：以官方定价页实时值为准，
# 不抄研究稿）：2026-08-22 核对 api-docs.deepseek.com/quick_start/pricing——该页
# 2026-08-16 起分 peak/off-peak 时段价（off-peak = peak 一半，peak 时段
# 01:00–04:00、06:00–10:00 UTC）；单价表无时段维度，种子取 peak 档（上限口径）：
# 成本可见性宁高勿低，精确到时段的换算是单价表演化，现在不预设。
# 现役模型 = demo 配置档位 deepseek-v4-pro / deepseek-v4-flash（business
# AgentProfile），DeepSeek 无缓存写/推理独立口径 → 每模型三行
# （tokenKind：1=input 3=cache_read 2=output）。$x/1M ÷ 1e6 = 每 token 单价。
# 生效起点统一 2026-01-01（敞口覆盖平台存量事件；真实改价史经管理 API 原子
# 改价维护，历史成本不漂移）。
#
# 幂等：匹配键（provider, model, tokenKind）已有任意行（含已关行）即跳过——
# 重复执行零新插；中断续跑收敛（缺哪个键补哪个）。既有键集取自清单翻页全量
# （页大小 100，翻到取尽），价史超百行也不漏判。
#
# 用法：./scripts/backoffice-price-seed.sh
# 凭据：BACKOFFICE_API_KEY / BACKOFFICE_API_SECRET（或 .env.local /
#   app-registry bootstrap 现取，同 backoffice-quote.sh）；不传操作者头——
#   种入行操作者落空（同存量行口径，运营侧无「谁种的」歧义面）。
# ============================================================================
set -euo pipefail

source "$(dirname "$0")/backoffice-signed-http.sh"

PROVIDER="deepseek"
EFFECTIVE_FROM="2026-01-01T00:00:00Z"
# provider model tokenKind unitPrice（官方定价页 peak 档，每 token USD）
SEED_ROWS=(
  "deepseek-v4-pro   1  0.00000132"
  "deepseek-v4-pro   3  0.000000044"
  "deepseek-v4-pro   2  0.00000396"
  "deepseek-v4-flash 1  0.00000044"
  "deepseek-v4-flash 3  0.000000014"
  "deepseek-v4-flash 2  0.00000132"
)

# 既有键集（含已关行）：model|tokenKind 每行一条。清单分页翻到取尽（防价史
# 超页截断漏判 → 误插撞 409 不收敛）
existing_keys() {
  local page=1 resp status body
  while :; do
    resp=$(signed_call GET "/api/backoffice/price-entries?provider=${PROVIDER}&page=${page}&size=100")
    status="${resp##*$'\n'}"
    body="${resp%$'\n'*}"
    [[ "$status" == 2* ]] || { printf '单价行清单读取失败（page %s）：\n%s\nHTTP %s\n' "$page" "$body" "$status" >&2; exit 1; }
    printf '%s' "$body" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for item in d["data"]["items"]:
    print(str(item["model"]) + "|" + str(item["tokenKind"]))'
    # 不足一页＝已取尽（total 兜底由服务端分页保证）
    local fetched
    fetched=$(printf '%s' "$body" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["data"]["items"]))')
    [[ "$fetched" -lt 100 ]] && break
    page=$((page + 1))
  done
}

open_row() {
  # 开行（单键）：model tokenKind unitPrice → 种入成功打印回执 id，失败打印错误退出。
  # unitPrice 以十进制串原样进 JSON（float 换算会走科学计数丢精度，脚本内常量可信）
  local model="$1" kind="$2" price="$3" resp status body
  local body_file
  body_file=$(mktemp)
  python3 - "$PROVIDER" "$model" "$kind" "$price" "$EFFECTIVE_FROM" > "$body_file" <<'PY'
import sys
provider, model, kind, price, effective_from = sys.argv[1:6]
print('{"provider": "%s", "model": "%s", "tokenKind": %d, "unitPrice": %s, '
      '"currency": "USD", "effectiveFrom": "%s"}' % (provider, model, int(kind), price, effective_from))
PY
  resp=$(signed_call POST "/api/backoffice/price-entries" "$body_file")
  rm -f "$body_file"
  status="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  if [[ "$status" != 2* ]]; then
    printf '开行失败（%s / tokenKind %s）：\n%s\nHTTP %s\n' "$model" "$kind" "$body" "$status" >&2
    exit 1
  fi
  printf '%s' "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])'
}

main() {
  resolve_secret
  local existing inserted=0 skipped=0
  existing=$(existing_keys)

  for row in "${SEED_ROWS[@]}"; do
    read -r model kind price <<< "$row"
    local key="${model}|${kind}"
    if grep -Fqx "$key" <<< "$existing"; then
      printf '跳过 %s / tokenKind %s（已有行，含已关行）\n' "$model" "$kind"
      skipped=$((skipped + 1))
    else
      local id
      id=$(open_row "$model" "$kind" "$price")
      printf '种入 %s / tokenKind %s ＝ %s USD/token（行 %s，自 %s 起敞口）\n' \
        "$model" "$kind" "$price" "$id" "$EFFECTIVE_FROM"
      inserted=$((inserted + 1))
    fi
  done

  step "种子完成：新插 ${inserted} 行 / 跳过 ${skipped} 行（共 ${#SEED_ROWS[@]} 键）"
}

main "$@"
