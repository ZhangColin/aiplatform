#!/usr/bin/env bash
# semgravio-infra nginx 配置门禁——接入方安装/更新自己名下 conf.d/<service>.conf 的唯一合法通道。
# 共享 nginx 是最危险的共享故障点：本门禁使任一服务的坏配置无法殃及他人（#385 AC）。
# 用法：./install-nginx-conf.sh <服务名> <conf 文件路径> [证书目录]
# 行为：
#   ① 资产边界：只写 nginx/conf.d/<service>.conf（+ 可选参数给定时 nginx/certs/<service>/），
#      绝不触碰其它服务的文件；
#   ② 撞车即拒：server_name 与既有其它服务配置冲突（通配按单层标签口径）、
#      或声明 default_server（归 infra 兜底 server）——写文件前拒绝，不留任何痕迹；
#   ③ 语法门禁：写入后 nginx -t 通过才 reload；不过自动回滚刚写的文件（旧文件/证书还原
#      或删除），nginx 未 reload、存活不受影响。写入前基线 -t 已不过（他方坏配置）时拒绝进场。
# 并发口径：门禁无跨进程锁——同一共享 nginx 的安装操作由各服务部署流程自行串行化
# （reload 与 -t 之间的竞态只在此前提下闭合）。
# 服务名口径：^[a-z][a-z0-9_-]{0,30}$——合法文件名、免引号、无路径穿越面。
# 比建号 helper 多允「-」（nginx conf 惯例名，如 app-registry）；若同名服务还要建库，
# 库名/角色名按 helper 口径（无「-」）另行取名——两资产的名字契约各自独立。
set -euo pipefail
cd "$(dirname "$0")"

die() { printf '✗ %s\n' "$*" >&2; exit 1; }
ok()  { printf '✓ %s\n' "$*" >&2; }

# ---- 参数与文件校验 ----------------------------------------------------------
[ $# -ge 2 ] && [ $# -le 3 ] || die "用法：./install-nginx-conf.sh <服务名> <conf 文件路径> [证书目录]"
svc=$1
conf=$2
certs_src=${3:-}
[[ "$svc" =~ ^[a-z][a-z0-9_-]{0,30}$ ]] \
  || die "服务名须匹配 ^[a-z][a-z0-9_-]{0,30}$（直接作 conf 文件名）：$svc"
[ -f "$conf" ] || die "conf 文件不存在：$conf"
[ -z "$certs_src" ] || [ -d "$certs_src" ] || die "证书目录不存在：$certs_src"

# ---- nginx 前置 --------------------------------------------------------------
[ "$(docker inspect -f '{{.State.Running}}' semgravio-nginx 2>/dev/null || true)" = true ] \
  || die "semgravio-nginx 未运行——先跑 ./deploy.sh"

# 基线语法：写入前共享 nginx 的既有配置必须全绿；已坏则拒绝进场（不是我的文件造成的，
# 也不该由我背——让运维先修，避免本门禁的回滚语义被搅浑）
baseline=$(docker exec semgravio-nginx nginx -t 2>&1) \
  || die "nginx 基线语法校验已不过（既有配置有坏文件，先修复再装）：
$baseline"

# ---- ① 资产边界 --------------------------------------------------------------
# 名下资产清单（本脚本唯一会写的目标，路径由 svc 正则兜住、无穿越面）：
#   nginx/conf.d/<svc>.conf        （必装）
#   nginx/certs/<svc>/             （可选第三参数给定时装证书）
# 其余任何文件不碰；其它服务的同名空间（conf/certs）由 svc 身份天然隔开。
conf_target=$PWD/nginx/conf.d/$svc.conf
certs_target=$PWD/nginx/certs/$svc

# ---- ② 撞车即拒（写文件前） --------------------------------------------------
strip_comments() { sed -e 's/#.*//' "$1"; }   # 去注释：撞车/抢占检测统一口径（防注释里的假名）

# server_name 抽取口径：去注释后按「单指令行」取 token——接入契约按单行写 server_name；
# 多行折行写法过不了本检测，属于契约外用法（撞车检测漏检的风险自担）。
# 词边界防误匹配：$server_name 等变量展开写法不算指令（$ 或标识符紧邻的排除）。
server_names() {
  strip_comments "$1" \
    | grep -oE '(^|[^$A-Za-z0-9_-])server_name[[:space:]]+[^;]+;' \
    | sed -E 's/^(.?)server_name[[:space:]]+//; s/;[[:space:]]*$//' \
    | tr -s '[:space:]' '\n' | sed '/^$/d' | sort -u
}

wild_hit() { # $1=*.suffix（通配） $2=精确名：恰好多一层标签且尾部相同 → nginx 语义命中
  local sfx=${1#\*} rest
  rest=${2%"$sfx"}
  [ "$rest" != "$2" ] && [ -n "$rest" ] && [ "${rest#*.}" = "$rest" ]
}
names_overlap() { # $1 $2：精确相等；单侧通配按单层标签命中另一侧；双侧通配仅后缀相同才撞
  local a=$1 b=$2  # （单层标签语义下 *.ex.com 与 *.sub.ex.com 服务集合无交集——不得误判）
  [ "$a" = "$b" ] && return 0
  if [[ "$a" == \*.* && "$b" == \*.* ]]; then
    [ "${a#\*}" = "${b#\*}" ]
    return
  fi
  case "$a" in \*.*) wild_hit "$a" "$b" && return 0 ;; esac
  case "$b" in \*.*) wild_hit "$b" "$a" && return 0 ;; esac
  return 1
}

# default_server 归 infra 兜底 server（nginx.conf），服务 conf 不得抢占
if strip_comments "$conf" | grep -q 'default_server'; then
  die "conf 声明了 default_server——IP 直访/未匹配域名兜底归 infra（nginx.conf），服务不得抢占"
fi

mapfile -t new_names < <(server_names "$conf")
[ "${#new_names[@]}" -gt 0 ] || die "conf 里没有 server_name 指令——装进来路由永不命中，拒绝"

conflict=""
for f in nginx/conf.d/*.conf; do
  [ -e "$f" ] || continue                       # 空目录 glob 原样返回，跳过
  [ "$(basename "$f")" = "$svc.conf" ] && continue  # 更新自己：旧名的占用不算冲突
  while read -r existing; do
    for n in "${new_names[@]}"; do
      if names_overlap "$n" "$existing"; then
        conflict="$conflict
  $n（已被 $(basename "$f") 占用：$existing）"
      fi
    done
  done < <(server_names "$f")
done
[ -z "$conflict" ] || die "server_name 撞车，安装被拒（写文件前拒绝，未动任何配置）：$conflict"
ok "server_name 撞车预检通过：${new_names[*]}"

# ---- ③ 语法门禁：写入 → nginx -t → reload，不过即回滚 -------------------------
rollback_marker=$(mktemp -d)   # 备份底座：conf 旧文件 + certs 旧目录，失败时逐项还原
had_prev=0
[ -f "$conf_target" ] && { cp "$conf_target" "$rollback_marker/prev.conf"; had_prev=1; }
had_certs=0
if [ -d "$certs_target" ]; then cp -a "$certs_target" "$rollback_marker/prev-certs"; had_certs=1; fi

# 先装证书（conf 里 listen 443 ssl 引用它，-t 一并校验），再装 conf
if [ -n "$certs_src" ]; then
  mkdir -p "$certs_target"
  cp -a "$certs_src"/. "$certs_target"/
fi
cp -- "$conf" "$conf_target"

if t_out=$(docker exec semgravio-nginx nginx -t 2>&1); then
  docker exec semgravio-nginx nginx -s reload
  rm -rf "$rollback_marker"
  ok "nginx -t 通过，已 reload：conf.d/$svc.conf 生效${certs_src:+，证书已装入 certs/$svc/}"
else
  # 回滚顺序与写入相反：先撤 conf 再撤 certs；旧的在则还原、无则删除（不留半安装态）
  if [ "$had_prev" = 1 ]; then cp "$rollback_marker/prev.conf" "$conf_target"; else rm -f "$conf_target"; fi
  if [ "$had_certs" = 1 ]; then rm -rf "$certs_target"; cp -a "$rollback_marker/prev-certs" "$certs_target"; else rm -rf "$certs_target"; fi
  rm -rf "$rollback_marker"
  # 回滚后基线应恢复全绿（写入前已验过基线）；仍不过说明并发进了别的坏文件
  docker exec semgravio-nginx nginx -t >/dev/null 2>&1 \
    || printf '⚠ 回滚后 nginx -t 仍不过——疑似并发写入了其它坏配置，请立即排查 conf.d/\n' >&2
  die "nginx 语法校验失败，已自动回滚 conf.d/$svc.conf${certs_src:+ 与 certs/$svc/}（未 reload，nginx 仍跑旧配置、存活不受影响）。原始错误：
$t_out"
fi
