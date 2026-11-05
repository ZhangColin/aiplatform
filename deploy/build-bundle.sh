#!/usr/bin/env bash
# 组装自包含部署包：dist/aiplatform-deploy-<时间戳>.tar.gz（跨平台：macOS ＋ Windows Git Bash）
# 在开发机仓库根执行：deploy/build-bundle.sh；末行打印包路径（deploy/release.sh 依赖该约定）。
# 产物结构（scp 到服务器任意目录解包，deploy/部署文档.md 的命令均以此为根）：
#   deploy/                    部署文档＋全部 Dockerfile/compose/nginx 配置＋env 模板
#   deploy/nginx/assets/      平台接入共享 nginx 的资产目录（assemble-assets.sh 装配：
#                             标注脚本/提示页/代理/注入块——发布时经门禁第四参装入）
#   aiplatform-server/target/  后端 jar（开发机构建——cartisan-boot 父 POM 只在开发机 ~/.m2）
#   aiplatform-server/scripts/ embedding_server.py（embedding 镜像的构建上下文）
#   aiplatform-web/            前端源码（docker build 上下文，已剔 node_modules 等）
#   workspace/                 工作区镜像构建上下文（baseline/render/seed/入口脚本）
#   gateway/                   网关配置与资产（proxy/inject/unavailable/annotation.js）
#   REVISION                   打包时的 git revision
#   DEV_IMAGE                  后端引用的 dev 镜像 tag（DockerEnvironmentBackend 常量单源提取，
#                              release.sh 服务器侧据此预建，#402）
#
# 源码树用 `git archive` 导出（旧版 rsync 拷工作区，仅 Mac 可跑），三个理由：
#   ① Windows Git Bash 无 rsync，git 两平台都在；
#   ② Windows checkout 是 CRLF（core.autocrlf=true，.gitattributes 只钉了 *.sh），
#      工作区直拷会把 \r 带进 Linux 容器脚本；git archive 出仓库正本，恒 LF；
#   ③ 打包内容＝已提交内容（HEAD），与 REVISION 严格一致。工作区脏则拒绝打包
#      （jar 仍按工作区 mvn 构建，会与源码树不一致）；确认带脏打包：ALLOW_DIRTY=1。
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
  if [ "${ALLOW_DIRTY:-0}" = "1" ]; then
    echo "警告：工作区有未提交改动，源码树按 HEAD 打包、jar 按工作区构建，两者可能不一致" >&2
  else
    echo "错误：工作区有未提交改动（如下）。先提交再打包；确认带脏打包用：ALLOW_DIRTY=1 deploy/build-bundle.sh" >&2
    git status --porcelain >&2
    exit 1
  fi
fi

STAMP=$(date +%Y%m%d-%H%M)
OUT="dist/aiplatform-deploy-${STAMP}"

echo "== 1/4 后端 jar =="
(cd aiplatform-server && mvn clean package -DskipTests -q)

echo "== 2/4 组装目录（git archive，LF 正本）＋ nginx 接入资产 =="
rm -rf "${OUT}"    # 同分钟内重跑兜底
mkdir -p "${OUT}/aiplatform-server/target" "${OUT}/aiplatform-server/scripts" \
         "${OUT}/aiplatform-web" "${OUT}/workspace" "${OUT}/gateway" "${OUT}/deploy"
# git archive 会按本机 core.autocrlf 做 checkout 同款转换（Windows 上＝CRLF，实测踩过），
# 覆盖后输出仓库正本、恒 LF。
ga() { git -c core.autocrlf=false -c core.eol=lf archive "$@"; }
cp aiplatform-server/target/aiplatform-server-1.0.0-SNAPSHOT.jar "${OUT}/aiplatform-server/target/"
ga HEAD:aiplatform-server/scripts embedding_server.py                      | tar -x -C "${OUT}/aiplatform-server/scripts"
ga HEAD:aiplatform-web                                                      | tar -x -C "${OUT}/aiplatform-web"
ga HEAD:aiplatform-server/src/main/resources/docker/workspace              | tar -x -C "${OUT}/workspace"
ga HEAD:aiplatform-server/src/main/resources/docker/gateway                | tar -x -C "${OUT}/gateway"
ga HEAD:deploy                                                              | tar -x -C "${OUT}/deploy"

echo "-- 装配 nginx 接入资产（deploy/nginx/assets/，发布经门禁装入 conf.d/aiplatform.assets/）"
# 三个路径必须绝对：assemble-assets.sh 内部 cd 到自己的 dirname，相对路径会被解析到
# 包内 deploy/nginx/ 下（踩过：workspace/annotation.js 找不到，装配必败）
bash "$PWD/${OUT}/deploy/nginx/assemble-assets.sh" "$PWD/${OUT}/deploy/nginx/assets" "$PWD/${OUT}"

echo "== 3/4 记录 revision ＋ dev 镜像 tag =="
git rev-parse HEAD > "${OUT}/REVISION"
# DEV_IMAGE：后端置备工作区引用的 dev 镜像 tag（DockerEnvironmentBackend 常量，唯一正本），
# 从 HEAD 提取写包根——release.sh 服务器侧据此预建（#402：忘预建则新工作区置备/唤醒重建
# 静默失败）。提取不到唯一值即打包失败：常量改名/多值属发布链断裂，须显式修。
DEV_SRC=aiplatform-server/src/main/java/com/semgravio/aiplatform/base/workspace/infrastructure/docker/DockerEnvironmentBackend.java
# 引号锚定提取（防 0.14-rc1 形被截成数字前缀、绕过多值守卫）；正本＝HEAD，与「打包内容＝
# 已提交内容」口径自洽
DEV_IMAGE=$(git show "HEAD:$DEV_SRC" | grep -oE '"aiplatform/dev:[0-9]+(\.[0-9]+)*"' | tr -d '"' | sort -u)
if [ -z "$DEV_IMAGE" ] || [ "$(printf '%s\n' "$DEV_IMAGE" | wc -l)" -ne 1 ]; then
  echo "错误：$DEV_SRC 未提取到唯一 aiplatform/dev:<tag>（得到：${DEV_IMAGE:-空}）" >&2
  exit 1
fi
# 带脏打包守卫：jar 按工作区构建、tag 按 HEAD 提取——常量改了没提交则两者漂移，恰好复现
# 本票要消灭的「静默缺镜像」，拒绝带脏出货
if [ "${ALLOW_DIRTY:-0}" = "1" ]; then
  DEV_WT=$(grep -oE '"aiplatform/dev:[0-9]+(\.[0-9]+)*"' "$DEV_SRC" | tr -d '"' | sort -u)
  [ "$DEV_WT" = "$DEV_IMAGE" ] \
    || { echo "错误：ALLOW_DIRTY 下 $DEV_SRC 的镜像 tag 工作区（$DEV_WT）与 HEAD（$DEV_IMAGE）不一致——先提交再打包" >&2; exit 1; }
fi
printf '%s\n' "$DEV_IMAGE" > "${OUT}/DEV_IMAGE"
echo "DEV_IMAGE=$DEV_IMAGE"

echo "== 4/4 打包 =="
tar -C dist -czf "${OUT}.tar.gz" "aiplatform-deploy-${STAMP}"
rm -rf "${OUT}"
du -sh "${OUT}.tar.gz"
echo "完成，包路径："
echo "${OUT}.tar.gz"
