# 版本层「查看当时」快照容器：同卷双 PG 冲突取临时复制数据目录解路

「查看当时」（#75 ⑥）= 运行态快照容器跑当时代码 + 现在数据（对齐「只逛不换」）。主沙箱是单容器 all-in-one（ADR 0001）：`PGDATA` 落工作区卷内 `/workspace/data/pg`，入口脚本每次启动对既有卷幂等起 pg——快照容器与主容器同卷并存即双 PG 冲突。经本机 docker 活体实验（2026-09-04，docker 29.2 / `aiplatform/dev:0.6` / PG 15.19，证据数字见文末）决断：**快照容器挂同一工作区卷（只读）、自带 PG 跑在容器本地目录的数据目录副本上；查看会话结束销毁容器，副本随容器可写层消失**。主容器的 pg、卷与数据零暴露、零扰动，快照侧写入只落副本——「只逛不换」由物理隔离兜底。

## Considered Options

- **解路一：快照容器经容器网络连主容器 pg**——机械上可行（实验证实：主 pg 扩监听 + 工作区专属网络 + DNS 连接，快照应用 0.6s 起服读到主库数据；主容器除扩监听配置所需的一次性 pg 重启外无扰动），但拒：
  - **写入语义致命**：经快照应用的写入直达主库当前数据（实验实测插入落主库）；生成的应用普遍启动即跑迁移，老迁移对上新 schema 可回改/删列——老代码直写现在数据违背「只逛不换」。
  - 需主 pg 监听扩出回环（`listen_addresses` 变更要重启，实测 0.27s 停机窗）+ `pg_hba` 放行 + 专属网络生命周期（`WorkspaceNaming.networkName` 是残留命名、物理网络从未创建，需复活整套创建/挂接/销毁）——trust 认证的暴露面与置备面同涨。
  - 快照生命期耦合主容器 pg：主容器销毁重建（置备幂等预清的常规路径）即断。
  - 附带语义瑕疵：连过去的「现在数据」是活的——旧界面里看实时演化的数据，比「打开那一刻的现在」更怪。
- **解路二：临时复制数据目录给快照容器自带 pg**——选定。代价与限制见 Consequences。
- **变体：`--network container:` 共享网络命名空间**（免扩监听、快照以 localhost 连主 pg）——docker 明确拒绝该模式与 `-p` 端口发布并存（实验取证：`conflicting options: port publishing and the container type network mode`），快照预览端口映射无解，出局。
- **变体：`pg_dump` 逻辑复制 / `pg_backup_start` 物理备份替代文件直拷**——一致性更严但时延与复杂度更高，v1 不必（见已知限制的加固选项），不作主路。

## Consequences

- **实施配方**（#92 照单开工，无残留设计不确定点；开工顺序依 #75 ⑥ 实施序——#91 容器内 git 地基先行，配方第 4 步的当时代码来源由其供给）：
  1. 快照容器 = 同镜像、同工作区卷挂 **`:ro`**、入口脚本旁路（`--entrypoint sleep`）、独立随机预览端口映射；
  2. 启动序列由平台经 exec 确定性驱动：复制 `/workspace/data/pg` → 容器本地目录（如 `/tmp`，随容器消失）→ 删副本内 `postmaster.pid` → `su postgres` 起 pg → 起容器内 redis（回环、纯缓存）→ 按当时代码起应用（`DATABASE_URL` 指容器内 localhost——与主容器 `.env` 同形，应用侧零适配）；
  3. **不得**用「起容器→等 `pg_isready`→外部再换数据目录」拼装时序（实验翻车实录：入口脚本 `createdb` 未收尾被外部 stop 打断，`set -eu` 令入口脚本退出、容器死亡——就绪探针 ≠ 入口脚本收尾）；
  4. 当时代码来源 = 容器内 git（#91）：检出当轮 commit 到快照容器本地目录跑，`node_modules` 独立安装（卷 `:ro` 天然不与主容器工作树互踩）；
  5. 销毁 = `docker rm -f`，无任何销毁协调；并挂工作区销毁级联（主容器销毁/重建时在途查看会话随之销毁）。
- **已知限制**（票面三问逐项）：
  - **数据规模**：复制时长线性于 PGDATA 体积（实测 41MB≈0.4s、175MB≈2.5s，本机 NVMe），每次查看一份副本占磁盘。v1 单项目系统 MB 级数据充裕；逼近 1GB 或打开时延有投诉时重开（触发器备案）。
  - **并发查看**：每个查看会话一份独立副本（磁盘 × 会话数）；v1 单用户无并发多开场景，平台侧设同项目并发快照上限兜底（实施票定，建议 ≤2）。
  - **销毁时序**：副本随容器可写层消失（实验验证卷内零残留、主 pg `postmaster.pid` 不动）；反向时序（查看在途、主容器重建）快照照跑但数据停留打开时刻，随销毁级联收口即可。
  - **一致性档位**：文件直拷 = 崩溃一致（等价主库在拷贝瞬间崩溃后 WAL 恢复），非事务一致；实测主库 2 万插入 + 40 轮全表 UPDATE 进行中拷贝，副本干净起库、全表可读。理论撕裂页风险存在（initdb 默认未开 checksums，撕裂页可能被静默读）——v1 接受；可选加固：initdb 加 `-k` 开 checksums（读时暴露撕裂页）、高压场景退 `pg_dump`，留实施票裁量。
- **E0 教训入正本**：跨容器对同卷 PGDATA 起 pg 时，postgres 的锁防线不可依赖（实验实测：第二容器入口脚本对主库**活体** PGDATA 起库成功——pg_ctl 跨 PID 命名空间把主锁判为可能陈旧的「trying to start server anyway」，`postmaster.pid` 被改写、共享数据文件被执行 recovery + checkpoint，是主动腐坏不是良性失败）。故任何第二容器一律卷 `:ro` + 副本，永不直接对共享 PGDATA 起库；主容器单实例自愈路径（ADR 0001）不受影响。
- **与 ADR 0001 的边界**：快照容器是同一系统工作区的临时只读视图容器，不承载任何持久状态、查看结束即销毁——「一个系统 = 一个 docker 实例」的常驻口径不变，非第二常驻实例。
- 快照会话所见数据 = **打开那一刻**的现在（拷贝时刻），非实时现在——与「当时」浏览语义自洽，作为「查看当时」的定案口径。

## 实验证据摘要（可复跑）

底座与置备同形（`docker run -d --name ws-<id>-dev -p 宿主:8081 -v vol-ws-<id>-dev:/workspace -w /workspace -e PGDATA=/workspace/data/pg -e WORKSPACE_DB=ws<id> aiplatform/dev:0.6 sleep infinity`），种子 5000 行业务数据、PGDATA 41MB：

- **E0 冲突现场**：第二容器同卷直跑入口脚本 → `pg_ctl: another server might be running; trying to start server anyway` → `server started`；快照侧 postgres 与主库 postgres 并存同 PGDATA，`postmaster.pid` 被改写，共享文件被执行 crash recovery（checkpoint 写 984 buffers）。
- **解路一**：`ALTER SYSTEM SET listen_addresses='*'` + `pg_hba` 放行 + 重启（0.27s）→ `docker network create` + 挂两容器 → 快照容器（入口旁路、卷 `:ro`、`-p 38082:8081`）应用以 `postgresql://ws78@ws-e78-dev:5432/ws78` 起服 0.6s，宿主 curl 读到 5000 行；经快照应用插入 1 行 → 主库 5001（写入直达）。
- **解路二**：`docker run --entrypoint sleep -v 卷:/workspace:ro` → `cp -a /workspace/data/pg /tmp/pgsnap && rm -f /tmp/pgsnap/postmaster.pid` → `su postgres pg_ctl start`：起容器 0.31s + 拷贝 0.40s + WAL 恢复起库 0.91s = **1.62s 数据可见**；应用级全栈（拷贝 + 起库 + redis + 应用起服）3.1s。快照侧插入 +1 只落副本、主库不动；压力拷贝（主库持续 2 万插入 + 40 轮全表 UPDATE）副本起库后 count/全表扫描/首尾行全部可读；双快照并存各自独立；`docker rm -f` 后卷内 `data/` 仅剩 `pg`、主 pg 应答与 `postmaster.pid` 不变。

依据：#78（本设计票）、#75 ⑥（版本层实施决议）、ADR 0001（单容器 all-in-one 与 PGDATA 归位卷内）。实验脚本为一次性物料未入库（票面「实验代码可抛」），命令序列与数字如上可复跑。
