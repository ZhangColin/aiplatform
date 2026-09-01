# SSE 消费层：双通道连接拓扑 + 事件→失效桥 + 断线对齐

> **修订（片5-3 · [票 #33](https://github.com/ZhangColin/aiplatform/issues/33) 终检，2026-09-01）**：本 ADR 叙述中的 Phase-A 面——todos 失效域（`AGENT_EVENT_INVALIDATIONS`）、`stage-changed` 载荷白名单、「直播」tab、agent-streams 分段 store——已随平台重定义删除（#14/#17 清场）；「工作台」为旧壳称谓，现行词条 = 项目页（指令区 + 成果区），agent 流通道随**项目页**建连、直播为成果区右侧栏解说广播。仍有效的决策：双通道连接拓扑（通知 root 常开、agent 流随项目页建连）、「失效为主 + REST 重查兜底」分工、原生 EventSource 重连不自制退避。后端 agent 流已升级为**热缓冲回放**（新连接先收近期帧再进实时流，见 server SSE 事件清单）——「后端未承诺补发」一句按此读。

后端已定稿双通道（aiplatform-server ADR-0001：`GET /api/events` 平台通知 ∥ `GET /api/agent-events` agent 流，信封 `{type, payload, ts}`，15s `:ping` 注释行心跳，重连 = EventSource 自动重连 + REST 重拉对齐），本仓库状态分工已定（ADR 0002：SSE 事件只做 invalidate，流式状态归 Zustand）。本 ADR 定前端消费层三件事：**连接拓扑**（通知通道 root 级登录后单例常开、agent 流通道随工作台建连）；**事件→状态桥**（声明式失效注册表 + runId 键控 streams store）；**断线对齐**（原生重连 + 广谱 invalidate + 15s 门控轮询兜底 + agent 流 Set 去重）。决议过程见 wayfinder 票 #5。

## 连接拓扑

- **平台通知 `/api/events`：root layout 挂 provider，登录后单例一条，缺省全量不过滤**。切门户不断线（后端「门户布局级常开」取的是常开语义，不强制每门户一条）；未登录不建连；每标签页天然各持一条，不做跨标签页聚合。A2 后加 `userId` 过滤 = 改连接 URL 参数，连接管理层不动。
- **agent 流 `/api/agent-events`：工作台 mount 建连（`?projectId=` 过滤）、unmount 即断**。用户中途离开、运行还在继续：streams 按 runId 留存，回来重连后续播，中间缺口 Phase A **不做交代**——事件只让 UI 活、正确性走 REST，终态以后端重查为准；后端将来开补发时前端增量承接（见 Consequences 的缝）。
  - **修订（#23 落码，2026-08-22）——首个挂载方落地**：项目页（`project-page-view.tsx`）经 `agent-channel.tsx` 的 `useAgentStreamChannel(projectId)` 建连，probe 会话守卫 + StrictMode 幂等形态同构通知 provider；呈现 = 主面板「直播」tab（知识命中区块 + 连接状态小指示 + 断流 10s toast），全量舞台时间线归后续对接 issue。

## 事件→状态桥

- **通知通道 → 声明式失效注册表**：事件 type → query key 工厂列表的映射表，连接层收到事件查表执行 `invalidateQueries`，不写 switch 散落业务。v1 一律粗粒度前缀失效（失效是幂等信号不是数据搬运，Query 请求去重已压住浪费，实测有痛点再收粒度）；终态类事件同样只 invalidate，不直接改缓存。
  - **修订（#19 落码，2026-08-22）——载荷展示白名单例外**：个别载荷字段 REST 重查拿不到（`stage-changed` 的 `rejected:true, reason` 驳回理由、`preview-ready` 的「有更新」信号），失效救不了展示。注册表旁增设 `NOTIFICATION_PAYLOAD_WRITERS` 白名单（bridge 内同构映射表），仅白名单事件把载荷写入轻量 `project-notices` store 页内呈现（桥仍是唯一事件写入方，模式同 streams store）；其余事件一律只失效。正确性仍以 REST 重查为准，store 只承载瞬时展示态。
- **agent 通道 → `agent-streams` store**：按 runId 键控、run 内分段数组（text / thinking / tool 调用配对 / 终态标记），SSE handler 唯一写入方，Agent Feed 组件只读。驱逐：按项目留最近 1 个 run + 总量软上限 10——够回看刚才那次运行，内存有界。此形态即 T4 工作台原型票的数据契约。
  - **修订（#21 落码，2026-08-22）——agent 侧失效注册表**：`wait-raised` / `wait-settled` 除写 streams store 外，按 `AGENT_EVENT_INVALIDATIONS` 一并失效 todos 域（待办含 AGENT_WAIT 型，等待点开关即待办增删），`dispatchAgentEvent` 相应加 queryClient 参数，与通知注册表同构、由将来 agent 通道挂载方传入。待办列表轮询兜底因数据源跨双通道改为「任一通道未连即 15s」：骨架期 agent 通道未挂载即等价常开，挂载后自然收敛。

## 断线恢复与兜底

- **重连 = 原生 EventSource 自动重连，封装层不接管退避**：原生白送 Last-Event-ID 请求头，正是后端补发缝的既定载体；自制 close+重开会丢掉它，接补发还得加查询参数契约（后端未承诺）。后端挂掉时的 ~3s 重试锤在单机单账号 dev 环境是无感小流量，锤出问题再进图。
  - **修订（#16 落码，2026-08-21）**：「不接管」的边界细化——浏览器对**非 2xx 响应**会让 EventSource 直接 CLOSED 且不再自动重连（原生重连只覆盖断流）。此时探一次 `/api/me`：401 停手交全局 401 出口；非 401 按**原生同款节奏（~3s、无退避）再播种**重建连接，防后端重启 / 代理瞬时 5xx 让通道永久失活到刷新页面。该路径确实丢 Last-Event-ID，但 Phase A 两通道都永不补发（通知通道设计上就不补，agent 流补发未开），无损。
- **重连成功（error 后的 onopen）→ 广谱 invalidate**：当前挂载 query 自然重拉，未挂载的下次挂载本就 refetch——事件不承担正确性，粗对齐零风险。
- **轮询兜底 = 门控 refetchInterval**：活页面 query（工作台 / 项目详情 / 待办列表）挂 `refetchInterval: 15s`，门控条件 = 对应通道 status ≠ connected（列表类看通知通道、工作台看 agent 通道），连接健康时不空转；`refetchOnWindowFocus` 保持 Query 默认开。
- **401 盲区**：EventSource 读不到状态码，会话过期只会表现为反复 error。连续 5 次失败探一次 `/api/me`——401 停手交给全局 401→BFF 登录流程（ADR 0002），200 继续原生重连。
- **去重：agent 流通道做，通知通道不做**。agent 流是 append-only 直播画面，重复一眼可见；Set 键 = 完整事件 id（`{runId}:{seq}`）带上限清空——这也是将来接后端补发的现成缝。通知通道消费端是幂等 invalidate，重复无害，省掉。
- **心跳无需前端处理**：`:ping` 是注释行，EventSource API 不可见；transport 保活有效性归后端 PoC ②（Next 代理）。
- **呈现最小化**：`sse-status` store 按通道存 connected / connecting / offline；只在**工作台** agent 流区给小指示（直播断流对用户有解释义务），断线超 ~10s 未恢复发一次 toast、恢复不刷屏；通知通道完全静默（REST 兜底本就是设计）。

## Considered Options

- **每门户各一条 vs root 单例**：选单例。切门户断线重连毫无收益；三门户同一后端同一账号，全量通知流对谁都成立。
- **自制指数退避 vs 原生自动重连**：选原生。自制要 close+定时重开，丢 Last-Event-ID 头；退避收益只在后端长时间挂时省流量，dev 单账号场景不成立。
- **SSE 事件直接写 Query 缓存 / 前端合成状态 vs 纯 invalidate**：纯 invalidate（ADR 0002 已定，此处落地为注册表形态）。CC 实测：服务端是唯一真相源，前端合成必漂移。
- **fetch-event-source 等库 vs 原生 EventSource**：原生。只需 GET + 同源 cookie（走 `/api` rewrite 自动携带），库的卖点（POST / 自定义头）用不上。
- **store 合一 vs 分两个**：分。`sse-status`（传输层：provider 写，指示器 / 轮询门控读）与 `agent-streams`（过程层：bridge 写，Agent Feed 读）读写方零重叠，变更频率也不同。
- **细粒度失效 vs 粗粒度前缀**：粗。失效幂等、Query 去重，细粒度是过早优化。

## Consequences

- 模块落点：`src/lib/sse/`——`connection.ts`（原生 EventSource 封装：单例 / 建连 / 状态 / 失败计数 / 去重 / id 缝，StrictMode 双挂载幂等）、`events.ts`（手写判别联合镜像后端《SSE事件清单》名册——SSE 不进 swagger，这是唯一可行类型来源；文件头注释指正本，对接 issue 时对照更新）、`bridge.ts`（通知→失效注册表 + agent 流→streams 分发）、`provider.tsx`（"use client"，root layout，会话守卫建连通知通道）、`agent-channel.tsx`（agent 流通道挂载 hook，工作台页建连，#23）；React 侧读口两个：`useSseStatus()` 与其派生的门控轮询值 `useSseFallbackPolling()`（轮询兜底的载体，#16 回填）。store：`src/lib/store/sse-status.ts` + `src/lib/store/agent-streams.ts` + `src/lib/store/project-notices.ts`（#19：载荷展示白名单的落地）。
- 执行票 #16：落码等后端片 1 swagger 就绪后的首个 SSE 对接 issue 解锁；届时一并实测 Next rewrite 对 SSE 的缓冲行为（后端 PoC ② 前端侧）。
- 将来后端开 agent 流补发：Last-Event-ID 头（原生重连自带）+ 去重 Set 已就位，前端只加补发段落的呈现。
- 多标签页各持一条通知连接——天然行为，不聚合。
- **PoC ② 前端侧实测回填（2026-08-21，#16 冒烟）**：Next 16.3.1 dev（`next dev` + rewrites → 8888）对 SSE **不缓冲**——`content-type: text/event-stream` 保真透传、`:ping` 心跳按拍实时到达（keep-alive timeout=60）、Node 原生 EventSource 经代理建连并稳定持有；注释行心跳对 EventSource API 天然不可见，无需前端处理。prod standalone 未本机复验：`pnpm build` 会把 `.env.production` 的部署态 `BACKEND_URL`（容器主机名:8081）烧进 routes-manifest，属部署姿态而非 SSE 问题，上线时以同源真实后端复验即可。另：后端片 1a 只落通知通道管道，发射方（业务编排层）随 A3 就位——`POST /api/workspaces` 不触发 SSE 属预期，「通知→invalidate 生效」的活体验证留在首个 SSE 对接 issue。
