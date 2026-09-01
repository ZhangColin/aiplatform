#!/bin/sh
# 工作区自愈入口（ADR 0001：单容器 all-in-one、/workspace 唯一持久卷、容器无状态）。
# 容器每次启动对既有卷幂等落位布局 + 起容器内中间件（pg/redis）——容器销毁重建后由
# 本脚本自愈，卷内数据（含 PGDATA）原样续用。布局目录与 WorkspaceLayout 常量表对应
# （手工同步，落位由 DockerEnvironmentBackendTest 对真实容器断言）。
set -eu

WS_ROOT=/workspace
# PGDATA 由置备方经 docker -e 注入（= WorkspaceLayout.PG_DATA_DIR），此处兜底缺省
PG_DATA="${PGDATA:-$WS_ROOT/data/pg}"
PG_STARTUP_LOG="$PG_DATA/startup.log"
PG_BIN=/usr/lib/postgresql/15/bin
PG_PORT=5432
REDIS_PORT=6379

# 1) 布局骨架（幂等）：WorkspaceLayout.SKELETON_DIRS 的物理落位
#    （docs / data/pg / .platform/{skills,rules,logs}）
mkdir -p "$WS_ROOT/docs" "$PG_DATA" "$WS_ROOT/.platform/skills" \
  "$WS_ROOT/.platform/rules" "$WS_ROOT/.platform/logs"

# 2) pg：数据进卷（PGDATA=/workspace/data/pg）；未初始化则 initdb。
#    trust 认证——pg 只监听容器内回环、不映射对外端口，客户端只有同容器进程。
if [ ! -s "$PG_DATA/PG_VERSION" ]; then
  chown postgres:postgres "$PG_DATA"
  su postgres -c "$PG_BIN/initdb -D '$PG_DATA' -U postgres -A trust -E UTF8 --no-locale" >/dev/null
fi
if ! pg_isready -h localhost -p "$PG_PORT" -q; then
  su postgres -c "$PG_BIN/pg_ctl -D '$PG_DATA' -l '$PG_STARTUP_LOG' -w start"
fi

# 3) 应用库（幂等）：角色与库同名（WORKSPACE_DB 由置备方注入），不存在则建。
if [ -n "${WORKSPACE_DB:-}" ] && ! su postgres -c \
    "$PG_BIN/psql -h localhost -p $PG_PORT -U postgres -tAc \
     \"SELECT 1 FROM pg_roles WHERE rolname='$WORKSPACE_DB'\"" | grep -q 1; then
  su postgres -c "$PG_BIN/createuser -h localhost -U postgres '$WORKSPACE_DB'"
  su postgres -c "$PG_BIN/createdb -h localhost -U postgres -O '$WORKSPACE_DB' '$WORKSPACE_DB'"
fi

# 4) redis：纯缓存不落盘（无持久化文件），未在跑则起（回环绑定，无对外端口）。
if ! redis-cli -h localhost -p "$REDIS_PORT" ping >/dev/null 2>&1; then
  redis-server --daemonize yes --bind 127.0.0.1 --port "$REDIS_PORT" \
    --save '' --appendonly no
fi

exec "$@"
