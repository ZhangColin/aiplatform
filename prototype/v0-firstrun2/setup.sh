#!/bin/sh
# 建一个干净实验容器并落题面，然后由「平台」把开发服务器起好（#590 第 9 条）。
# 用法: sh setup.sh <name> <hostport> <prd-file> <label>
NAME=$1; PORT=$2; PRD=$3; LABEL=$4
IMAGE=aiplatform/dev:0.18

docker rm -f "$NAME" >/dev/null 2>&1
docker run -d --name "$NAME" -p "$PORT:8081" "$IMAGE" sleep infinity >/dev/null || { echo "run failed"; exit 1; }

# 等基座模板就位、数据库起来
i=0
until docker exec "$NAME" sh -c 'test -f /workspace/package.json && pg_isready -h localhost -p 5432 -q' 2>/dev/null; do
  i=$((i+1)); [ $i -ge 60 ] && { echo "init timeout"; exit 1; }; sleep 2
done

docker exec "$NAME" sh -c "printf 'DATABASE_URL=postgres://postgres:$LABEL@localhost:5432/postgres\nREDIS_URL=redis://localhost:6379\n' > /workspace/.env"
docker exec "$NAME" sh -c 'mkdir -p /workspace/docs'
docker cp "$PRD" "$NAME:/workspace/docs/PRD.md" || { echo "docker cp failed"; exit 1; }

# 开发服务器由平台起、常驻（与 #576 的差别只有这一处：那时是模型自己起）
docker exec -d "$NAME" sh -c 'cd /workspace && pnpm dev > /tmp/app.log 2>&1'

i=0
until docker exec "$NAME" sh -c 'curl -s -o /dev/null --max-time 10 http://localhost:8081/' 2>/dev/null; do
  i=$((i+1)); [ $i -ge 90 ] && { echo "dev server timeout"; docker exec "$NAME" sh -c 'tail -n 30 /tmp/app.log'; exit 1; }; sleep 2
done
CODE=$(docker exec "$NAME" sh -c "curl -s -o /dev/null -w '%{http_code}' --max-time 30 http://localhost:8081/")
echo "$NAME ready (http://localhost:$PORT -> $CODE)"
