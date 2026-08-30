FROM node:22-bookworm

# 单容器 all-in-one（ADR 0001）：编码智能体与应用运行时（node）与中间件（pg/redis）
# 同容器不拆分；/workspace 为唯一持久卷、容器无状态——pg 数据落 PGDATA=/workspace/data/pg，
# 引擎会话数据经 XDG_DATA_HOME=/workspace/.platform 重定向，销毁重建由入口脚本自愈。
# pg 取 bookworm 发行版的 postgresql-15（旧独立容器为 pgvector:pg16——沙箱应用用不上
# vector 扩展，跟随发行版省一层外部 apt 源；版本差异对生成应用透明）。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-15 postgresql-client-15 redis-server \
    && rm -rf /var/lib/apt/lists/*

# 编码智能体 CLI 工具箱（平台侧多引擎适配层已删，CLI 留作生成环活体对照的
# 旧路径运行环境——AgentScope × opencode 对照门通过后随收尾片删除）。
RUN npm install -g opencode-ai --no-audit --no-fund

# 预装自定义 provider 依赖：opencode 首次使用自定义 provider 时会现场 npm 安装
# @ai-sdk/openai-compatible（同步阻塞、可能数分钟——新容器第一条消息卡死的元凶）。
# 预装进镜像后首条消息即秒回。
RUN npm install -g @ai-sdk/openai-compatible --no-audit --no-fund

# DeepSeek Harness / DSH（headless 一次性任务模式：`dsh --profile headless "<任务>"`，
# 打印最终回复后退出——无监听端口、无交互提问 surface）。模型/推理档位经
# $DSH_HOME/settings.yaml 注入，API Key 走容器环境变量 DEEPSEEK_API_KEY。
RUN npm install -g @deepseek-ai/dsh --no-audit --no-fund

# 工作区自愈入口：布局骨架 + 容器内 pg/redis 幂等起服务后 exec 交还启动命令
COPY init-workspace.sh /opt/init-workspace.sh
RUN chmod +x /opt/init-workspace.sh
ENTRYPOINT ["/opt/init-workspace.sh"]
CMD ["sleep", "infinity"]

# 极简静态文件服务器：demo 预览示意（环境抽象 exposePort 能力）
COPY serve.js /opt/serve.js
