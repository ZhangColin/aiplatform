#!/usr/bin/env bash
# 发布尾清理（#484）：release.sh 远程段冒烟通过后的尾部子步骤模块。
# 职责：① build cache 全清（17G 级积压主源）② 旧 dev 镜像清到保留集 ③ dangling 镜像清残
# （compose 重建覆盖 :ecs tag 后的旧层）。
# 保留集三件（按构造不在清理射程，永不触碰）：当前 DEV_IMAGE 版（参数传入，正本＝发布包内
# DEV_IMAGE 文件）、ecs-pre-infra 备份 tag ×2、busybox——①只动 build cache，②按 repository
# 过滤只动 aiplatform/dev 族且排除当前版（-vxF 整串精确），③只动无 tag 的 dangling。
# 失败口径：每步独立兜底、只 WARN 不翻红发布（调用方 release.sh 另有一层总兜底）。
# 运行中容器引用的旧版镜像：docker image rm -f 只 untag 不杀容器，层待容器停删后由 ③ 收。
#
# 只开 pipefail 不开 -e/-u：本模块契约＝每步 if/else 自兜、失败 WARN 后继续走完其余步骤、
# 尾部恒退出 0；pipefail 必须开——release.sh 以 bash 子进程调用本文件，父层 pipefail 不
# 继承，缺了它 `docker builder prune | tail` 的退出码恒＝tail 的 0，docker 异常会报假成功。
#
# 本文件不是独立发布通道（单通道口径）：发布只能走 deploy/release.sh，本模块由其远程段
# 尾部调用；本地排练资产 deploy/rehearsal-release-cleanup.sh 以同一命令形态复演。
# 用法：bash deploy/release-cleanup.sh <当前 DEV_IMAGE，如 aiplatform/dev:0.17>
set -o pipefail

cur_dev="${1:?用法：bash deploy/release-cleanup.sh <当前 DEV_IMAGE，如 aiplatform/dev:0.17>}"

echo "-- 发布尾清理（build cache＋保留集外旧镜像；失败仅 WARN 不翻红发布，#484）"
echo "   保留集：当前 dev 镜像（$cur_dev）＋ ecs-pre-infra 备份 tag ＋ busybox"

# ① build cache 全清（cache 只加速构建，清了不影响运行）
reclaimed=$(docker builder prune -af 2>/dev/null | tail -n 1) \
  && echo "   ✓ build cache 已清（${reclaimed:-无回收量输出}）" \
  || echo "   ⚠ WARN：docker builder prune 失败（不影响发布结果）"

# ② 旧 dev 镜像清到保留集：只动 aiplatform/dev 族，排除当前版
if ! dev_list=$(docker images --format '{{.Repository}}:{{.Tag}}' 2>/dev/null); then
  echo "   ⚠ WARN：docker images 不可达，旧 dev 镜像本轮不清（不影响发布结果）"
else
  old_devs=$(printf '%s\n' "$dev_list" | grep -xE 'aiplatform/dev:[0-9]+(\.[0-9]+)*' | grep -vxF "$cur_dev" || true)
  if [ -z "$old_devs" ]; then
    echo "   ✓ 无保留集外旧 dev 镜像"
  elif docker image rm -f $old_devs >/dev/null 2>&1; then
    echo "   ✓ 旧 dev 镜像已清：$(echo "$old_devs" | tr '\n' ' ')"
  else
    echo "   ⚠ WARN：旧 dev 镜像清理失败（部分在用？不影响发布结果）：$(echo "$old_devs" | tr '\n' ' ')"
  fi
fi

# ③ dangling 清残（只动无 tag 镜像；ecs-pre-infra/busybox 有 tag 不在射程）
docker image prune -f >/dev/null 2>&1 \
  && echo "   ✓ dangling 镜像已清" \
  || echo "   ⚠ WARN：dangling 镜像清理失败（不影响发布结果）"
