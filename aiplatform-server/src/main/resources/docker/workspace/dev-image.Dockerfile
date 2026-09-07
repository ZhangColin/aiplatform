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

# 基座工程模板（#113 / ADR-0013）：固定技术栈（TypeScript / Next.js / React 19 /
# pnpm / shadcn / PostgreSQL / Redis）的只读正本。构建期 pnpm install 把依赖装进
# 镜像层；工作区初始化时由 init-workspace.sh 首次就位复制到 /workspace，生成 run
# 在基座上增量生长、跳过选型与初始化。
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

# 极简静态文件服务器：demo 预览示意（环境抽象 exposePort 能力）
COPY serve.js /opt/serve.js

# 平台预览标注脚本（#97 圈注 B 档）：serve.js 对 HTML 响应内联注入的资产
COPY annotation.js /opt/annotation.js
