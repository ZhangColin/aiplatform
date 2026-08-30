FROM node:22-bookworm

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

# 极简静态文件服务器：demo 预览示意（环境抽象 exposePort 能力）
COPY serve.js /opt/serve.js
