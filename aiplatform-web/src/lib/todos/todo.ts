import type { components } from "@/lib/api/schema";

import { buildWorkbenchDeepLink } from "./deep-link";

/**
 * 待办列表（issue #21/#22，spec 0003 §3）：`GET /api/todos?view=dev|opc` 的计算式
 * 投影——view 是端点视角参数非待办属性（dev = 开发平台 / opc = 任务平台）。dev 四型
 * AGENT_WAIT / GATE_PENDING / TASK_SUBMITTED / RETEST_READY，opc 两型 NEW_TASK /
 * TASK_REJECTED（A4 落定）。title 为后端生成的中性短文本，不含智能体产出内容。
 */

/** swagger TodoItemResponse 原始形状（字段全可缺）。 */
export type TodoItemResponse = components["schemas"]["TodoItemResponse"];

/** 端点视角：dev=开发平台 / opc=任务平台（非法 view 后端 400，前端只传合法字面量）。 */
export type TodoView = "dev" | "opc";

/** 列表消费口径（缺省字段防御归一）。 */
export type TodoItem = {
  /** 待办型：AGENT_WAIT=智能体等待答复/权限批准、GATE_PENDING=门待拍板（任务型随 A4）。 */
  type: string;
  /** TSID 十进制字符串，导航锚点。 */
  projectId: string;
  /** AGENT_WAIT=waitId、GATE_PENDING=projectId；深链形态到工作台功能点再定（spec 0003 §7）。 */
  refId: string;
  title: string;
  /** ISO Instant，源状态时刻（非拉取时刻）；后端已按新者在前排序。 */
  createdAt: string;
};

export function normalizeTodo(raw: TodoItemResponse): TodoItem {
  return {
    type: raw.type ?? "",
    projectId: raw.projectId ?? "",
    refId: raw.refId ?? "",
    title: raw.title ?? "",
    createdAt: raw.createdAt ?? "",
  };
}

/** 待办型呈现配置：徽章文案 + tone（amber=智能体侧等待、primary=主链门/待裁决、destructive=被驳回）。 */
export type TodoTypeMeta = {
  label: string;
  tone: "amber" | "primary" | "destructive" | "default";
};

const TODO_TYPE_META: Record<string, TodoTypeMeta> = {
  AGENT_WAIT: { label: "等答复", tone: "amber" },
  GATE_PENDING: { label: "待拍板", tone: "primary" },
  // A4 落定（#22）：dev 任务两型 + opc 任务两型
  TASK_SUBMITTED: { label: "待确认", tone: "primary" },
  RETEST_READY: { label: "可复测", tone: "amber" },
  NEW_TASK: { label: "新任务", tone: "amber" },
  TASK_REJECTED: { label: "被驳回", tone: "destructive" },
};

/** 未知型：type 原文兜底呈现，不炸不丢。 */
export function todoTypeMeta(type: string): TodoTypeMeta {
  return TODO_TYPE_META[type] ?? { label: type, tone: "default" };
}

/** opc 任务型（refId=taskId）→ 任务详情；其余 dev 型 → 工作台深链。 */
const OPC_TASK_TYPES: ReadonlySet<string> = new Set(["NEW_TASK", "TASK_REJECTED"]);

/** 需求端「需要你」口径（issue #49，spec 0002 §3.1 修订）：问答待答 + 门待拍板。 */
const ATTENTION_TYPES: ReadonlySet<string> = new Set(["AGENT_WAIT", "GATE_PENDING"]);

/**
 * 点击去向（spec 0003 §3，issue #44 深链落定）：opc 两型 → `/opc/tasks/{refId}`
 * （refId=taskId）；dev 四型 → 开发平台工作台对应功能点——
 * AGENT_WAIT → 对话模式定位等待点（`?wait=`）、GATE_PENDING → 门卡（`?focus=gate`）、
 * TASK_SUBMITTED / RETEST_READY → 任务面板（`?focus=tasks`）。锚点缺失返回 null 不导航。
 * base = 工作台路由前缀，开发平台缺省；需求端「需要你」行传 `/projects`（issue #49）。
 */
export function todoHref(todo: TodoItem, base = "/dev/projects"): string | null {
  if (OPC_TASK_TYPES.has(todo.type)) {
    return todo.refId ? `/opc/tasks/${todo.refId}` : null;
  }
  if (!todo.projectId) return null;
  switch (todo.type) {
    case "AGENT_WAIT":
      return todo.refId
        ? buildWorkbenchDeepLink({ kind: "wait", waitId: todo.refId }, todo.projectId, base)
        : `${base}/${todo.projectId}`;
    case "GATE_PENDING":
      return buildWorkbenchDeepLink({ kind: "gate" }, todo.projectId, base);
    case "TASK_SUBMITTED":
    case "RETEST_READY":
      return buildWorkbenchDeepLink({ kind: "tasks" }, todo.projectId, base);
    default:
      return `${base}/${todo.projectId}`;
  }
}

/**
 * 「需要你」行文案（需求端禁词红线，spec 0002 §5）：type 级用户侧措辞，不透传
 * 后端 todo title——AGENT_WAIT 口径的「智能体」等词不得见于需求端用户文案。
 */
const ATTENTION_LABELS: Record<string, string> = {
  AGENT_WAIT: "需要你：顾问在等你答复",
  GATE_PENDING: "需要你：有一件事等你拍板",
};

/** 需求端「需要你」行数据（issue #49）：用户侧文案 + 需求端工作台深链。 */
export type ProjectAttention = {
  label: string;
  href: string;
};

/**
 * 项目 → 「需要你」行数据（spec 0002 §3.1 修订，issue #49）：首页「最近的项目」
 * 卡与后续需求端卡片共用的聚合口径——AGENT_WAIT（问答待答）/ GATE_PENDING（门
 * 待拍板）两型按项目去重，输入新→旧（后端契约）每项目取最新一条；深链复用
 * todoHref 落需求端工作台（`/projects/{id}`，AGENT_WAIT → 等待点、GATE_PENDING
 * → 门卡）。任务型（TASK_SUBMITTED / RETEST_READY）是开发平台口径，不进需求端
 * 提醒。
 */
export function attentionByProject(todos: TodoItem[]): Map<string, ProjectAttention> {
  const map = new Map<string, ProjectAttention>();
  for (const todo of todos) {
    if (!ATTENTION_TYPES.has(todo.type) || !todo.projectId || map.has(todo.projectId)) continue;
    map.set(todo.projectId, {
      label: ATTENTION_LABELS[todo.type] ?? "需要你：有需要你处理的事",
      href: todoHref(todo, "/projects") ?? `/projects/${todo.projectId}`,
    });
  }
  return map;
}
