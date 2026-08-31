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
    /** 列表（全量一份；四态过滤/归档折叠在消费端本地分区——#21）。 */
    list: () => ["projects", "list"] as const,
  },
  /**
   * 工作文档产物域：`document-updated` SSE 的失效目标之一（#20 成果区文件
   * 模式消费）；v1 唯一成员 = PRD。事件连带失效 projects 域（详情的
   * prdProducedAt 是成果区长出判据，PRD 写出瞬间一并重拉）。
   */
  documents: {
    all: ["documents"] as const,
    /** 当前版 PRD（markdown + updatedAt；未产出 404 PRJ_015 在 hook 归一为 null）。 */
    prd: (id: string) => ["documents", "prd", id] as const,
  },
} as const;
