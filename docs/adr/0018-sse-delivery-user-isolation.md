# SSE 投递按订阅者归属隔离：owner 路由键

`GET /api/events` 通道不按用户隔离，两面：未过滤的站点级常开订阅收**全站**通知族；`?projectId=`/`?runId=` 过滤是纯 payload 字段匹配而非授权——任意登录用户可以**他人** projectId 建立过滤订阅，收该项目通知族＋智能体族事件（含对话内容流）（#208）。决定：投递谓词统一叠加订阅者归属匹配——订阅握手绑定登录用户（匿名 401 已由 `/api/**` 拦截面保证），`ownerAccountId` 作为事件载荷**路由键**（与 projectId/runId 同 idiom），发布侧显式注入：通知族发布点聚合在手免费、智能体族在 `AgentEventBridge.sink` 创建时捕获一次（token 热路径零查库）；`publishNotification`/`publishAgentEvent` 对 owner 键强制校验、漏传发布期即炸——未来发布点结构性防漏。未过滤订阅语义 = 我的全部通知；过滤参数维持纯兴趣选择（他人 projectId = 静默空流，不加订阅时归属校验）；重放补发走同一谓词，实时与重放双覆盖。

## Considered Options

- **前端按「我的项目集」过滤**：被否。治标——通道本身仍泄密；且对第二泄漏面（他人主动订阅我的项目）无效，前端过滤根本不在此路径上。
- **发布口集中解析**（EventsAppService 注入 OwnerResolver 端口，发布时按 projectId 查 owner）：被否。单点覆盖未来发布点，但 base 不得依赖 business 需造端口缝，且智能体族变成每事件一次查库（token 流热路径不可接受，补救还得加缓存）。
- **信封维度**（EventEnvelope 加 owner 字段、hub 谓词签名改造）：被否。契约最纯，但现有路由键（projectId/runId）全在载荷里，单为 owner 改 hub 公共面不合既有习惯；owner 键对客户端无害（隔离修后只见自己的 accountId）。

## Consequences

- 两族载荷对客户端各多一个 `ownerAccountId` 路由键（前端不消费，契约注明路由键非内容）。
- 发布口强制校验 = 无 owner 的事件进不了通道；将来通知载荷变丰富前通道已收口（#202 信号-only 与本票正交：前者控内容量级，本票控通道边界）。
- CONTEXT.md「平台通知」词条随之去「广播」改「按订阅者归属定向投递」。
- 残余（接受）：会话中途过期（页面长挂不动）连接仍投至断连——不为「同一浏览器挂几天」造会话失效断连机制，connection.ts 探活出口兜底。
