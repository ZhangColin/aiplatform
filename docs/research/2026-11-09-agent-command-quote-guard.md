# 智能体自产命令 ①族守卫：止损数据前后对照（#440）

日期：2026-11-09
票：#440（parent #432；暴露源 #434 单会话 60 分钟连排排练）

## 问题

#434 排练实测：run 执行体经 ShellExecuteTool 自产的 shell 命令无 ①族守卫，
Windows JVM→docker.exe argv 编码链剥掉内嵌双引号后 sh 引号不配对、从 stdin
**无界挂死**——ord=2 单点挂死 **35 分钟**（2,112 s），吃掉 60 分钟排练预算的
**58%**。平台自产命令面当时已有 #399/#401 防呆（WSP_018），智能体面是盲区；
止损完全依赖 PT30M 工具窗兜底（代价过高），人工杀 exec 客户端后链路可自愈但
为时已晚。

## 修复形态（两层同修）

1. **执行层（根治）**：`DockerExecFilesystem.execute()`（agentscope 壳面，
   ShellExecuteTool 唯一通道）执行前 ①族 fail-fast——Windows 宿主
   （`os.name` 条件化，Linux execve 免疫不启用）下命令含**内嵌双引号**或
   **`$(` 命令替换**即拒：不触容器缝，回合成退出码 **250**（刻意避开 124
   超时、125-127 docker 自有带）+ 改写指引文案（单引号形态 / source .env
   取值的合规写法），智能体改写重试即可自愈。
2. **提示层（短平快）**：执行体工作协议新增第 12 条命令纪律（一律单引号、
   禁内嵌双引号与 `$()`、多行内容用 write_file）；verify 子智能体正文 db 面
   教学命令从 `psql "$(grep … | cut …)"` 改写为合规形态
   `set -a; . /workspace/.env; psql $DATABASE_URL -c 'SELECT …'`。

## 止损数据（前后对照）

| 形态 | 同类违规命令的结局 | 实测数据 |
| --- | --- | --- |
| 修复前（#434 排练） | 无界挂死 → PT30M 工具窗判死 → 尝试环耗尽 → 片失败 | 单点 **2,112 s（35 min）**，吃 60 min 预算 58% |
| 修复后（#440 live 用例） | 秒级拒命（本地判定、零 I/O、不 spawn）→ 智能体按指引改写重试成功 | 拒命实测 **<1 ms**（live 用例打印 0 ms；整例含真容器起停 18.65 s） |

## 验证入口

- live：`DockerExecFilesystemQuoteGuardLiveTest`（真 dev 工作区 + 生产实例；
  Windows 分支＝挂死原形秒拒 + `$(` 同拒 + 单引号改写成功路径，Linux 分支＝
  守卫不启用行为不变）。
- fast：`DockerExecFilesystemTest` 守卫 4 例（拒命/两违规形态/单引号透传/
  非 Windows 透传）；`DockerEnvironmentBackendQuoteGuardTest` 平台防呆不回归
  （本票未触碰 `DockerEnvironmentBackend`）。
- 提示层钉死：`AgentProfileTest`（执行体第 12 条在位）、
  `ProfileSubagentSupplierTest`（psql 教学形态合规、旧 `"$("` 反形态绝迹）。

## 平台性口径备注

守卫与平台防呆同族：`os.name` 条件化（换机不消失、Linux 生产免疫）；智能体面
`$(` 一律禁用、无 stat 数字回执例外（该例外仅属平台自产命令缝，
见 docs/agents/live-test-lane.md §①族）。
