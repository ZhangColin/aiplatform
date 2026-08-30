# aiplatform-web

前端项目，基于 Next.js。

## 技术栈

- Next.js（App Router）/ React / TypeScript（strict）
- Tailwind CSS v4 + shadcn/ui 全量 / TanStack Query + Zustand / pnpm
- 路径别名：`@/*` → `./src/*`，工具函数：`@/lib/utils`（cn）

## 常用命令

- 开发：`pnpm dev`（端口 3333）
- 构建：`pnpm build`
- 代码检查：`pnpm lint`
- 类型检查：`pnpm typecheck`

## 编码规范

- 函数组件 + hooks，禁止 class 组件
- 状态管理三分法：REST 服务端状态 → TanStack Query（key 工厂 `src/lib/api/keys.ts`；SSE 事件以 invalidate 为主，载荷展示走白名单例外——桥内注册表，ADR 0003；消费层在 `src/lib/sse/`）；流式/纯 UI 状态 → Zustand store，放 `src/lib/store/`（SSE 相关三 store：`sse-status.ts` 连接状态、`agent-streams.ts` agent 流分段、`project-notices.ts` 项目级瞬时通知——桥为唯一事件写入方）；一次性局部状态 → 组件 state
- 样式：Tailwind CSS，用 `cn()` 合并类名；UI 组件一律取 `@/components/ui/`（全量已备），缺的 `pnpm dlx shadcn@latest add`，图标用 lucide
- API 调用：薄 client `src/lib/api/client.ts`（相对路径 `/api/*`，Next.js rewrite 代理 → 后端）；类型用 `pnpm gen:api` 从 swagger 生成（`src/lib/api/schema.d.ts`），不手写请求类型

## Agent skills

### Issue tracker

Issue 跟踪在 GitHub Issues（ZhangColin/aiplatform-web），用 `gh` CLI 操作。See `docs/agents/issue-tracker.md`.

### Triage labels

使用五个默认 triage 标签（needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix）。See `docs/agents/triage-labels.md`.

### Domain docs

Single-context 布局：根目录 `CONTEXT.md` + `docs/adr/`。See `docs/agents/domain.md`.

