#!/bin/sh
# 演示种子数据施用入口（#340）：幂等、失败静默——绝不阻断容器自愈/应用拉起/run 收口。
# 三处调用方共用：init-workspace.sh（容器自愈）、应用拉起命令
# （DockerEnvironmentBackend.appStartCommand）、平台 run 收口 exec
# （DemoSeedAppService——首跑的应用表在收口前才成形，纯起跑点注不进首跑）。
# 引擎＝同目录 seed.sql（通用示例集：只补空表，模型已产数据不碰）。
# 回执落 .platform/logs/seed.log（NOTICE 逐表跳过/预置原因，诊断用）。
# 速度触发器留档（票面）：实测落库对 Demo 速度影响非常大时再议降形态方案。
set -u

# PG 客户端路径（与 init-workspace.sh 的 PG_BIN 同源——镜像 postgresql-15；镜像
# 升 PG 版本两处同改）
PG_BIN=/usr/lib/postgresql/15/bin

[ -n "${WORKSPACE_DB:-}" ] || exit 0
pg_isready -h localhost -q || exit 0
mkdir -p /workspace/.platform/logs
{
  echo "--- seed $(date '+%F %T') ---"
  su postgres -c "$PG_BIN/psql -h localhost -q -v ON_ERROR_STOP=0 -d '$WORKSPACE_DB' -f /opt/seed/seed.sql"
} >> /workspace/.platform/logs/seed.log 2>&1 || true
exit 0
