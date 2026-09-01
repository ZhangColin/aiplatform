FROM node:22-bookworm

# 单容器 all-in-one（ADR 0001）：编码智能体与应用运行时（node）与中间件（pg/redis）
# 同容器不拆分；/workspace 为唯一持久卷、容器无状态——pg 数据落 PGDATA=/workspace/data/pg，
# 销毁重建由入口脚本自愈。pg 取 bookworm 发行版的 postgresql-15（旧独立容器为
# pgvector:pg16——沙箱应用用不上 vector 扩展，跟随发行版省一层外部 apt 源；
# 版本差异对生成应用透明）。编码智能体 = AgentScope 进程内单栈（平台侧运行），
# 容器只承载应用运行时与中间件，不装智能体 CLI。
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        postgresql-15 postgresql-client-15 redis-server \
    && rm -rf /var/lib/apt/lists/*

# 工作区自愈入口：布局骨架 + 容器内 pg/redis 幂等起服务后 exec 交还启动命令
COPY init-workspace.sh /opt/init-workspace.sh
RUN chmod +x /opt/init-workspace.sh
ENTRYPOINT ["/opt/init-workspace.sh"]
CMD ["sleep", "infinity"]

# 极简静态文件服务器：demo 预览示意（环境抽象 exposePort 能力）
COPY serve.js /opt/serve.js
