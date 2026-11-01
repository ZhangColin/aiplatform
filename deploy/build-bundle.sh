#!/usr/bin/env bash
# 组装自包含部署包：dist/aiplatform-deploy-<时间戳>.tar.gz
# 在开发机仓库根执行：deploy/build-bundle.sh
# 产物结构（scp 到服务器任意目录解包，deploy/部署文档.md 的命令均以此为根）：
#   deploy/                    部署文档＋全部 Dockerfile/compose/nginx 配置＋env 模板
#   aiplatform-server/target/  后端 jar（开发机构建——cartisan-boot 父 POM 只在开发机 ~/.m2）
#   aiplatform-server/scripts/ embedding_server.py（infra compose 的构建上下文）
#   aiplatform-web/            前端源码（docker build 上下文，已剔 node_modules 等）
#   workspace/                 工作区镜像构建上下文（baseline/render/seed/入口脚本）
#   gateway/                   网关配置与资产（proxy/inject/unavailable/annotation.js）
#   REVISION                   打包时的 git revision
set -euo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M)
OUT="dist/aiplatform-deploy-${STAMP}"

echo "== 1/4 后端 jar =="
(cd aiplatform-server && mvn clean package -DskipTests -q)

echo "== 2/4 组装目录 =="
mkdir -p "${OUT}/aiplatform-server/target" "${OUT}/aiplatform-server/scripts"
cp aiplatform-server/target/aiplatform-server-1.0.0-SNAPSHOT.jar "${OUT}/aiplatform-server/target/"
cp aiplatform-server/scripts/embedding_server.py "${OUT}/aiplatform-server/scripts/"
rsync -a --exclude node_modules --exclude .next --exclude tsconfig.tsbuildinfo \
  aiplatform-web/ "${OUT}/aiplatform-web/"
rsync -a aiplatform-server/src/main/resources/docker/workspace/ "${OUT}/workspace/"
rsync -a aiplatform-server/src/main/resources/docker/gateway/ "${OUT}/gateway/"
rsync -a --exclude .env --exclude '*.pem' deploy/ "${OUT}/deploy/"

echo "== 3/4 记录 revision =="
git rev-parse HEAD > "${OUT}/REVISION" 2>/dev/null || echo "(dirty)" > "${OUT}/REVISION"

echo "== 4/4 打包 =="
tar -C dist -czf "${OUT}.tar.gz" "aiplatform-deploy-${STAMP}"
rm -rf "${OUT}"
du -sh "${OUT}.tar.gz"
echo "完成：${OUT}.tar.gz"
