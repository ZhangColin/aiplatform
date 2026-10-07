FROM node:22-bookworm

# 单容器 all-in-one（ADR 0001）：编码智能体与应用运行时（node）与中间件（pg/redis）
# 同容器不拆分；/workspace 为唯一持久卷、容器无状态——pg 数据落 PGDATA=/workspace/data/pg，
# 销毁重建由入口脚本自愈。pg 取 bookworm 发行版的 postgresql-15（旧独立容器为
# pgvector:pg16——沙箱应用用不上 vector 扩展，跟随发行版省一层外部 apt 源；
# 版本差异对生成应用透明）。编码智能体 = AgentScope 进程内单栈（平台侧运行），
# 容器只承载应用运行时与中间件，不装智能体 CLI。git 显式安装（版本层 #91：
# 收口自动成版走容器内 git；基座 buildpack-deps 隐式自带，显式声明防漂移）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-15 postgresql-client-15 redis-server git \
    && rm -rf /var/lib/apt/lists/*

# pnpm（基座工程包管理器，#113）：依赖在镜像构建期装好，工作区初始化 = 模板就位，
# 非项目内现场 install
RUN npm install -g pnpm@10

# 位图出口渲染器（#284，ADR-0026/0027）：/opt/render 工程（playwright＋@resvg/
# resvg-js，版本钉死——Playwright 与浏览器二进制强绑定，升版＝同步改钉版＋
# chromium zip 版本＋随 DEV_IMAGE 升版重建镜像）。chromium 二进制取 npmmirror 的
# legacy 路径 zip（builds/chromium/1243，与 playwright 1.63 的 revision 严格配对）
# 解压到 /opt/chrome，运行期经 RENDER_CHROMIUM 显式 executablePath 启动——不走
# playwright install 的注册表下载（1.63 起 chromium 走 builds/cft/ 新路径，
# npmmirror 未同步、官方 CDN 本机实测网关故障；旧路径 zip 同源同版可用）。
# 渲染依赖（系统库）经 playwright install-deps 落层（纯 apt、零浏览器下载）；
# fonts-noto-cjk 补 CJK 正文字体（install-deps 自带 wqy-zenhei 可用，Noto 是
# 设计交付线的质量底线）。代价如实：chromium 解压 ~490MB、层压缩 ≈ +0.4–0.5GB。
# 画布呈现不经它——设计稿 tab 呈现＝用户浏览器 iframe live 渲染零 chromium，
# 与出口分家（出口＝出稿即渲 PNG / 下载位图化 / 导出衍生）。SVG→PNG 走 resvg
# 零浏览器旁路（render-svg.mjs，不触 chromium）。构建期网络三处走国内镜像
# （本机对默认 CDN/debian 源卡滞是既知事实，实测 54KB/s vs 镜像 8.6MB/s；
# 研究文档 2026-10-03 备案解法）：npm registry、浏览器 zip 走 npmmirror，apt 走
# tuna（sed 换源、层末还原——不留镜像配置残留）。chromium zip 是 arm64 命名的
# 本机单机形态（dev 镜像只在本机构建不外推；amd64 主机构建须换对应 zip）。
# 层序：渲染层在基座模板层之前（#296 起）——基座依赖演进（如 @shadcn/lint＋
# oxlint）只重跑基座 pnpm install，不再触发 chromium 重下载。
COPY render/ /opt/render/
RUN cp /etc/apt/sources.list.d/debian.sources /tmp/debian.sources.orig \
    && sed -i 's|http://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && cd /opt/render && npm install --registry=https://registry.npmmirror.com \
    && curl -sL https://cdn.npmmirror.com/binaries/playwright/builds/chromium/1243/chromium-linux-arm64.zip -o /tmp/chromium.zip \
    && mkdir -p /opt/chrome \
    && unzip -q /tmp/chromium.zip -d /opt/chrome \
    && rm /tmp/chromium.zip \
    && test -x /opt/chrome/chrome-linux/chrome \
    && npx playwright install-deps chromium \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && mv /tmp/debian.sources.orig /etc/apt/sources.list.d/debian.sources \
    && rm -rf /var/lib/apt/lists/*
ENV RENDER_CHROMIUM=/opt/chrome/chrome-linux/chrome

# 基座工程模板（#113 / ADR-0013）：固定技术栈（TypeScript / Next.js / React 19 /
# pnpm / shadcn / PostgreSQL / Redis）的只读正本。构建期 pnpm install 把依赖装进
# 镜像层；工作区初始化时由 init-workspace.sh 首次就位复制到 /workspace，生成 run
# 在基座上增量生长、跳过选型与初始化。devDependencies 含 @shadcn/lint＋oxlint
# （#296 遵守三件套③——带规范项目收口 token 合规扫描的容器内执行件；无规范项目
# 只是装而不用，行为零改变）。
# store-dir 钉在 /workspace/.pnpm-store：/workspace 是独立卷（独立文件系统），
# pnpm 在卷上会自动改用项目内 store；若构建期走默认全局 store（/root/.local），
# 复制就位后 node_modules 的 storeDir 记录与运行时不一致，执行体 pnpm add（长尾
# 依赖 / shadcn add）会报 UNEXPECTED_STORE。预先对齐 storeDir，就位后 pnpm add
# 只增量下载新依赖、不复装全部。
COPY baseline/ /opt/baseline/
RUN cd /opt/baseline && pnpm install --store-dir /workspace/.pnpm-store

# 工作区自愈入口：布局骨架 + 基座模板就位 + 容器内 pg/redis 幂等起服务后 exec 交还启动命令
COPY init-workspace.sh /opt/init-workspace.sh
RUN chmod +x /opt/init-workspace.sh
ENTRYPOINT ["/opt/init-workspace.sh"]
CMD ["sleep", "infinity"]

# 极简静态文件服务器：demo 预览示意（环境抽象 exposePort 能力；快照「查看当时」
# 静态兜底 + 集成测试探针）。纯静态职责——预览注入由网关 sub_filter 单源承担
# （ADR-0014，#138 删旧内联注入路径），标注脚本资产不进镜像。
COPY serve.js /opt/serve.js
