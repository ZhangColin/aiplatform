#!/usr/bin/env bash
# ============================================================================
# #184 预览停机体验计时器（spec #180 计时基线）：轮询预览子域，客观记录
# 5xx（上游不可达——网关自恢复提示页承载中）→ 非 5xx（上游活了）的翻转时刻
# 与耗时。走查手册 docs/smoke/184-预览停机体验联调手册.md §六计时口径：
#
#   - 体感计时（AC6 主口径）：记下触发动作的钟点（点刷新/提交消息的瞬间），
#     与本脚本打出的「→ 可用」钟点相减；脚本时间线自带钟点，方便对算
#   - 502 窗口（AC5）：后端就绪（浏览器 devtools EventStream 见 preview-ready
#     或后端终端日志）到本脚本翻 200 的差值 ≤ 数秒（resolver valid=5s 实效）
#
# 用法：
#   ./scripts/preview-wake-timer.sh <workspaceId> [最大秒数=600]
#
# 行为：每 1s 一探（curl --max-time 3），只在状态变化时打一行（钟点 + 相对秒）；
# 见过至少一次 5xx 后翻回非 5xx 即收口退出（起始就是非 5xx 时提示继续监控——
# 等 5xx 出现即「休眠回收已发生」，正好覆盖「停留页面等回收」的场景）。Ctrl-C
# 随时停。状态释义：502/504=网关连不上上游（提示页在承载）；000=网关自身不可达
# （连提示页都没有）；3xx/4xx=上游应用已活（应用自身的 4xx 也算活，同提示页
# 恢复脚本的判定口径）；200=正常。
# ============================================================================
set -euo pipefail

ws_id="${1:-}"
max_secs="${2:-600}"
[[ "$ws_id" =~ ^[0-9]+$ ]] || { echo "用法：$0 <workspaceId> [最大秒数=600]" >&2; exit 2; }
[[ "$max_secs" =~ ^[0-9]+$ ]] || { echo "最大秒数须为正整数（bash 3.2 算术里非数字按 0 处理，循环一轮不跑）" >&2; exit 2; }
url="http://${ws_id}.localhost/"

now() { date '+%H:%M:%S'; }

echo "[preview-wake-timer] 轮询 ${url} 每 1s，最长 ${max_secs}s；见过 5xx 后翻非 5xx 即收口（Ctrl-C 随时停）"

seen_5xx=0
last=""
start_epoch=$(date +%s)
last_5xx_epoch=""
elapsed=0

while (( elapsed < max_secs )); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" || true)
  [[ -z "$code" ]] && code="000"
  elapsed=$(( $(date +%s) - start_epoch ))   # 每轮更新：循环上界以真实墙钟为准
  # 不可达谓词单点落布尔（分类与翻转退出两处共用同口径）；
  # last_5xx_epoch 每 5xx 轮都刷新——翻转耗时从最后一个「已知不可达」观测起算，
  # 不把休眠等待期混进唤醒恢复耗时
  down=0
  if [[ "$code" =~ ^5 ]] || [[ "$code" == "000" ]]; then
    down=1
    seen_5xx=1
    last_5xx_epoch=$(date +%s)
  fi
  if [[ "$code" != "$last" ]]; then
    marker=""
    if (( down )); then
      marker="上游不可达（提示页承载中）"
    elif (( seen_5xx )); then
      marker="← 上游恢复"
    else
      marker="当前可用——等 5xx（休眠回收）后再触发恢复，继续监控"
    fi
    printf '%s +%ss\t%s\t%s\n' "$(now)" "$elapsed" "$code" "$marker"
    last="$code"
    if (( seen_5xx )) && (( ! down )); then
      flip=$(( $(date +%s) - last_5xx_epoch ))
      echo "[preview-wake-timer] 最后一个 5xx 观测 → 可用 翻转 ≤ ${flip}s（含 1s 轮询粒度）；「← 上游恢复」行的钟点即可用时刻"
      exit 0
    fi
  fi
  sleep 1
done

echo "[preview-wake-timer] ${max_secs}s 内未观测到恢复翻转（超时退出）" >&2
exit 1
