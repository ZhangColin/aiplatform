# Windows 开发机落机指南（WSL2 路线）

> 读者：换机时的自己。路线已定：**WSL2 ＋ Docker Desktop**。开发形态＝「后端直接跑在一台装了 Docker 的 Linux 宿主机上」，WSL2 就是那台 Linux，现有脚本与习惯原样平移；原生 Windows 需移植全部 bash 脚本＋自编译 pgvector，不取。

## 1. 基础层（全在 WSL 内）

1. `wsl --install -d Ubuntu`（22.04/24.04）；Windows 侧装 Docker Desktop（WSL backend）。
2. WSL 内装：Temurin JDK 21、Maven、Node 20（nvm）、pnpm@10、python3＋pip（`pip install fastembed`）。
3. git 与 SSH key 在 WSL 内重新配置（私仓 GitHub 权限）。
4. **仓库放 WSL 文件系统内**（`~/…`），不要放 `/mnt/c`（跨文件系统 IO 慢一个量级）。

## 2. 数据库（绕开 Windows 编译 pgvector）

- 用仓里现成备胎镜像：`pgvector/pgvector:pg16`（见 `aiplatform-server/docker-compose.dev.yml`，端口映射改/用 5432）。
- 建库 `aiplatform` 与 `aiplatform_test`，用户 `postgres`/密码 `truth`（与 application-local.yml 一致 → 零环境变量）。
- 版本说明：旧机是本机 PG18，Docker 路线为 PG16（与 CI 备案一致）；Flyway 已应用集兼容。
- 数据迁移（可选）：旧机 `pg_dump` → 新机 `psql` 导入；或空库起步让 Flyway 重建。

## 3. 代码与运行

1. clone 仓库；`aiplatform-server/.env.local` 从旧机拷（`VOLCENGINE_API_KEY`、`DEEPSEEK_API_KEY`、`BOCHA_API_KEY`、`ARK_API_KEY`、`IMAGE_GEN_PROVIDER` 等）。
2. `scripts/dev.sh` 一键起后端（8888）＋前端（3333）。
3. embedding 单独跑：`python3 aiplatform-server/scripts/embedding_server.py`；首次拉模型到 `~/.cache/fastembed`，大陆网络可设 hf-mirror 或直接从旧机拷缓存目录。
4. 登录不依赖 identity：访客路径＋验证码登录（通道＝后端日志），详见 `docs/deploy/replit-deployment-brief.md` §1.5；dev.sh 对 10002 的探测失败可忽略。

## 4. 已知坑与新机差异

- **工作区镜像 chromium**：新机是 amd64，arm64 zip 要换包（`dev-image.Dockerfile` 内注释指明位置；与 Replit 外部 host 同一笔资产）。
- 容器内 clone github 卡 TCP：macOS 旧机已知现象；WSL/Docker Desktop 网络栈不同，若复现沿用既有判定（用 exitCode 与 `.git` 目录判成败，别信 git init 假绿）。
- macOS bash 3.2 的全角变量坑在 WSL bash5 自然消失。
- 容器 8081 应用重启后要手工拉（restart=unless-stopped 行为同 Linux，见 #168）。
- 验证编译用 `clean test-compile`（增量「Nothing to compile」假绿）；`mvn test` 直跑（@IntegrationTest 已隔离到 aiplatform_test 库）。
- Docker 残留清理沿用 `aiplatform-server/scripts/clean-ws-residue.sh`（判定准绳＝`wsp_workspaces` 注册表并集）。

## 5. 验收

1. `mvn clean test-compile` 全绿。
2. dev.sh 拉起后：首页匿名建一个项目、真跑一个任务（容器置备＋预览 `*.localhost` 可打开）。
3. 验证码登录走通（后端日志读码）。

## 6. 参考

`aiplatform-server/docs/guide/本地开发启动.md`、`生产部署.md`；环境真相总览见 `docs/deploy/replit-deployment-brief.md` §1。
