#!/usr/bin/env bash
# semgravio-infra 幂等建号 helper（ensure-db-user 语义，接入契约见 README.md）。
# 接入方一条命令获得自己的 database ＋ 低权限用户：
#   DB_PASSWORD=<密码> ./ensure-db-user.sh <服务名>
# 行为（#384 AC）：
#   ① 幂等：同一服务重复执行结果一致——已存在即对齐（角色属性/密码、库属主、权限全部重放），
#      无报错、无重复创建。务必每次携带同一个 DB_PASSWORD；换新值即是对齐改密。
#   ② 低权限：角色 LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE；库属主＝服务用户；
#      本库 CONNECT 对 PUBLIC 收回、维护库（postgres/template1）CONNECT 也从 PUBLIC
#      收回——可连接面＝自己名下的库，他服务库/维护库连接即拒。
#   ③ 超管凭据不出 infra：.env 超管凭据仅本脚本内部使用，stdout 只有服务自身凭据分片。
# 服务名口径：^[a-z][a-z0-9_]{0,30}$（直接作库名/角色名，合法 PG 标识符、免引号）。
set -euo pipefail
cd "$(dirname "$0")"

die() { printf '✗ %s\n' "$*" >&2; exit 1; }

# ---- 参数与服务名校验 ------------------------------------------------------
[ $# -eq 1 ] || die "用法：DB_PASSWORD=<密码> ./ensure-db-user.sh <服务名>"
svc=$1
[[ "$svc" =~ ^[a-z][a-z0-9_]{0,30}$ ]] \
  || die "服务名须匹配 ^[a-z][a-z0-9_]{0,30}$（直接作库名/角色名用）：$svc"
[ -n "${DB_PASSWORD:-}" ] || die "DB_PASSWORD 未提供——服务自身密码，接入方生成并持有（如 openssl rand -hex 16）"

# ---- 超管凭据正本（仅内部使用，绝不进输出） ----------------------------------
[ -f .env ] || die ".env 缺失——先跑 ./deploy.sh 起栈（或按 README 凭据模型手写正本）"
set -a; . ./.env; set +a
: "${POSTGRES_USER:?missing in .env}" "${POSTGRES_PASSWORD:?missing in .env}"
[ "$(docker inspect -f '{{.State.Health.Status}}' semgravio-pg 2>/dev/null || true)" = healthy ] \
  || die "semgravio-pg 未运行/未 healthy——先跑 ./deploy.sh"

# 超管面 psql：密码只进 docker exec 环境（不进命令行、不进输出）；
# SQL 一律走 stdin 喂（psql 变量替换只在 stdin/文件流上做，-c 串不替换！），
# 服务名/密码经 psql 变量（:'var' 字符串转义、:"var" 标识符转义）拼 SQL，杜绝注入面
psql_admin() {
  docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD" semgravio-pg \
    psql -q -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 "$@"
}

# ---- ① 角色：不存在则建，已存在则对齐属性/密码 ------------------------------
if [ "$(psql_admin -v svc="$svc" -tA <<<"SELECT 1 FROM pg_roles WHERE rolname = :'svc'")" != 1 ]; then
  psql_admin -v svc="$svc" <<'SQL'
CREATE ROLE :"svc";
SQL
fi
# 每次重放对齐：登录/密码/三无（超管、建库、建角色）——幂等的另一半
psql_admin -v svc="$svc" -v pw="$DB_PASSWORD" <<'SQL'
ALTER ROLE :"svc" WITH LOGIN PASSWORD :'pw' NOSUPERUSER NOCREATEDB NOCREATEROLE;
SQL
printf '==> 角色 %s 已就绪（属性/密码已对齐）\n' "$svc" >&2

# ---- ② 库：不存在则建，已存在则对齐属主/权限 --------------------------------
if [ "$(psql_admin -v svc="$svc" -tA <<<"SELECT 1 FROM pg_database WHERE datname = :'svc'")" = 1 ]; then
  psql_admin -v svc="$svc" <<'SQL'
ALTER DATABASE :"svc" OWNER TO :"svc";
SQL
else
  psql_admin -v svc="$svc" <<'SQL'
CREATE DATABASE :"svc" OWNER :"svc";
SQL
fi
# 连接权收回到属主：他服务用户对本库 CONNECT 即拒（低权限边界的执行机构）
psql_admin -v svc="$svc" <<'SQL'
REVOKE CONNECT ON DATABASE :"svc" FROM PUBLIC;
SQL
psql_admin -v svc="$svc" <<'SQL'
GRANT ALL PRIVILEGES ON DATABASE :"svc" TO :"svc";
SQL
# 维护库（postgres/template1）CONNECT 从 PUBLIC 收回：新角色对维护库的连接路径
# 一并关掉（角色经由 PUBLIC 获得 CONNECT，按角色收回管不到——必须收 PUBLIC）。
# 超管/属主不受 ACL 影响，运维面不变；模板库复制不经 CONNECT 授权（selftest 建号全链路兜底）
for mdb in postgres template1; do
  psql_admin -v mdb="$mdb" <<'SQL'
REVOKE CONNECT ON DATABASE :"mdb" FROM PUBLIC;
SQL
done
printf '==> 库 %s 已就绪（属主/权限已对齐）\n' "$svc" >&2

# ---- ③ 输出：仅服务自身凭据分片（超管凭据不出 infra） ------------------------
cat <<EOF
DB_HOST=semgravio-pg
DB_PORT=5432
DB_NAME=$svc
DB_USER=$svc
DB_PASSWORD=$DB_PASSWORD
EOF
