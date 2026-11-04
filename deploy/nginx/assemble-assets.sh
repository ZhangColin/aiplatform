#!/usr/bin/env bash
# 装配平台接入共享 nginx 的资产目录（#386）——供门禁第四参整体装入
# infra 的 nginx/conf.d/aiplatform.assets/。内容四件：
#   annotation.js        圈注脚本（单源：aiplatform-server .../docker/workspace/annotation.js）
#   unavailable.html     上游不可达自恢复提示页（单源：.../docker/gateway/unavailable.html）
#   gateway-proxy.conf   代理块（单源：.../docker/gateway/gateway-proxy.conf，被 aiplatform.conf include）
#   gateway-inject.conf  注入块（单源：.../docker/gateway/gateway-inject.conf，同上）
# 装配即拷贝，零改写：块内资产路径已统一按共享 nginx 形态（/etc/nginx/conf.d/aiplatform.assets/，
# 见块文件头注），dev 预览网关把同批文件挂到同一路径，单源不劈叉。
# 用法：./assemble-assets.sh <输出目录> [源根目录]
#   源根目录缺省＝本仓根（脚本位置 ../../ 推得）；部署包内＝包根（gateway/ 与 workspace/ 布局）。
set -euo pipefail
cd "$(dirname "$0")"

OUT=${1:?用法：./assemble-assets.sh <输出目录> [源根目录]}
SRC_ROOT=${2:-$(cd ../.. && pwd)}

# 源根两种布局：仓内（aiplatform-server/src/main/resources/docker/...）与部署包（gateway/、workspace/）
if [ -d "$SRC_ROOT/aiplatform-server/src/main/resources/docker/gateway" ]; then
  GW=$SRC_ROOT/aiplatform-server/src/main/resources/docker/gateway
  WS=$SRC_ROOT/aiplatform-server/src/main/resources/docker/workspace
else
  GW=$SRC_ROOT/gateway
  WS=$SRC_ROOT/workspace
fi

for f in annotation.js; do
  [ -f "$WS/$f" ] || { echo "错误：找不到 $WS/$f" >&2; exit 1; }
done
for f in gateway-proxy.conf gateway-inject.conf unavailable.html; do
  [ -f "$GW/$f" ] || { echo "错误：找不到 $GW/$f" >&2; exit 1; }
done

mkdir -p "$OUT"
cp -a "$WS/annotation.js" "$GW/unavailable.html" "$GW/gateway-proxy.conf" "$GW/gateway-inject.conf" "$OUT"/
printf '✓ 资产已装配到 %s（annotation.js / unavailable.html / gateway-proxy.conf / gateway-inject.conf）\n' "$OUT"
