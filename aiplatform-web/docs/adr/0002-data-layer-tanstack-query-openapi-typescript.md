# 数据层：TanStack Query + openapi-typescript + 统一解包错误约定

后端契约（aiplatform-server ADR-0001：swagger 唯一契约、成功判定 = HTTP 2xx、SSE 事件只让 UI 活 / 正确性永远走 REST / 重连后 REST 重查）天然映射为 cache-invalidation 模式。决定：服务端状态引入 **TanStack Query v5**（SSE 事件只做 `invalidateQueries`，不在前端合成状态）；swagger → TS 用 **openapi-typescript 只生成类型**，请求层手写薄 client + query key 工厂；错误统一 `ApiError`（带后端中文 message），薄 client 统一解包 `ApiResponse`。决议过程见 wayfinder 票 #4。

## Considered Options

- **TanStack Query vs 手写 hooks vs RSC 数据获取**：选 Query。手写要重造缓存去重、失效传播、重试、竞态处理；RSC fetch 缓存的失效靠 `router.refresh()`，与「门户常开 + SSE 驱动失效」形态桥接别扭。CC（Code-Canvas）实测同模式跑通：Query 5 + SSE 事件只做 invalidate + query key 工厂 + 处处重试——服务端是唯一真相源。Zustand 分工收窄：只放 REST 拿不到的过程状态（agent 直播 streams 表、SSE 连接状态）+ 纯 UI 状态；CC 反面教训是流式状态放组件 `useState`（窄屏切页签即丢）。
- **orval vs openapi-typescript vs 手写类型**：选 openapi-typescript。orval 连 TanStack Query hooks 一起生成看似省样板，但 query key 藏在生成物里——SSE→invalidate 模式下 **key 工厂是核心资产**，失效逻辑、乐观更新、失效粒度都要与生成物搏斗，`ApiResponse` 解包还需 custom mutator；openapi-typescript 只产一个类型文件、零运行时，网络出口单一（CC 正是此模式：OpenAPI→类型、`lib/api.ts` 唯一网络出口）。手写类型直接否——违背「swagger 唯一契约」，必然漂移。
- **错误处理：薄 client 统一解包 vs 逐处判断**：选统一解包。2xx → 返回 `data`（unwrap `ApiResponse`）或 `PageResponse` 原样；非 2xx → `throw ApiError`。业务层不见信封，只处理业务数据与异常。**binary 例外（#20 起）**：`application/gzip` 等二进制端点（源码包下载）不走信封也不走薄 client——直挂 `<a download>` 由浏览器经同源 rewrite 下载（后端 ADR-0001 修订例外）。

## Consequences

- **状态三分法**：REST 可重得 → Query；REST 拿不到的过程状态（流式）+ 纯 UI 状态 → Zustand（`src/lib/store/`）；一次性局部状态 → 组件 state。**流式状态禁止放组件 state**。
- 依赖：`@tanstack/react-query`（运行时）+ `openapi-typescript`（devDep）；`pnpm gen:api` 从 `http://localhost:8888/v3/api-docs` 生成 `src/lib/api/schema.d.ts`，**产物提交进 git**（CI / 他机不依赖 8888 在线）。
- `ApiError = { code（如 PRJ_001）, message（后端中文，可直接 toast）, errors?: FieldError[]（表单就地展示）, status（HTTP 状态）, requestId（对后端日志排障）}`；`FieldError = {field, message, errorCode}`、`PageResponse = {items, total, page, size}`（1 基）——字段以 cartisan-web 实测为准。
- 401 → 跳 `/auth/login`（BFF）；403 → 无权限呈现。Query retry **只重试网络错误与 5xx**（4xx 不重试），mutation 不自动重试（显式重试按钮）；错误 toast 统一走 sonner 全局出口。
- fetch 一律相对路径 `/api/*`（`next.config.ts` rewrite → 8888，同源 cookie 自动携带）。
  - **修订（#60 落码，2026-08-28）——dev 专用 in-flight GET 去重**：StrictMode 双挂载（显式保留，换取 effect 清理问题早期暴露）让同一 URL 的 GET 在 ~10ms 内连发两次，首个随 cleanup abort 但 HTTP 已出门，Query 层去重拦不住（unmount abort 使然）。薄 client 内做同 URL in-flight 去重（落定即清、只去并发不缓存、写操作不去重），仅非生产构建启用。语义取舍：共享请求不挂消费者 signal——挂了会被首个挂载方击穿、去重失效；代价是 invalidate refetch 可能搭上仍在途的上一代请求（仅 dev、窗口极窄、下轮失效/轮询自愈）。SSE 双连接另由连接层 probe-cancel 守卫保证（ADR 0003 拓扑，双通道均有 StrictMode 回归测试锁定）。
- **遗留验证**：SpringDoc 产物质量（→ 类型生成效果）待后端片 0 就绪后在首个对接 issue 验收时首跑 `gen:api` 实测；不符再微调（filter / 后处理器）。
- 下游：T3（SSE 层）的事件→失效桥以 Query invalidate 为载体；流式 store 形态归 T3 定。执行落码见执行票。
