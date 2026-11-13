#!/usr/bin/env bash
# ============================================================================
# #165/#500 单价表种子幂等签名脚本：经后台管理 API「开行」端点种入种子行。
# 启动 Seeder 已退役（写口唯一化到管理 API）——本脚本是单价表唯一初始化手段；
# 忘初始化的护栏＝unpriced 全局警示读面（用量驱动，自然暴露漏配价）。
#
# 数值口径（A6 §1 纪律：以官方定价页实时值为准，不抄研究稿）：
#
# 【deepseek 官方通道 12 行，#500 重写（ADR-0044 峰谷 4/4）】CNY 每 token 真值、
# 峰/谷 × 未命中/命中/输出 × 两模型＝12 行（band 1=峰 2=谷，#498 单价表带列），
# 生效起点 2026-11-11T00:00:00Z（官方通道切换日，无终点敞口；门禁③证实此前无
# 官方通道事件，起点前窗口无事件可匹配）。数值＝#470 Further Notes 价格表（2026-
# 11-12 拉自英文站现行价：谷＝峰半价；中文站价目页是 V4 旧价已过期勿作依据；
# ⚠ 六价位点系账单反推、未见官方 CNY 牌价正本——生产落库前必须过 #500 门禁①
# 人工核对 DeepSeek 后台价格页）。注意父票表 pro 输出「库内」列误植 0.0000027，
# 真值 0.000027（¥27/M÷1e6，与 volcengine 存量行 0.000027、旧 USD 行 $3.96/M
# 交叉印证）。时段规则（工作日两峰窗 UTC）不由本表承载——本脚本收尾经
# backoffice-time-band-seed.sh 种入同通道（#500 AC：同脚本通道一次跑全）。
# 存量旧 deepseek 行（#165 USD 峰值档、NULL 带、2026-01-01 起）如在库本脚本
# 不动：通道有生效规则行后 NULL 带行不再参与匹配（引擎 #499 归段选价语义），
# 是否停用走管理 API 运维自决。
#
# #288 出图按张行（tokenKind 6=image，CNY/张；2026-10-06 核对各家官方定价页）：
#   zhipu/glm-image 0.10（docs.bigmodel.cn 定价页）｜
#   volcano/doubao-seedream-5-0-260128 0.22（ark 模型价格页，Seedream 5.0 lite 档）｜
#   dashscope/qwen-image-3.0 0.18（百炼价格页，输出张计价——文生图无输入图）。
#   openai/gpt-image-2.5-sunburst 不种：官方按 token 计价（image output $30/1M）、
#   无按张官方价，硬抄折算数违「实时值为准」纪律——用 OpenAI 档时经管理 API 自行
#   开行（unpriced 警示会如实暴露）。
#
# #369 火山通道行（provider=volcengine＝通道键，模型名与档位行实际模型一致；
# 2026-10-23 核对 docs.volcengine.com/docs/ark/model-pricing 在线推理常规价，
# CNY/百万 tokens，无时段分档直取）：切换后主通道三模型 × 3 tokenKind
# （1=input 3=cache_read 2=output），成本观测跨通道切换不断线。无峰谷通道不带
# band 列（NULL＝不区分时段，#498 存量语义零变动）。
#   deepseek-v4-pro-ga-260813 9/0.3/27｜deepseek-v4-flash-ga-260731 3/0.1/9｜
#   doubao-seed-2-1-pro-260915 6/1.2/30。
#
# 幂等：匹配键（provider, model, tokenKind, band）已有任意行（含已关行）即跳过
# ——重复执行零新插；中断续跑收敛（缺哪个键补哪个键，带维度各自独立）。既有键集
# 取自清单翻页全量（页大小 100，翻到取尽），价史超百行也不漏判。
#
# 用法：./scripts/backoffice-price-seed.sh
# 凭据：BACKOFFICE_API_KEY / BACKOFFICE_API_SECRET（或 .env.local /
#   app-registry bootstrap 现取，同 backoffice-quote.sh）；不传操作者头——
#   种入行操作者落空（同存量行口径，运营侧无「谁种的」歧义面）。
# ============================================================================
set -euo pipefail

source "$(dirname "$0")/backoffice-signed-http.sh"

# 默认生效起点（无峰谷通道与按张行：敞口覆盖平台存量事件；真实改价史经管理
# API 原子改价维护，历史成本不漂移）
DEFAULT_EFFECTIVE_FROM="2026-01-01T00:00:00Z"
# deepseek 官方通道真值行生效起点（#500：官方通道切换日，门禁③证实此前无事件）
DEEPSEEK_EFFECTIVE_FROM="2026-11-11T00:00:00Z"

# provider model tokenKind band unitPrice currency effectiveFrom（deepseek 行＝
# 官方通道 CNY 每 token 真值带峰谷；图片行＝官方定价页 CNY/张；band「-」＝NULL 带）
SEED_ROWS=(
  # -- deepseek 官方通道：deepseek-v4-pro（=DeepSeek-V4-Pro-0813）峰/谷 --
  "deepseek         deepseek-v4-pro             1  1  0.0000090    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-v4-pro             3  1  0.0000003    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-v4-pro             2  1  0.000027     CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-v4-pro             1  2  0.0000045    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-v4-pro             3  2  0.00000015   CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-v4-pro             2  2  0.0000135    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  # -- deepseek 官方通道：deepseek-flash（=DeepSeek-V4.1-Flash-1017）峰/谷 --
  "deepseek         deepseek-flash              1  1  0.0000020    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-flash              3  1  0.00000004   CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-flash              2  1  0.0000080    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-flash              1  2  0.0000010    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-flash              3  2  0.00000002   CNY  $DEEPSEEK_EFFECTIVE_FROM"
  "deepseek         deepseek-flash              2  2  0.0000040    CNY  $DEEPSEEK_EFFECTIVE_FROM"
  # -- 出图按张（tokenKind 6=image，CNY/张） --
  "zhipu            glm-image                   6  -  0.10         CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcano          doubao-seedream-5-0-260128  6  -  0.22         CNY  $DEFAULT_EFFECTIVE_FROM"
  "dashscope        qwen-image-3.0              6  -  0.18         CNY  $DEFAULT_EFFECTIVE_FROM"
  # -- volcengine 通道（无峰谷，NULL 带）--
  "volcengine       deepseek-v4-pro-ga-260813   1  -  0.000009     CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       deepseek-v4-pro-ga-260813   3  -  0.0000003    CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       deepseek-v4-pro-ga-260813   2  -  0.000027     CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       deepseek-v4-flash-ga-260731 1  -  0.000003     CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       deepseek-v4-flash-ga-260731 3  -  0.0000001    CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       deepseek-v4-flash-ga-260731 2  -  0.000009     CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       doubao-seed-2-1-pro-260915  1  -  0.000006     CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       doubao-seed-2-1-pro-260915  3  -  0.0000012    CNY  $DEFAULT_EFFECTIVE_FROM"
  "volcengine       doubao-seed-2-1-pro-260915  2  -  0.00003      CNY  $DEFAULT_EFFECTIVE_FROM"
)

# 既有键集（含已关行）：provider|model|tokenKind|band 每行一条（band 空串＝NULL 带
# ——#500 起匹配键含时段带，峰/谷/空三态各自独立判重）。清单分页翻到取尽（防
# 价史超页截断漏判 → 误插撞 409 不收敛）
existing_keys() {
  local page=1 resp status body
  while :; do
    resp=$(signed_call GET "/api/backoffice/price-entries?page=${page}&size=100")
    status="${resp##*$'\n'}"
    body="${resp%$'\n'*}"
    [[ "$status" == 2* ]] || { printf '单价行清单读取失败（page %s）：\n%s\nHTTP %s\n' "$page" "$body" "$status" >&2; exit 1; }
    printf '%s' "$body" | python3 -c '
import json, sys
d = json.load(sys.stdin)
for item in d["data"]["items"]:
    band = item.get("band")
    print(str(item["provider"]) + "|" + str(item["model"]) + "|"
          + str(item["tokenKind"]) + "|" + ("" if band is None else str(band)))'
    # 不足一页＝已取尽（total 兜底由服务端分页保证）
    local fetched
    fetched=$(printf '%s' "$body" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["data"]["items"]))')
    [[ "$fetched" -lt 100 ]] && break
    page=$((page + 1))
  done
}

open_row() {
  # 开行（单键）：provider model tokenKind band unitPrice currency effectiveFrom
  # → 种入成功打印回执 id，失败打印错误退出。unitPrice 以十进制串原样进 JSON
  # （float 换算会走科学计数丢精度，脚本内常量可信）；band 空＝不传（NULL 带）
  local provider="$1" model="$2" kind="$3" band="$4" price="$5" currency="$6" effective_from="$7"
  local resp status body body_file
  body_file=$(mktemp)
  python3 - "$provider" "$model" "$kind" "$band" "$price" "$currency" "$effective_from" > "$body_file" <<'PY'
import sys
provider, model, kind, band, price, currency, effective_from = sys.argv[1:8]
band_json = (", " + '"band": ' + str(int(band))) if band else ""
print('{"provider": "%s", "model": "%s", "tokenKind": %d%s, "unitPrice": %s, '
      '"currency": "%s", "effectiveFrom": "%s"}'
      % (provider, model, int(kind), band_json, price, currency, effective_from))
PY
  resp=$(signed_call POST "/api/backoffice/price-entries" "$body_file")
  rm -f "$body_file"
  status="${resp##*$'\n'}"
  body="${resp%$'\n'*}"
  if [[ "$status" != 2* ]]; then
    printf '开行失败（%s / %s / tokenKind %s / band %s）：\n%s\nHTTP %s\n' \
      "$provider" "$model" "$kind" "${band:-NULL}" "$body" "$status" >&2
    exit 1
  fi
  printf '%s' "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])'
}

seed_time_band_rule() {
  # 时段规则同脚本通道收尾种入（#500 AC）：复用 #497 幂等脚本——deepseek 通道
  # 已有任意规则行即其内部跳过，本函数不再重复判
  step "种入 deepseek 峰窗时段规则（backoffice-time-band-seed.sh，幂等）"
  export BACKOFFICE_API_SECRET
  bash "$(dirname "$0")/backoffice-time-band-seed.sh"
}

main() {
  resolve_secret
  local existing inserted=0 skipped=0
  existing=$(existing_keys)

  for row in "${SEED_ROWS[@]}"; do
    read -r provider model kind band price currency effective_from <<< "$row"
    # band 占位「-」＝NULL 带（无峰谷行；read 对变宽行按占位对齐字段位）
    [[ "$band" == "-" ]] && band=""
    local key="${provider}|${model}|${kind}|${band}"
    if grep -Fqx "$key" <<< "$existing"; then
      printf '跳过 %s / %s / tokenKind %s / band %s（已有行，含已关行）\n' \
        "$provider" "$model" "$kind" "${band:-NULL}"
      skipped=$((skipped + 1))
    else
      local id
      id=$(open_row "$provider" "$model" "$kind" "$band" "$price" "$currency" "$effective_from")
      printf '种入 %s / %s / tokenKind %s / band %s ＝ %s %s（行 %s，自 %s 起敞口）\n' \
        "$provider" "$model" "$kind" "${band:-NULL}" "$price" "$currency" "$id" "$effective_from"
      inserted=$((inserted + 1))
    fi
  done

  step "单价种子完成：新插 ${inserted} 行 / 跳过 ${skipped} 行（共 ${#SEED_ROWS[@]} 键）"
  seed_time_band_rule
}

main "$@"
