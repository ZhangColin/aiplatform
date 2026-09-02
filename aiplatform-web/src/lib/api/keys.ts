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
    /** 系统预览地址（#45：run 开始即轮询，探活通过才返回 URL、取到即停）。 */
    preview: (id: string) => ["projects", "preview", id] as const,
    /** 文件树（交付文件只读清单，目录由前端合成；run 收口/PRD 写出随 projects 域失效——#27）。 */
    files: (id: string) => ["projects", "files", id] as const,
    /** 文本文件内容（文件模式点看；换文件即换 key，不缓存旁路）。 */
    fileContent: (id: string, path: string) => ["projects", "file-content", id, path] as const,
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
  /**
   * 订单域（#28 交易环①）：订单卡按 id 拉详情；下单/取消动作连带失效
   * projects 域（详情的 activeOrder 嵌入是锁定式矩阵的推导输入）。
   */
  orders: {
    all: ["orders"] as const,
    /** 订单详情（状态 + 时间戳组 + 报价面：金额/备注/改价历史，#29）。 */
    detail: (id: string) => ["orders", "detail", id] as const,
  },
} as const;
