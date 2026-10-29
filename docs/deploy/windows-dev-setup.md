# Windows 开发机落机指南（原生 Windows 路线）

> 读者：换机时的自己。路线（2026-10 实际落地）：**原生 Windows 工具链 + Docker Desktop（WSL backend 仅承载 Docker）**。
> 开发形态＝VSCode + PowerShell 为主，Git Bash 只执行 git 命令；仓库放 C 盘 `C:\Users\zhang\workspace\aiplatform`。
> 旧稿的 WSL2 路线已废弃（当时顾虑的「自编译 pgvector」因 Docker Desktop 就位而不成立）；本地起停脚本已新写 PowerShell 版，仓内 `.sh` 保留给 Linux 服务器（CentOS/Ubuntu 部署侧）。

## 1. 基础层（全装 Windows 侧）

1. JDK 21（Temurin）、Maven、Node 20+、git（Git Bash 随装）。
2. pnpm 不必单独装：独立安装的 pnpm 有 `manage-package-manager-versions` 特性，在 `aiplatform-web` 目录内自动切到 `package.json` 钉的 `pnpm@10.28.2`（实测 `pnpm --version` 输出 10.28.2），lockfile 安全。
3. python：系统 3.15 与 fastembed 不兼容（依赖无 3.15 wheel，`ResolutionImpossible`）。用 **uv** 自管 python：`pip install uv`（其 Scripts 目录已入用户 PATH），embedding 服务经 `uv run --with fastembed --python 3.12` 启动。
4. Docker Desktop（用户目录安装：`AppData\Local\Programs\DockerDesktop`）。

## 2. 数据库（Docker 起 pgvector，不装本机 PG）

- Windows 无 pgvector 官方预编译包（自编译要 MSVC），本机 PG 不取；Docker 官方镜像开箱即用。
- 正口径文件：`aiplatform-server/docker-compose.windows.yml`（5432 直映、`postgres`/`truth` 对齐 application-local.yml → 后端零环境变量；`docker/initdb/` 建第二库）。
- 起法：`docker compose -f docker-compose.windows.yml up -d`（容器名 `aiplatform-pg`，数据在 named volume `pgdata_windows`，容器删了数据仍在）。
- 两库：`aiplatform`（POSTGRES_DB）+ `aiplatform_test`（initdb 脚本，@IntegrationTest 隔离用）；vector extension 由 Flyway V1 自建，无需手工。
- 版本口径 pg16（与 CI 一致；旧 mac 机是本机 PG18）。数据迁移（可选）：旧机 `pg_dump` → `docker exec -i aiplatform-pg psql` 导入；或空库起步让 Flyway 重建。
- `docker-compose.dev.yml`（5434 应急备胎）是 mac 侧口径文件，Windows 上勿用。

## 3. 代码与运行（PowerShell）

1. clone 仓库；`aiplatform-server/.env.local` 从旧机拷（SSO 七项 + `VOLCENGINE_API_KEY` + `BOCHA_API_KEY` + `OPENAPI_APP_*` 等，gitignored）。
2. **cartisan-boot 基座（编译阻塞）**：parent POM `com.cartisan:cartisan-boot:0.1.0-SNAPSHOT` 不在任何公共仓库（pom 亦无 repositories 段），旧机是本地 install 的。新机必须从旧机拷 `~/.m2/repository/com/cartisan/` 整目录到同路径（顺手拷 `~/.m2/settings.xml` 若存在）；或用 cartisan 源码仓重新 `mvn install`。缺它 `mvn clean test-compile` 直接报 Non-resolvable parent POM。
3. 前后端一键拉起：`powershell -ExecutionPolicy Bypass -File scripts/dev.ps1`（后端 8888 + 前端 3333，停旧/健康探测对齐旧 dev.sh 语义；日志在 `%TEMP%\aiplatform-{backend,web}.log`）。后端单独起用 `aiplatform-server/scripts/dev-backend.ps1`。
4. embedding 单独跑（知识库功能才需要）：`cd aiplatform-server; uv run --with fastembed --python 3.12 python scripts/embedding_server.py`；首次拉模型到 `~/.cache/fastembed`，大陆网络可设 hf-mirror 或直接从旧机拷缓存目录（ONNX 模型跨平台通用）。
5. 登录不依赖 identity：访客路径＋验证码登录（通道＝后端日志），详见 `docs/deploy/replit-deployment-brief.md` §1.5。
6. 前端 fresh clone 后：`pnpm install` ＋ `pnpm exec next typegen`——`next-env.d.ts` 与 `.next/types`（`PageProps`/`LayoutProps` 全局泛型）均被 gitignore，需 Next CLI 生成；缺了直接 typecheck 会报 `Cannot find name 'PageProps'`。

## 4. 已知坑与新机差异

- **`.sh` 行尾**：仓库已加 `.gitattributes` 钉 `*.sh eol=lf`（Windows `core.autocrlf=true` 会 checkout 成 CRLF 而炸脚本；服务器部署也依赖 LF）。
- **工作区镜像 chromium（未决）**：`dev-image.Dockerfile` 取 npmmirror legacy zip `chromium-linux-arm64.zip`（revision 1243 与 playwright 1.63 钉死配对）。**该 revision 在 npmmirror 只有 arm64 包，x64 无源**（官方 CDN legacy 路径亦失效）。amd64 新机首次构建 dev 镜像时需解决：容器内配代理走 `playwright install` 官方注册表，或找到可用 x64 zip 后改 Dockerfile（文件内注释已记录约束：升版＝同步改钉版＋zip 版本）。
- 容器内 clone github 卡 TCP：macOS 旧机已知现象；Docker Desktop 网络栈不同，若复现沿用既有判定（用 exitCode 与 `.git` 目录判成败，别信 git init 假绿）。
- 容器 8081 应用重启后要手工拉（restart=unless-stopped 行为同 Linux，见 #168）。
- 验证编译用 `clean test-compile`（增量「Nothing to compile」假绿）；`mvn test` 直跑（@IntegrationTest 已隔离到 aiplatform_test 库）。
- Docker 残留清理沿用 `aiplatform-server/scripts/clean-ws-residue.sh`（判定准绳＝`wsp_workspaces` 注册表并集；在 Git Bash/服务器侧执行）。
- PowerShell 5.1 会把 docker 的 stderr 进度输出显示为 NativeCommandError（`Container xxx Running` 红字），非真错误。

## 5. 验收

1. `mvn clean test-compile` 全绿（aiplatform-server 目录）。
2. dev.ps1 拉起后：首页匿名建一个项目、真跑一个任务（容器置备＋预览 `*.localhost` 可打开）。
3. 验证码登录走通（后端日志读码）。

## 6. 参考

`aiplatform-server/docs/guide/本地开发启动.md`、`生产部署.md`；环境真相总览见 `docs/deploy/replit-deployment-brief.md` §1。
