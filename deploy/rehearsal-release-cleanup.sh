#!/usr/bin/env bash
# #484 发布尾清理排练（本地 Docker Desktop 复演 release.sh 远程段尾部清理行为）：
#   ① 造现场（build cache＋保留集三件＋保留集外旧 dev 版）→ 跑清理 → 断言 build cache
#      清零、保留集三件完整在场、旧版被清（票面 AC1）
#   ② 模拟 docker 不可达（DOCKER_HOST 死端口）→ 断言发布形态调用仍退出 0、仅 WARN（AC2）
#   ③ 冒烟 grep 无匹配不中止远程段（rider，AC3——行为＋release.sh 静态双断言）
#
# 排练跑的是真实清理：本地 build cache 会全清，保留集外旧 dev 镜像（含本地残留的真实旧版，
# 如 0.1x 系）会被清到只剩指定当前版，全机 dangling 镜像（含与本项目无关者）亦被清——这正是
# 该步骤在生产要做的事，本地顺带回收。可重复执行。
# 共享资源协调：会清空 build cache 与 dangling 镜像（其他 docker 构建/排障变慢、无现场破坏）；
# 不碰任何运行中容器、不碰任何有 tag 的非 dev 族镜像。
# 用法（仓库根）：deploy/rehearsal-release-cleanup.sh
set -euo pipefail
cd "$(dirname "$0")/.."

ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }
phase(){ printf '\n== %s ==\n' "$*"; }

# CUR＝真实当前版（DockerEnvironmentBackend 常量正本提取，与 build-bundle.sh:69 同口径：
# sort -u＋唯一值守卫——多常量＝发布链断裂，排练同样要红）——排练语义＝「发版后现场」：
# 当前版在场且属保留集，清理不得触碰；替身只用于旧版与备份 tag
DEV_SRC=aiplatform-server/src/main/java/com/semgravio/aiplatform/base/workspace/infrastructure/docker/DockerEnvironmentBackend.java
CUR=$(grep -oE '"aiplatform/dev:[0-9]+(\.[0-9]+)*"' "$DEV_SRC" | tr -d '"' | sort -u)
[ -n "$CUR" ] && [ "$(printf '%s\n' "$CUR" | wc -l)" -eq 1 ] \
  || die "$DEV_SRC 未提取到唯一 aiplatform/dev:<tag> 常量（得到：${CUR:-空}）"
OLD=aiplatform/dev:9.8   # 保留集外旧 dev 版替身（两位大版本号，永不撞真实 0.x）
ECS_S=aiplatform-server:ecs-pre-infra
ECS_W=aiplatform-web:ecs-pre-infra

build_cache_size() { docker system df --format '{{.Type}}|{{.Size}}' | awk -F'|' '$1=="Build Cache"{print $2}'; }
img_present() { docker image inspect "$1" >/dev/null 2>&1; }

trap 'printf "\n✗ 排练失败，现场保留供排查（%s 行）。清场手跑：docker image rm %s %s %s\n" \
      "${LINENO:-?}" "$OLD" "$ECS_S" "$ECS_W"' ERR

phase "0/6 前置"
docker info >/dev/null 2>&1 || die "docker 不可用"
img_present busybox:latest || docker pull busybox
img_present "$CUR" || die "当前 dev 镜像 $CUR 不在场（排练语义＝发版后现场；先按部署文档 §5 预建）"
ok "docker 就绪、busybox 与当前版 $CUR 在场"

phase "1/6 造现场（build cache＋保留集②③替身＋旧版替身；保留集①＝真实当前版已在场）"
# 保留集②③替身（tag busybox 而来，零空间成本；ecs-pre-infra 若已在场视为用户现场、收尾不删）
ECS_S_OWNED=0; ECS_W_OWNED=0
img_present "$ECS_S" || { docker tag busybox:latest "$ECS_S"; ECS_S_OWNED=1; }
img_present "$ECS_W" || { docker tag busybox:latest "$ECS_W"; ECS_W_OWNED=1; }
docker tag busybox:latest "$OLD"
# build cache：造一层 RUN 缓存再删镜像（cache 留场）——保证「清前 > 0」可重复成立
img_id=$(printf 'FROM busybox\nRUN dd if=/dev/zero of=/pad bs=1M count=8\n' | docker build -q -)
docker image rm "$img_id" >/dev/null
sz=$(build_cache_size)
[ -n "$sz" ] && [ "$sz" != "0B" ] || die "造 build cache 失败（当前：${sz:-空}）"
img_present "$ECS_S" && img_present "$ECS_W" && img_present "$OLD" \
  || die "现场镜像不全"
ok "build cache 在场（$sz）；保留集②③替身＋旧版替身已造"

phase "2/6 跑清理（复刻 release.sh 远程段尾部调用形态：set -euo pipefail＋外层 WARN 兜底）"
( set -euo pipefail
  bash deploy/release-cleanup.sh "$CUR" \
    || echo "   ⚠ WARN：发布尾清理异常（不影响发布结果）"
  echo "== 5/5 完成：排练已上线 =="
) || die "清理步骤让发布形态翻红"

phase "3/6 断言清理效果（AC1）"
sz=$(build_cache_size)
[ "$sz" = "0B" ] || die "build cache 未清零（当前：$sz）"
ok "build cache 清零（$sz）"
img_present "$CUR"   || die "保留集①当前 dev 版被误删：$CUR（真实当前版，开发置备依赖）"
img_present "$ECS_S" || die "保留集②ecs-pre-infra 备份 tag 被误删：$ECS_S"
img_present "$ECS_W" || die "保留集②ecs-pre-infra 备份 tag 被误删：$ECS_W"
img_present busybox:latest || die "保留集③busybox 被误删"
ok "保留集三件完整在场（$CUR / ecs-pre-infra ×2 / busybox）"
img_present "$OLD" && die "保留集外旧版未被清：$OLD"
ok "保留集外旧 dev 版已清（$OLD）"

phase "4/6 模拟 docker 异常（AC2：发布仍成功、仅 WARN）"
OUT=$(mktemp)
( set -euo pipefail
  DOCKER_HOST=tcp://127.0.0.1:1 bash deploy/release-cleanup.sh "$CUR" \
    || echo "   ⚠ WARN：发布尾清理异常（不影响发布结果）"
  echo "== 5/5 完成：排练已上线 =="
) >"$OUT" 2>&1 || die "docker 异常模拟下发布形态翻红"
grep -q 'WARN' "$OUT" || die "docker 异常模拟未见 WARN"
grep -q 'builder prune 失败' "$OUT" \
  || die "docker 异常模拟下步①报假成功（模块须 pipefail：bash 子进程不继承父层 pipefail，docker 失败被 | tail 吞成退出 0）"
grep -q '已上线' "$OUT" || die "docker 异常模拟下发布成功尾行未打出"
ok "docker 不可达时各步失败均可见 WARN、发布形态仍报成功"
rm -f "$OUT"

phase "5/6 冒烟 grep rider（AC3）"
( set -euo pipefail
  docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'ws--no-such-xyz' \
    || echo "   注：无匹配容器（冒烟已过，仅列表无匹配）"
) || die "冒烟 grep 无匹配中止了远程段（rider 未修）"
docker ps --format 'table {{.Names}}\t{{.Status}}' | grep -E 'aiplatform|semgravio|ws-' >/dev/null \
  || die "冒烟 grep 正常路径失配（本地应有匹配容器）"
smoke_line=$(grep -F "grep -E 'aiplatform|semgravio|ws-'" deploy/release.sh || true)
case "$smoke_line" in
  *'||'*) ok "release.sh 冒烟 grep 行 || 兜底在位" ;;
  *)      die "release.sh 冒烟 grep 行未带 || 兜底：${smoke_line:-未找到该行}" ;;
esac

phase "6/6 清场"
docker image rm "$OLD" >/dev/null 2>&1 || true
[ "$ECS_S_OWNED" = 1 ] && docker image rm "$ECS_S" >/dev/null
[ "$ECS_W_OWNED" = 1 ] && docker image rm "$ECS_W" >/dev/null
ok "排练替身已清（build cache 已是目标清零态；当前版与保留集外的真实旧 dev 版保持清理后状态）"
echo
echo "排练通过：build cache 清零、保留集三件在场、旧版被清、失败仅 WARN、冒烟 grep 不中止。"
