/**
 * Query key 工厂 —— SSE 事件 → invalidate 桥的核心资产（ADR 0002 / ADR 0003）。
 * 随端点增补，保持层级结构以支持粗 / 细粒度失效：
 *
 * ```ts
 * projects: {
 *   all: ["projects"] as const, // invalidate(["projects"]) 失效整域
 *   detail: (id: string) => ["projects", id] as const,
 * },
 * ```
 */
import type { TodoView } from "@/lib/todos/todo";

export const queryKeys = {
  /** 会话身份（spec 0004：staleTime Infinity，失效感知交给 401 全局出口）。 */
  me: ["me"] as const,
  /**
   * 以下域为 SSE 失效桥（src/lib/sse/bridge.ts）的粗粒度目标：键先于端点落位
   * （SSE 事件已开始到达），具体端点 query 随各对接 issue 增补挂到这些前缀下。
   */
  projects: {
    all: ["projects"] as const,
    /** 详情（含 stages[] 主链定义序列 + gate）。 */
    detail: (id: string) => ["projects", id] as const,
    /** 预览地址（挂 projects 域下，通知通道粗粒度失效天然覆盖点亮重拉）。 */
    preview: (id: string) => ["projects", id, "preview"] as const,
    /** 列表（过滤参数进 key；status 缺省 = all——同名参数是过滤视图非项目三态）。 */
    list: (status?: number) => ["projects", "list", status ?? "all"] as const,
    /** 想法池条目（新→旧）。 */
    demandPool: (id: string) => ["projects", id, "demand-pool"] as const,
    /** 用量简版（五档 token）。 */
    usage: (id: string) => ["projects", id, "usage"] as const,
    /** HITL 等待点（跨会话 PENDING，issue #45）：wait-raised / wait-settled 失效目标。 */
    waits: (id: string) => ["projects", id, "waits"] as const,
  },
  tasks: {
    all: ["tasks"] as const,
    /** opc 我的任务卡片（assignee=me，新→旧）。 */
    list: ["tasks", "list"] as const,
    /** 任务详情（task + ProjectBrief + bugs[]）。 */
    detail: (taskId: string) => ["tasks", "detail", taskId] as const,
    /** dev 项目任务全量（含 submittedPayload / rejectReason 裁决面）。 */
    projectTasks: (projectId: string) => ["tasks", "project", projectId] as const,
    /** dev 项目 Bug 面板（三态行）。 */
    projectBugs: (projectId: string) => ["tasks", "project", projectId, "bugs"] as const,
  },
  /** 账号（成员页只读表格 + dev 建任务指派下拉源）。 */
  accounts: {
    all: ["accounts"] as const,
  },
  /** 引擎能力矩阵（消费方 = 后台引擎配置页 #56；创建表单 #51 起不再选引擎）。 */
  agentEngines: ["agent-engines"] as const,
  /**
   * 简易后台域（CONTEXT「简易后台」，#56）。无 SSE 失效源——engine-config
   * 切换成功后就地失效重拉（即时生效，读库不缓存）。
   */
  admin: {
    all: ["admin"] as const,
    /** 当前生效引擎（GET /admin/engine-config；从未配置返回注册表缺省 opencode）。 */
    engineConfig: ["admin", "engine-config"] as const,
  },
  /**
   * 工作文档产物域：`document-updated` SSE 的失效目标之一（名册「失效文档域」
   * 口径，#54）；v1 唯一成员 = PRD。事件实际失效 documents + projects 双域
   * （#58：门就绪 gate.ready 挂在 projects 详情，PRD 写出连带重拉）。
   */
  documents: {
    all: ["documents"] as const,
    /** 当前版 PRD（markdown + updatedAt；未产出 404 PRJ_015 在 hook 归一为 null）。 */
    prd: (id: string) => ["documents", "prd", id] as const,
  },
  /** 待办（issue #21）：SSE wait-raised / wait-settled / stage-changed 的失效目标。 */
  todos: {
    all: ["todos"] as const,
    /** 列表（view 进 key：dev=开发平台 / opc=任务平台）。 */
    list: (view: TodoView) => ["todos", "list", view] as const,
  },
} as const;
