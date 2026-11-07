# dev 工作区镜像·部署变体（跨架构通用：amd64/arm64 实测可用；原 dev-image.Dockerfile 不动，Mac arm64 单机形态）。
# 差异两处——
# ① chromium 来源：原文件钉 npmmirror builds/chromium/1243 arm64 zip；该目录无 x64 包、
#   playwright 官方 CDN 大陆服务器不可达（2026-10 实测 400），x64 改用 bookworm apt
#   chromium（≈15x，playwright 1.63 CDP 兼容面可用、launch 默认不开沙箱无需加参；
#   渲染器属设计线资产、非系统 Demo 关键路径）。RENDER_CHROMIUM 指向 /usr/bin/chromium。
# ② 基座模板 pnpm install 也显式走 npmmirror（服务器构建）。
# 其余层（基座/种子/入口/静态服务）与原 Dockerfile 一致。
# 构建上下文＝aiplatform-server/src/main/resources/docker/workspace/：
#   docker build -f deploy/dev-image.deploy.Dockerfile -t aiplatform/dev:0.15 \
#     aiplatform-server/src/main/resources/docker/workspace/
FROM node:22-bookworm

# 单容器 all-in-one（ADR 0001）：应用运行时（node）与中间件（pg/redis）同容器；
# /workspace 为唯一持久卷。git 显式安装（收口自动成版走容器内 git）。
# 换源提前到第一层——原文件的换源在渲染层才做，第一层走 debian
# 默认源在大陆机上实测卡滞 15 分钟+（Mac 构建有层缓存从未暴露）。换源后不还原：
# 后续层（chromium apt）同走 tuna；海外机构建 tuna 亦可达、无需分支。
RUN cp /etc/apt/sources.list.d/debian.sources /tmp/debian.sources.orig \
    && sed -i 's|http://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-15 postgresql-client-15 redis-server git \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g pnpm@10

# 渲染层（部署变体）：playwright 库走 npmmirror、chromium 二进制走 apt（源已在第一层
# 换为 tuna）、CJK 字体保底（Noto 是设计交付线质量底线）。
COPY render/ /opt/render/
RUN apt-get update \
    && apt-get install -y --no-install-recommends chromium fonts-noto-cjk \
    && cd /opt/render && npm install --registry=https://registry.npmmirror.com \
    && rm -rf /var/lib/apt/lists/*
ENV RENDER_CHROMIUM=/usr/bin/chromium

# 基座工程模板（#113 / ADR-0013）：构建期装好依赖，工作区初始化＝模板就位。
# store-dir 钉 /workspace/.pnpm-store（卷上独立文件系统，防 UNEXPECTED_STORE）。
# #421 起构建期 pnpm test 跑基座健壮件行为契约测试（随镜像构建验证，与 dev 变体同口径）。
COPY baseline/ /opt/baseline/
RUN cd /opt/baseline && pnpm install --store-dir /workspace/.pnpm-store --registry=https://registry.npmmirror.com && pnpm test

# 演示种子数据（#340）
COPY seed/ /opt/seed/
RUN chmod +x /opt/seed/apply.sh

COPY init-workspace.sh /opt/init-workspace.sh
RUN chmod +x /opt/init-workspace.sh
ENTRYPOINT ["/opt/init-workspace.sh"]
CMD ["sleep", "infinity"]

COPY serve.js /opt/serve.js
