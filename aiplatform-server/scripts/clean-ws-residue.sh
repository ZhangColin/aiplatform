#!/usr/bin/env bash
# 清理测试泄漏的沙箱 Docker 残留（活体测试 @AfterEach 尽力而为，JVM 中断即漏）。
#
# 判定「残留」的唯一准绳：资源编号不在 wsp_workspaces 注册表里
# （dev 库 aiplatform 与测试库 aiplatform_test 的并集）。
# 注册表里有 = 活跃/休眠/封存的工作区，一律不动——休眠保卷是用户数据（ADR-0016）。
# 任一库连不上 → 容器/卷整体跳过不删（宁可漏删，不可误删）；镜像清理不受影响。
#
# 连接口径同 application-local.yml（postgres@18 本机 5432），可用环境变量覆盖。
# 手动执行：aiplatform-server/scripts/clean-ws-residue.sh
set -u

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-postgres}"
PGPASSWORD="${PGPASSWORD:-truth}"
SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# docker 没起就静默退出（CI/无 docker 环境零副作用）
docker info >/dev/null 2>&1 || exit 0

# ---- 1. 注册表并集（两库都可达才敢删容器/卷）----
registered=""
db_ok=0
for db in aiplatform aiplatform_test; do
  if names=$(PGPASSWORD="$PGPASSWORD" psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" \
      -d "$db" -tAc "SELECT container_name FROM wsp_workspaces" 2>/dev/null); then
    registered="$registered $names"
  else
    db_ok=1
    echo "warn: 库 $db 不可达" >&2
  fi
done

is_registered() { # $1 = ws 编号；snap 等派生资源与主资源同编号，一并保护
  for name in $registered; do
    [ "${name#ws-}" = "$1" ] && return 0
  done
  return 1
}

removed_containers=0
removed_volumes=0
removed_images=0

# ---- 2. 无主容器（含泄漏的 ws-*-snap-* 快照容器）----
if [ "$db_ok" -ne 0 ]; then
  echo "PG 不可达：跳过容器/卷清理（无法判定注册表，防误删）"
else
  for c in $(docker ps -a --format '{{.Names}}' | grep -E '^ws-[0-9]+' || true); do
    id=$(printf '%s' "$c" | sed -E 's/^ws-([0-9]+).*/\1/')
    if ! is_registered "$id"; then
      docker rm -f "$c" >/dev/null 2>&1 && echo "容器已删: $c" && removed_containers=$((removed_containers + 1))
    fi
  done
  # ---- 3. 无主卷（vol-ws-<编号>；卷在容器删掉后才能删，故后置）----
  for v in $(docker volume ls --format '{{.Name}}' | grep -E '^vol-ws-[0-9]+$' || true); do
    id=${v#vol-ws-}
    if ! is_registered "$id"; then
      docker volume rm "$v" >/dev/null 2>&1 && echo "卷已删: $v" && removed_volumes=$((removed_volumes + 1))
    fi
  done
fi

# ---- 4. 旧版沙箱镜像：只留 DockerEnvironmentBackend.DEV_IMAGE 当前版 ----
# 版本号单点在 Java 常量；解析失败（文件挪了/常量改名）就跳过镜像清理。
backend_java="$SERVER_DIR/src/main/java/com/aieducenter/aiplatform/base/workspace/infrastructure/docker/DockerEnvironmentBackend.java"
current_image=$(grep -oE '"aiplatform/dev:[0-9.]+"' "$backend_java" 2>/dev/null | head -1 | tr -d '"')
if [ -n "$current_image" ]; then
  for img in $(docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '^aiplatform/dev:' || true); do
    if [ "$img" != "$current_image" ]; then
      docker rmi "$img" >/dev/null 2>&1 && echo "镜像已删: $img" && removed_images=$((removed_images + 1))
    fi
  done
fi

if [ $removed_containers -eq 0 ] && [ $removed_volumes -eq 0 ] && [ $removed_images -eq 0 ]; then
  echo "无沙箱残留"
fi
exit 0
