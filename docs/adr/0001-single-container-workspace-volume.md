# 单容器 all-in-one 运行环境与 workspace 唯一持久卷

沙箱中为用户生成的系统是真实可用的 TS 全栈应用（含 pg 等中间件）。我们决定**一个系统 = 一个 docker 实例**：应用与中间件同容器、不拆分；`/workspace` 是唯一持久卷——数据目录（`PGDATA`、`XDG_DATA_HOME` 等）全部映射其中，容器本身无状态、可随时销毁重建而不丢数据。理由：拆碎容器在管理上得不偿失；平台止于支付、系统为单项目形态，无需容器级弹性隔离；数据落 workspace 使失败自动重试（#3 决议）不丢用户已见数据。

## Considered Options

- **一 network + pg/redis 独立容器（Phase A 现状）**：资源隔离更细，但置备/重建/排障面成倍扩大，且 pg 数据挂独立卷违背「workspace 唯一持久根」，被否。
- **按需 attachResource 动态挂载**：接口已预留、未实现；对固定的 TS 全栈栈无必要，v1 被否。

## Consequences

- 系统技术栈由平台约定为 TypeScript 全栈，基础镜像相对固定；异构中间件需求真实出现时重开本决议。
- 置备链路需从多容器改造为单容器（含 `PGDATA` / `XDG_DATA_HOME` 归位修复两处现状违背），由后端裁剪与重组方案（#8）消化。
- 引擎与 workspace 的绑定取「根与约定」（`WorkspaceHandle` + 布局常量表），不建文件面网关——见 #12 调研，最终契约另行落定。

依据：GitHub issues #3（决议出处）、#12（现状核实与修复方式）。
