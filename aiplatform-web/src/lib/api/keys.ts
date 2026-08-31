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
export const queryKeys = {
  /** 会话身份（spec 0004：staleTime Infinity，失效感知交给 401 全局出口）。 */
  me: ["me"] as const,
  /**
   * 项目域：SSE 失效桥（src/lib/sse/bridge.ts）的粗粒度目标——workspace /
   * 文档 / 改名等通知事件统一失效整域。
   */
  projects: {
    all: ["projects"] as const,
    /** 详情。 */
    detail: (id: string) => ["projects", id] as const,
    /** 列表（过滤参数进 key；status 缺省 = all）。 */
    list: (status?: number) => ["projects", "list", status ?? "all"] as const,
  },
} as const;
