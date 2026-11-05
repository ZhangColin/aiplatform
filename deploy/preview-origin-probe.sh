#!/usr/bin/env bash
# 预览网关 Origin 收口活体验收探针（#398／父 #392，收口实现 #394，ADR-0034）
#
# 一条命令回答「预览网关有没有剥 /_next 的 Origin」：部署收口前跑＝红（带预览域 Origin
# 打 chunk 得 403），网关 reload 后同命令＝绿（200）。输出带时间戳，整段可复制回帖留档
# （#392 口径：验收记录不进 CI，正本在 issue）。
#
# 断言面（与 #396 排练探针同观察点——经网关、浏览器真实头形态；只测外部可观测行为）：
#   ① 页面壳无 Origin 可达（沙箱在服；提示页/5xx＝沙箱不在服或已休眠——休眠删的是沙箱
#      容器、工作区数据不动，属环境红非判定红）
#   ② 从页面 HTML 定位一个 /_next/static chunk（无 chunk＝非 Next 应用，不可判定）
#   ③ 主断言：chunk＋Origin/Referer/Sec-Fetch（浏览器真实头）→ 403＝红／200＝绿；
#      对照组＝同 chunk 无 Origin → 两种状态下都应 200（闸门按请求头不按来源地址，#392 实测矩阵）
#   ④ 守门：非 /_next 页面路由带 Origin → 两种状态下都应 200（透传不误伤——Server Actions
#      CSRF／AI 自写 Origin 校验不受收口扰动；部署后此处变红＝收口误伤，立即回退）
#   ⑤ HMR 抽查：/_next/webpack-hmr 带 Origin 的 ws 握手 → 「Unauthorized」形拒绝＝红侧证据；
#      未被拒（101／或隧道挂起＝已过握手在等帧——生产实测通过形态）＝绿侧证据
#   浏览器活体（页面完整渲染＋迭代改动即时可见）curl 验不了，由 cutover runbook 人工步骤收口。
#
# 用法：deploy/preview-origin-probe.sh [预览主机名]   # 缺省＝事发 demo 工作区
#   主机名可为 host[:port]（本地排练沙箱同法打，如 127.0.0.1:18081）；SCHEME=https
#   环境变量覆盖（全站 TLS 切换后用）。
# 退出码：0＝绿（收口生效）；1＝红（闸门在场——部署前跑的预期值）；2＝探针环境异常
#   （沙箱不在服／非 Next／意外状态码——先人工看输出再定；含守门变红＝收口误伤透传，
#   立即按 cutover runbook §8 回退）；4＝绿但 HMR 握手仍被拒（收口疑未覆盖 ws 路径，
#   人工排查，别当绿交差）。
set -euo pipefail

HOST_ARG=${1:-376467559789526725.preview.semgravio.com}
SCHEME=${SCHEME:-http}
BASE="$SCHEME://$HOST_ARG"
UA="Mozilla/5.0 (compatible; aiplatform-preview-origin-probe/398)"

# 应答整体取回（-w 尾行法，排练 #396 同款）：stdout＝应答体＋尾行状态码，调用方拆分；
# 空应答重试收口（#396 先例：瞬时空应答不单发定生死）。注意勿在 $( ) 里指望全局变量回传
# （子 shell 隔离，踩过：set -u 下 unbound）——需要体时用 probe_raw 自己拆，只 要码用 code_of。
probe_raw() { # $@=curl 参数（URL 与 -H） → stdout=体＋尾行状态码
  local i=0 resp=""
  while [ "$i" -lt 5 ] && [ -z "$resp" ]; do
    resp=$(curl -s --max-time 15 -w $'\n%{http_code}' "$@" 2>/dev/null) || true
    i=$((i+1)); [ -z "$resp" ] && sleep 1
  done
  [ -n "$resp" ] || resp=$'\n000'
  printf '%s' "$resp"
}
code_of() { local r; r=$(probe_raw "$@"); printf '%s' "${r##*$'\n'}"; }
code_in_2xx_3xx() { [ "$1" -ge 200 ] 2>/dev/null && [ "$1" -lt 400 ] 2>/dev/null; }

env_die() { printf '✗ %s\n' "$*" >&2; exit 2; }

printf '== 预览网关 Origin 收口探针（#398）==\n'
printf '目标：%s/   时间：%s\n' "$BASE" "$(date +%Y-%m-%dT%H:%M:%S%z)"

# ① 页面壳（工作区在服前提）
page_resp=$(probe_raw "$BASE/")
page_code=${page_resp##*$'\n'}
page_body=${page_resp%$'\n'*}
code_in_2xx_3xx "$page_code" \
  || env_die "① 页面壳状态 $page_code（不可达/提示页/休眠）——沙箱不在服或已休眠属环境红非判定红，先经平台打开该项目 Demo 体验页触碰唤醒（访客直连不触发唤醒）再跑"
printf '[1] 页面壳（无 Origin）→ %s ✓ 在服\n' "$page_code"

# ② chunk 定位（Next dev 页面必引用 /_next/static chunk；无则非 Next，本探针不管）
chunk=$(grep -oE '/_next/static/chunks/[^"]+\.js' <<<"$page_body" | head -n1 || true)
chunk=${chunk%%\?*}
[ -n "$chunk" ] || env_die "② 页面未引用 /_next/static chunk——非 Next 应用或页面形态异常，本探针只判 Next dev 闸门"
printf '[2] chunk 定位 → %s\n' "$chunk"

# ③ 主断言＋对照
plain_chunk=$(code_of "$BASE$chunk")
origin_chunk=$(code_of \
  -H "Origin: $BASE" -H "Referer: $BASE/" \
  -H 'Sec-Fetch-Dest: script' -H 'Sec-Fetch-Mode: cors' -H 'Sec-Fetch-Site: same-origin' \
  -H "User-Agent: $UA" "$BASE$chunk")
printf '[3] chunk 无 Origin（对照）→ %s\n' "$plain_chunk"
printf '[4] chunk 带预览域 Origin（主断言）→ %s\n' "$origin_chunk"

# ④ 守门：非 /_next 透传（收口只剥 /_next 前缀，这里部署前后都必须 200）
guard_code=$(code_of \
  -H "Origin: $BASE" -H 'Sec-Fetch-Dest: document' -H 'Sec-Fetch-Mode: navigate' \
  -H 'Sec-Fetch-Site: same-origin' -H "User-Agent: $UA" "$BASE/")
printf '[5] 页面路由带 Origin（守门·透传不误伤）→ %s\n' "$guard_code"

# ⑤ HMR 握手抽查：带 Origin 的 ws 升级请求。被闸门拒时上游回裸 Unauthorized（经代理后
# curl 呈 HTTP/0.9 形，--http0.9 收原始字节）或规范 403；通过则 101 后隧道挂起等帧
# （curl 到 max-time 超时、无任何字节＝未见拒绝，生产实测通过形态）。
hmr_raw=$(curl -s -i --http0.9 --max-time 4 \
  -H "Origin: $BASE" -H "Referer: $BASE/" \
  -H 'Upgrade: websocket' -H 'Connection: Upgrade' \
  -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Sec-WebSocket-Version: 13' \
  -H 'Sec-Fetch-Dest: websocket' -H 'Sec-Fetch-Mode: websocket' -H 'Sec-Fetch-Site: same-origin' \
  -H "User-Agent: $UA" "$BASE/_next/webpack-hmr" 2>/dev/null || true)
# 判定走 hmr_verdict（rejected/passed/unknown），展示串由它派生——不拿人读文案反解析回判定
hmr_verdict=unknown; hmr_note=''
if grep -qiE 'unauthorized|HTTP/[0-9.]+ 403' <<<"$hmr_raw"; then
  hmr_verdict=rejected; hmr_note='Unauthorized/403——HMR 断连形态'
elif grep -qE 'HTTP/[0-9.]+ 101' <<<"$hmr_raw"; then
  hmr_verdict=passed; hmr_note='101 Switching Protocols'
elif grep -qE 'HTTP/[0-9.]+ 404' <<<"$hmr_raw"; then
  hmr_verdict=unknown; hmr_note='端点 404'
elif [ -z "$hmr_raw" ]; then
  hmr_verdict=passed; hmr_note='隧道挂起＝已过握手在等帧'
else
  hmr_verdict=passed; hmr_note="未见拒绝（首行：$(head -n1 <<<"$hmr_raw" | head -c 60)）"
fi
case "$hmr_verdict" in
  rejected) hmr_state="被拒（$hmr_note）" ;;
  passed)   hmr_state="未被拒（$hmr_note）" ;;
  *)        hmr_state="不可判（$hmr_note）" ;;
esac
printf '[6] HMR 握手带 Origin（/_next/webpack-hmr）→ %s\n' "$hmr_state"

# ---- 判定 ------------------------------------------------------------------
if [ "$origin_chunk" = 403 ]; then
  printf '结论：红——网关未剥 /_next Origin，Next dev 跨源闸门在场（chunk 403）。\n'
  printf '      部署 #394 收口（资产装配→门禁安装→reload）后，同命令应转为绿。\n'
  exit 1
fi
[ "$origin_chunk" = 200 ] \
  || env_die "③ 主断言意外状态码 $origin_chunk（非 403/200）——人工看响应再定"
[ "$plain_chunk" = 200 ] \
  || printf '⚠ 对照异常：同 chunk 无 Origin 得 %s（两种状态下都应 200）\n' "$plain_chunk" >&2
code_in_2xx_3xx "$guard_code" \
  || env_die "④ 守门断言意外：非 /_next 带 Origin 得 $guard_code（应 200）——若出现在 reload 后＝收口误伤透传，立即按 runbook 回退并回帖 #392"
if [ "$hmr_verdict" = rejected ]; then
  printf '结论：绿但 HMR 握手仍被拒——收口疑未覆盖 ws 路径（/_next/ 前缀应含 webpack-hmr），人工排查，别当绿交差。\n'
  exit 4
fi
if [ "$hmr_verdict" = unknown ]; then
  printf '结论：绿——网关已剥 /_next Origin（chunk 200、非 /_next 透传不误伤）；HMR 端点不可判（%s），以 runbook 浏览器 WS 检查兜底。\n' "$hmr_note"
else
  printf '结论：绿——网关已剥 /_next Origin（chunk 200、非 /_next 透传不误伤、HMR 握手未被拒）。\n'
fi
printf '      浏览器活体（demo 页面完整渲染＋迭代改动即时可见）curl 验不了，按 runbook 人工确认。\n'
exit 0
