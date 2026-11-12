# 会话执行器分池：交互／生成车道分离＋生成准入与队列可观测

#475 定档（2026-11-12，11-12 生产事故收口；承 #22 stripe 共池设计、#359 停止面）。现状事实：8 条单线程 stripe（sessionId 哈希落位）生成与交互共池，生成 run 是小时级任务且 stripe 线程内阻塞等模型流（jstack 实拍 40+ 分钟 parked 于 blockLast）——每并发生成占死一条 lane 1–2h，2 lane 占用＝25% 新会话饿死、8 lane＝全员问答不出；`String.hashCode` 对相邻 TSID 碰撞聚集（事故两会话同落 stripe 0），饿死非均匀命中；任务排在队列里零痕迹，定位全靠 jstack。定案：

**分池**：交互车道（秒级轮——主智能体对话/问答/判定、取名、分类、技能预检等边缘轻调用）与生成车道（小时级产出轨整场任务——生成、迭代复演、设计轨，即登记 `CodingRunTrack` 的同族）两条独立 stripe 池。同会话串行不变量两池各自保留（stripe 设计的意义仅此——同一 AgentState 槽位一次一轮；不同会话共享车道纯属历史顺带）。

**缝＝提交面显式分道**：`submit`（交互）／`submitGeneration`（生成）——车道选择在调用方类型面可见，不做执行器内按 sessionId 前缀路由。5 个提交点迁生成车道（GenerationAppService、IterationAppService、DesignProcessAppService×3）。

**生成准入＝车道宽度**：生成车道 stripe 数＝跨项目并发产出轨上界，`app.agentscope.generation-stripes` 可配（缺省 8＝共池时代容量平移，单机容量为准调低），改动重启生效；超宽排队**可见**——入队（排位）＋出队（滞留时长）逐任务 INFO（产出轨派发低频，不刷屏），交互车道出队滞留超 `app.agentscope.session-queue-warn-threshold` WARN（事后可查——卡队期间无在途探针，分池后交互车道全为秒级任务且单流 15m 封顶，残余风险结构性缓解，探针留待有真实需要再建）。

**落位改 TSID 尾数低位取模**：尾随数字串按数值取模（事故两会话 5/7 分道），无尾随数字回落 hashCode（行为同旧）。

## Considered Options

- **执行器内按 sessionId 前缀路由**——拒绝：base 层反向耦合业务前缀（coder-/designer-），车道选择成隐藏策略；显式 API 让「小时级任务误进交互车道」在调用面可见、mock 面随 API 简化（按前缀分流→按方法分流）。
- **信号量准入＋全局 FIFO 派发器**——拒绝：手写调度器（busy 会话跳过重入队、派发线程头阻塞、完工唤醒），stripe 池免费给同会话串行与并发上界；产出轨并发数小，全局 FIFO 与 stripe 分布的实际差异不抵复杂度。
- **单池＋优先级队列**——拒绝：优先级不解决线程占用——小时级任务仍占线程，交互轮仍排在后；优先级反转另添风险。

## Consequences

- `Future cancel(true)` 停止面语义不变：stripe future 直返，`CodingRunTrack.registerTask` 同锚（#359 停止请求仍中断模型流订阅）。
- 并行波不受影响：波内 fork 片走 `SliceParallelExecutor` 独立池，生成车道宽度只限「跨项目并发产出轨数」。
- 指标端点未开（micrometer/actuator metrics 暴露维持 health-only）：演示阶段日志即查证面；开端点属运维面接口暴露决策，留待运营化票。
- 宽度热调（不发版改）未做：常驻线程组属启动期装配；单机最大吞吐口径（#478 线）定档后随部署改。
- 与 #477（重启自愈无差别续跑）正交叠加：分池后重启洪水只灌生成车道，交互轮结构性免疫；续跑策略本身归 #477。
- 验收锚：生成并发打满配置上限时新建项目面试首轮仍秒级出队执行（回归测试钉死）；交互任务滞留超阈值有日志可查（sessionId＋滞留＋车道深度）。
