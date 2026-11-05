# Live 车道防腐约定

触碰 workspace/docker/探针缝的票，收尾前必须在本地跑相关 live 测试类，并在票内评论留结果。本文是该口径的正本（#401 起约定），后续票可直接引用。

## 为什么需要

live 车道（`-P live`）只在本机手跑、CI 永不执行——缝被改而无活体复核时，测试断了不红，断裂**长期静默**（实证：#397 期间发现 `WorkspaceWakeupLiveTest.WRITE_SERVER_JS` 因 argv 双引号被剥而断裂，长期无人知晓；#396 排练单发 curl 高负载假红、#397 快照 30s 窗被 next dev 冷编译拖爆而单跑即绿——live 车道的环境性红也需法证分离，不能因「live 会环境性红」而不跑）。

## 约定（must）

1. **判定**：票的改动触到下列任一缝，即适用本约定——`DockerEnvironmentBackend`（命令构造/探针/守卫）、网关资产（`docker/gateway/*` 挂载清单、nginx conf）、探针形态与判据、docker 包测试假面（`ProbeShape` 及其消费方）、`ThrowawayPreviewGateway`。
2. **跑**：收尾前本地跑被改缝的**直接消费者** live 类（映射见下），最低一门；改动面横跨多缝时相应叠加。
3. **留痕**：在票内评论结果——跑过的类名、绿/红；红要区分环境性红（说明判据）与真红（须修完再收口）。

## 缝 → live 类映射

| 被改缝 | 直接消费的 live 类 |
| --- | --- |
| 收口探针/网关判据/网关资产/注入 | `PreviewGatewaySmokeTest`（真 nginx×真探针端到端） |
| 唤醒幂等重建/startApp/预览恢复 | `WorkspaceWakeupLiveTest` |
| 封存/深度唤醒/卷打包回卷 | `WorkspaceSealLiveTest` |
| serve.js 静态透传（快照兜底同路径） | `ServeJsStaticPassthroughTest`（本机 node） |
| runCapture 管道/argv 守卫 | `DockerEnvironmentBackendRunCaptureTest`（默认车道已含，宜复跑） |

跑法：`cd aiplatform-server && mvn test -P live -Dtest=<类名>`。docker daemon 不在则活体用例自动跳过（Assumptions）——跳过不算「跑过」，须在真 daemon 在的机器上出结果。

## ①族：Windows argv 双引号（平台性口径，防再争论）

- **平台性确定**：Windows 上 Java `ProcessBuilder` 走 `CreateProcess` 平面命令行、对参数内嵌 `"` 不转义；docker.exe（Go/MSVCRT 规则）重切 argv 时把引号吃掉，`sh -c` 命令**静默变形**。这是任何「Windows JVM→docker.exe」链的确定行为，**换机不消失**，不是单机怪癖。
- **生产免疫**：Linux `execve` 传 argv 数组逐字节原样，`sh -c` 内双引号是合法常用写法——生产不受影响。
- **因此防呆必须 `os.name` 条件化**（`DockerEnvironmentBackend.isWindowsHost`）：Linux 不得一刀切禁双引号。`runCapture`/`runCaptureBinary` 在 Windows 下对含内嵌双引号的参数 **fail-fast**（WSP_018，文案指路 #399）。
- **命令书写纪律**：进容器的 shell 命令一律**单引号**形态——多行内容用 heredoc 单引号定界（`<<'EOF'`，见 `WorkspaceWakeupLiveTest.WRITE_SERVER_JS`、`PreviewGatewaySmokeTest` 的 HTML 落盘），SQL 字符串字面量用 PG 美元引号（`$q$…$q$`）避免嵌套单引号；shell 变量中间层消灭（路径 Java 侧单引号内联，派生路径 Java 侧算，见 `ContainerCommands`），唯一合法例外是 stat 纯数字回执（`s=$(stat -c %s '…')` 后 `$s` 无引号用——数字无空白/元字符）。
- **守卫边界**：防呆只盖 `EnvironmentBackend` 缝（`DockerEnvironmentBackend` 的全部 spawn 点）。AgentScope 的 `DockerExecFilesystem.DockerExecCommand` 是**独立 spawn 缝、不设防**——智能体（LLM）自写命令合法需要双引号（`"$VAR"`、`$(…)` 惯用法），防了就不可重写；其 Windows 变形是另一条线的问题（提示词侧教词适配，另行立项）。

## ②族：Windows 管道排水（#400 家族的测试侧漏网）

测试代码里 spawn docker/tar 等 CLI 子进程时，**不读输出的 `waitFor()` 在 Windows 上必死锁**：匿名管道仅 ~4KB（Linux 64KB），子进程 stdout 超容量即写阻塞、永不退出（`docker inspect` JSON ~7.4KB 实证挂死 `WorkspaceSealLiveTest.awaitDeepWoken`，#401 活体排查撞上；同机曾留一条挂死多日的 `docker build` 僵尸同因）。纪律：要输出就**读完再 waitFor**，不要输出就 `redirectOutput/error(DISCARD)` 直落 NUL——无管道即无死锁。生产 `runCapture` 已在 #400 修；测试 helper 新写时照此。
