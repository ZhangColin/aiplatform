/**
 * 待办 → 工作台深链（issue #44，spec 0003 §3）：待办点击不再只跳项目详情，而是
 * 直达工作台对应功能点。dev 四型 → 开发平台工作台 `/dev/projects/{id}`，以 query
 * 参数承载目标：
 * - AGENT_WAIT → `?wait={waitId}`：对话模式定位等待点（消费 waitId）。
 * - GATE_PENDING → `?focus=gate`：门卡。
 * - TASK_SUBMITTED / RETEST_READY → `?focus=tasks`：任务面板。
 * opc 两型 → `/opc/tasks/{taskId}`（本模块不管，todo.ts 直出）。
 */

export type WorkbenchDeepLink =
  | { kind: "wait"; waitId: string }
  | { kind: "gate" }
  | { kind: "tasks" };

/**
 * 深链 → href（query 参数编码统一在此，避免各调用点手拼）。base = 工作台路由前
 * 缀：开发平台 `/dev/projects`（缺省，issue #44），需求端 `/projects`（issue #49）。
 */
export function buildWorkbenchDeepLink(
  link: WorkbenchDeepLink,
  projectId: string,
  base = "/dev/projects",
): string {
  const path = `${base}/${projectId}`;
  switch (link.kind) {
    case "wait":
      return `${path}?wait=${encodeURIComponent(link.waitId)}`;
    case "gate":
      return `${path}?focus=gate`;
    case "tasks":
      return `${path}?focus=tasks`;
  }
}

/** 工作台 URL query → 深链目标（无匹配参数返回 null，等价普通进入）。 */
export function parseWorkbenchDeepLink(params: URLSearchParams): WorkbenchDeepLink | null {
  const wait = params.get("wait");
  if (wait) return { kind: "wait", waitId: wait };
  const focus = params.get("focus");
  if (focus === "gate") return { kind: "gate" };
  if (focus === "tasks") return { kind: "tasks" };
  return null;
}

/**
 * 服务端页消费口（issue #49）：Next `searchParams` record（值可 `string[]`）→ 深链
 * 目标。重复键取首值，与浏览器 URL 单值语义一致。
 */
export function parseWorkbenchDeepLinkFromSearchParams(
  params: Record<string, string | string[] | undefined>,
): WorkbenchDeepLink | null {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    const first = Array.isArray(value) ? value[0] : value;
    if (first !== undefined) query.set(key, first);
  }
  return parseWorkbenchDeepLink(query);
}
