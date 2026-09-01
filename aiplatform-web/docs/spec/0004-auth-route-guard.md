# Spec 0004 · 登录与路由守卫对接（OIDC BFF 前端侧）

> 产地：wayfinder T7（[#9](https://github.com/ZhangColin/aiplatform-web/issues/9)，grilling 票）。
> 前置事实：账号 + OIDC BFF 规格当时定稿于 server 侧 A2 规格（Phase-A 历史规格，已随片5-1 清档）；SSO 联调闭环（aiplatform-server#8）——`redirect_uri = http://localhost:3333/auth/callback`（**前端 origin**，Next 代理到 8888）、issuer `http://identity.localhost:10001`、identity 登录页 `identity.localhost:10002`、cookie `aiplatform_session`（不透明 sessionId，HttpOnly + SameSite=Lax，无 maxAge）。
> 真实实现等后端 A2 实现（aiplatform-server#19）完成、对接 issue 到达后照本 spec 落地（执行票 #17）。returnTo 为跨仓增量需求：[aiplatform-server#32](https://github.com/ZhangColin/aiplatform-server/issues/32)。

## 1. 对接形态总纲

BFF 四端点照 A2 §2/§9，前端**同源姿态**，不感知 token：

| 端点 | 前端姿态 |
|---|---|
| `GET /auth/login` | 整页跳转目标（middleware 302 / 401 出口），非 fetch |
| `GET /auth/callback` | identity 302 落点，经 Next 代理直达后端，**前端无回调页** |
| `POST /auth/logout` | 用户区 form POST（非 fetch），后端带 id_token hint 杀 identity 会话 |
| `GET /api/me` | `useMe()`（§5），同源 fetch |

- **代理扩展**：next.config.ts rewrites 增 `/auth/:path*` → `${backendUrl}/auth/:path*`（现只有 `/api/:path*`）。cookie 经代理落在 3333 origin——这正是 redirect_uri 注册前端 origin 的原因（A2 §7）。
- **无前端 /login 页**：未登录直达 identity 登录页（展示名「AI 开发平台」），不做中间页。

## 2. 登录链路与回跳契约（returnTo）

```
未登录访问 /projects/p123
  → middleware 302 /auth/login?returnTo=%2Fprojects%2Fp123   （§4）
  → identity 登录页（identity.localhost:10002）授权
  → 302 localhost:3333/auth/callback?code&state（代理到 8888）
  → 后端验签换 token、upsert 账号、Set-Cookie aiplatform_session
  → 302 returnTo（校验通过）或 /（缺参/非法兜底）
```

- **携带**：`returnTo = encodeURIComponent(pathname + search)`，仅同源相对路径。
- **两个入口共用**：middleware 首跳 与 §3 的 401 过期跳——同一机制，否则会话过期时深在项目页仍丢位置。
- **校验与兜底在后端**（开放重定向防线）：只接受单个 `/` 开头的相对路径，拒绝 `//`、`/\`、绝对 URL；非法落 `/`。往返存活方式（`oauth_txn` 承载 or 塞 state）是后端实现自由。
- **登录后兜底落点** = 首页（暂定 `/`，具体随工程初始化 #1 路由方案）。
- **注销落点不变**：`post_logout_redirect_uri` 维持 `http://localhost:3333/`，不做「已退出」公开页——落 `/` 后 middleware 302 到 identity 登录页属预期行为；若实测 id_token hint 未杀掉 identity 会话（注销后免密弹回）再议。

## 3. 会话保持与 401 全局出口

ADR 0002 已定「401 跳 BFF 登录」，本 spec 定细则——**一个出口、零分支**：

1. 任何 `/api` 调用解包为 401（首屏或后台 refetch，含 `/api/me` 自身）→ `window.location.href = '/auth/login?returnTo=' + encodeURIComponent(location.pathname + location.search)` 整页跳；无 toast / 无确认中间态；模块级 redirecting 标志防并发 401 重复触发。
2. **SSE 不做 401 特判**（EventSource 拿不到状态码）：靠同页 REST 调用的 401 出口兜底 + ADR 0003 的原生重连 / 重连广谱 invalidate / 15s 门控轮询自然收敛。
3. 会话生命周期感知完全被动：无 maxAge 会话 cookie + 后端内存会话，浏览器重启 / 后端重启 = 下次请求 401 → 出口接管，前端不做过期倒计时类主动逻辑。
4. v1 无 403 场景（单账号无角色），不设 403 分支。

**落码归属**：薄 client（#15 `client.ts`）的 401 分支——#15 未落码则随其直接带上 returnTo；已落码则执行票 #17 改之。

## 4. 路由守卫（proxy，Next 16 前称 middleware）

- `src/proxy.ts`：非白名单路径无 `aiplatform_session` cookie → 302 `/auth/login?returnTo=<enc>`；有 cookie → 放行。
- **只验存在性，不验真**：不透明 sessionId 前端无法验证；cookie 在而后端会话已死 → 页面渲染 → 首个 `/api` 401 → §3 出口兜底（接受一次空壳闪现，换零延迟零额外请求）。
- **白名单**（matcher 排除）：`/auth/*`（代理路径，拦了死循环）、`/api/*`、`_next/*` 与静态资源、`favicon.ico`、`/prototype/*`（UX 原型，prod 本就渲染 null）。
- **守卫只验登录态，不区分页面/角色**：将来角色过滤走场景菜单配置，不动守卫。

## 5. useMe 契约

- TanStack Query：key `['me']`，`queryFn` 走薄 client `GET /api/me`，`staleTime: Infinity`（会话内不重拉；失效感知交给 401 全局出口）。
- 返回 `{ accountId: string, displayName: string }`（A2 §3 最小契约，email/picture 不进 v1）。
- 401 走全局出口，`useQuery` 层不单独 error 分支。
- displayName 摆位（sidebar footer 用户区 / 登出入口按钮）随工程初始化 #1 的 Layout，本 spec 只定契约。

## 6. 本地开发口径（零开关）

- **登录体系只做 OIDC 一套**：无 `NEXT_PUBLIC_*` 假会话 / 免登录开关，dev 与 prod 同一条真流程（对齐 A2 §2「不做 stub profile」——identity 本机常启，避免前后端特殊代码）。
- UX 开发不受影响：`/prototype/*` 在守卫白名单内，无需登录。
- 代价：后端重启 → 一次 SSO 弹回重登（identity 会话仍在，不输密码）——内测期可忍。
- cookie 纪律沿用后端口径：全程 `localhost`，禁混 `127.0.0.1`（不同 host cookie 带不过）。

## 7. 升级路径

- returnTo 若后端短期不配合：前端降级为不带参跳 `/auth/login`（出口逻辑不变，仅丢回跳），机械改一行。
- 「已退出」公开页：仅当 identity 会话杀不干净造成困惑时补。
- 守卫强化（middleware 里反查 `/api/me` 验真）：页面级闪现成为实际投诉再说。
