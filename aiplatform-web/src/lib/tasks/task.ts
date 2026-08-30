import type { components } from "@/lib/api/schema";

/**
 * 任务系统（issue #22，spec 0003 §2/§2.7）：OPC 任务门户 + dev 任务/Bug 面板的
 * 数据层归一。要点：
 * - **被驳回不是 status**：驳回后状态回执行中（2），由 status + rejectReason 派生
 *   destructive 呈现（spec §2.1/§2.4）。
 * - **提交载荷两形状**由详情 bugs[] 判别（非空 = 复测）；同给/同缺后端 400 TASK_006，
 *   表单侧按判别只构造一种。
 * - **ID 类型不对称**：响应侧 bugId / accountId 为 string，提交侧为 int64——
 *   Number() 转换收口在本文件的构造器，不散落组件。
 * - submittedPayload 是 Map 形态泛化对象，消费侧经 narrowSubmittedPayload 断言收窄。
 */

export type TaskCardResponse = components["schemas"]["TaskCardResponse"];
export type TaskResponse = components["schemas"]["TaskResponse"];
export type TaskDetailResponse = components["schemas"]["TaskDetailResponse"];
export type BugResponse = components["schemas"]["BugResponse"];
export type AccountResponse = components["schemas"]["AccountResponse"];
export type CreateTaskCommand = components["schemas"]["CreateTaskCommand"];
export type SubmitTaskCommand = components["schemas"]["SubmitTaskCommand"];
export type RejectTaskCommand = components["schemas"]["RejectTaskCommand"];
export type CloseBugCommand = components["schemas"]["CloseBugCommand"];

// ── 枚举码（唯一散点，消费侧只拿 label/tone）────────────────────────────────

/** 任务 status Integer code（swagger 注释口径）。 */
export const TASK_STATUS = {
  PUBLISHED: 1,
  RUNNING: 2,
  SUBMITTED: 3,
  CONFIRMED: 4,
  CANCELLED: 5,
} as const;

const TASK_STATUS_LABEL: Record<number, string> = {
  [TASK_STATUS.PUBLISHED]: "已发布",
  [TASK_STATUS.RUNNING]: "执行中",
  [TASK_STATUS.SUBMITTED]: "已提交",
  [TASK_STATUS.CONFIRMED]: "已确认",
  [TASK_STATUS.CANCELLED]: "已取消",
};

/** severity 四档（1=致命 2=严重 3=一般 4=轻微）；表单 select 的选项源。 */
export const SEVERITY_OPTIONS = [
  { value: 1, label: "致命" },
  { value: 2, label: "严重" },
  { value: 3, label: "一般" },
  { value: 4, label: "轻微" },
] as const;

const SEVERITY_LABEL: Record<number, string> = Object.fromEntries(
  SEVERITY_OPTIONS.map((o) => [o.value, o.label]),
);

/** Bug status Integer code（1=待修复 2=已修复 3=复测通过）。 */
export const BUG_STATUS = { OPEN: 1, FIXED: 2, VERIFIED: 3 } as const;

/** 徽章 tone（同 todo/项目列表的徽章配色语汇，组件侧映射成类名）。 */
export type StatusTone = "amber" | "primary" | "default" | "destructive" | "success" | "muted";

// ── 消费口径类型 ────────────────────────────────────────────────────────────

export type TaskCard = {
  taskId: string;
  projectId: string;
  projectName: string;
  /** 工作区未建时为 null（后端口径：预览地址必带，未建给 null）。 */
  previewUrl: string | null;
  title: string;
  content: string;
  status: number;
  statusName: string;
  rejectReason: string;
  createdAt: string;
};

export type Bug = {
  bugId: string;
  title: string;
  description: string;
  reproSteps: string;
  severity: number;
  severityName: string;
  status: number;
  statusName: string;
  createdAt: string;
};

/** submittedPayload 收窄后的首轮 Bug 行（重交预填 / dev 明细呈现共用）。 */
export type SubmittedBug = {
  title: string;
  description: string;
  reproSteps: string;
  severity: number;
};

/** submittedPayload 收窄后的复测结果行（bugId 归一为响应侧 string）。 */
export type SubmittedResult = {
  bugId: string;
  pass: boolean;
  note: string;
};

export type SubmittedPayload = {
  report: string;
  bugs: SubmittedBug[];
  results: SubmittedResult[];
};

export type Task = {
  taskId: string;
  projectId: string;
  title: string;
  content: string;
  status: number;
  statusName: string;
  rejectReason: string;
  assigneeName: string;
  createdAt: string;
  confirmedAt: string;
  submittedPayload: SubmittedPayload;
};

export type TaskDetail = Task & {
  projectName: string;
  previewUrl: string | null;
  bugs: Bug[];
};

export type Account = {
  accountId: string;
  displayName: string;
};

// ── 归一 ────────────────────────────────────────────────────────────────────

export function normalizeTaskCard(raw: TaskCardResponse): TaskCard {
  return {
    taskId: raw.taskId ?? "",
    projectId: raw.projectId ?? "",
    projectName: raw.project?.name ?? "",
    previewUrl: raw.project?.previewUrl ?? null,
    title: raw.title ?? "",
    content: raw.content ?? "",
    status: raw.status ?? 0,
    statusName: raw.statusName ?? "",
    rejectReason: raw.rejectReason ?? "",
    createdAt: raw.createdAt ?? "",
  };
}

export function normalizeBug(raw: BugResponse): Bug {
  return {
    bugId: raw.bugId ?? "",
    title: raw.title ?? "",
    description: raw.description ?? "",
    reproSteps: raw.reproSteps ?? "",
    severity: raw.severity ?? 0,
    severityName: raw.severityName ?? "",
    status: raw.status ?? 0,
    statusName: raw.statusName ?? "",
    createdAt: raw.createdAt ?? "",
  };
}

export function normalizeTask(raw: TaskResponse): Task {
  return {
    taskId: raw.taskId ?? "",
    projectId: raw.projectId ?? "",
    title: raw.title ?? "",
    content: raw.content ?? "",
    status: raw.status ?? 0,
    statusName: raw.statusName ?? "",
    rejectReason: raw.rejectReason ?? "",
    assigneeName: raw.assigneeName ?? "",
    createdAt: raw.createdAt ?? "",
    confirmedAt: raw.confirmedAt ?? "",
    submittedPayload: narrowSubmittedPayload(raw.submittedPayload),
  };
}

export function normalizeTaskDetail(raw: TaskDetailResponse): TaskDetail {
  return {
    ...normalizeTask(raw.task ?? {}),
    projectName: raw.project?.name ?? "",
    previewUrl: raw.project?.previewUrl ?? null,
    bugs: (raw.bugs ?? []).map(normalizeBug),
  };
}

export function normalizeAccount(raw: AccountResponse): Account {
  return { accountId: raw.accountId ?? "", displayName: raw.displayName ?? "" };
}

// ── 呈现推导 ────────────────────────────────────────────────────────────────

/** 被驳回 = 执行中 + 有驳回理由（驳回后状态回执行中，spec §2.7）。 */
export function isRejectedTask(status: number, rejectReason: string): boolean {
  return status === TASK_STATUS.RUNNING && rejectReason !== "";
}

export function taskStatusTone(status: number, rejectReason: string): StatusTone {
  if (isRejectedTask(status, rejectReason)) return "destructive";
  switch (status) {
    case TASK_STATUS.PUBLISHED:
      return "amber";
    case TASK_STATUS.RUNNING:
      return "default";
    case TASK_STATUS.SUBMITTED:
      return "primary";
    case TASK_STATUS.CONFIRMED:
      return "success";
    case TASK_STATUS.CANCELLED:
      return "muted";
    default:
      return "default";
  }
}

/** label 优先服务端 statusName；被驳回覆盖为「被驳回」；缺枚举回退「未知」。 */
export function taskStatusLabel(status: number, statusName: string, rejectReason: string): string {
  if (isRejectedTask(status, rejectReason)) return "被驳回";
  return statusName || TASK_STATUS_LABEL[status] || "未知";
}

export function severityLabel(severity: number, severityName = ""): string {
  return severityName || SEVERITY_LABEL[severity] || "未知";
}

/** severity tone：致命 destructive / 严重 amber / 一般 default / 轻微 muted。 */
export function severityTone(severity: number): StatusTone {
  switch (severity) {
    case 1:
      return "destructive";
    case 2:
      return "amber";
    case 4:
      return "muted";
    default:
      return "default";
  }
}

export function bugStatusTone(status: number): StatusTone {
  switch (status) {
    case BUG_STATUS.OPEN:
      return "amber";
    case BUG_STATUS.FIXED:
      return "primary";
    case BUG_STATUS.VERIFIED:
      return "success";
    default:
      return "default";
  }
}

/**
 * 待修复（OPEN）Bug 数——「派发修复」按钮的计数与可派发态来源（>0 即可派发）。
 * dispatch-fixes 是幂等手动端点（后端只派 OPEN ∧ 无修复 run 引用，in-flight 空转），
 * 前端只做存在性。
 */
export function countOpenBugs(bugs: Bug[]): number {
  return bugs.filter((bug) => bug.status === BUG_STATUS.OPEN).length;
}

/** 提交形状判别（spec §2.3/§2.7）：详情 bugs[] 非空 = 复测，否则首轮。 */
export function isRetestTask(bugs: Bug[]): boolean {
  return bugs.length > 0;
}

/** 列表呈现序：创建时间新→旧（ISO 字符串字典序即时间序）；返回新数组不改原。 */
export function byCreatedAtDesc<T extends { createdAt: string }>(items: T[]): T[] {
  return [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
}

// ── submittedPayload 收窄（Map 形态泛化对象 → 断言取值）─────────────────────

export function narrowSubmittedPayload(raw: unknown): SubmittedPayload {
  const empty: SubmittedPayload = { report: "", bugs: [], results: [] };
  if (typeof raw !== "object" || raw === null) return empty;
  const record = raw as Record<string, unknown>;
  return {
    report: typeof record.report === "string" ? record.report : "",
    bugs: Array.isArray(record.bugs)
      ? record.bugs.map(narrowSubmittedBug).filter((bug): bug is SubmittedBug => bug !== null)
      : [],
    results: Array.isArray(record.results)
      ? record.results
          .map(narrowSubmittedResult)
          .filter((result): result is SubmittedResult => result !== null)
      : [],
  };
}

function narrowSubmittedBug(raw: unknown): SubmittedBug | null {
  if (typeof raw !== "object" || raw === null) return null;
  const record = raw as Record<string, unknown>;
  return {
    title: typeof record.title === "string" ? record.title : "",
    description: typeof record.description === "string" ? record.description : "",
    reproSteps: typeof record.reproSteps === "string" ? record.reproSteps : "",
    severity: typeof record.severity === "number" ? record.severity : 0,
  };
}

function narrowSubmittedResult(raw: unknown): SubmittedResult | null {
  if (typeof raw !== "object" || raw === null) return null;
  const record = raw as Record<string, unknown>;
  const bugId = record.bugId;
  return {
    // 后端回显提交侧的 int64（JSON number）；归一为响应侧 string 口径
    bugId: typeof bugId === "number" || typeof bugId === "string" ? String(bugId) : "",
    pass: record.pass === true,
    note: typeof record.note === "string" ? record.note : "",
  };
}

// ── 提交载荷构造（两形状 + ID 转换收口）─────────────────────────────────────

/** 首轮 Bug 表单行（截图位降级：无 attachments，spec §2.5）。 */
export type BugDraft = {
  title: string;
  description: string;
  reproSteps: string;
  severity: number;
};

/** 复测表单行（bugId 为响应侧 string，构造时转 int64）。 */
export type RetestDraft = {
  bugId: string;
  pass: boolean;
  note: string;
};

/** 首轮形状：{ report, bugs }——bugs 空数组 = 测试全过（spec §2.7）。 */
export function buildFirstRoundPayload(report: string, bugs: BugDraft[]): SubmitTaskCommand {
  return {
    report: report.trim(),
    bugs: bugs.map((bug) => ({
      title: bug.title.trim(),
      description: bug.description.trim(),
      reproSteps: bug.reproSteps.trim(),
      severity: bug.severity,
    })),
  };
}

/** 复测形状：{ report, results }——bugId string → int64 在此收口。 */
export function buildRetestPayload(report: string, results: RetestDraft[]): SubmitTaskCommand {
  return {
    report: report.trim(),
    results: results.map((result) => ({
      bugId: Number(result.bugId),
      pass: result.pass,
      note: result.note.trim() || undefined,
    })),
  };
}

/** dev 建任务：assigneeAccountId string → int64 在此收口。 */
export function buildCreateTaskCommand(
  title: string,
  content: string,
  assigneeAccountId: string,
): CreateTaskCommand {
  return {
    title: title.trim(),
    content: content.trim(),
    assigneeAccountId: Number(assigneeAccountId),
  };
}

/**
 * 关闭 Bug（issue #38，spec 0003 §2.7）完整入参：bugId string → int64（路径参，
 * 与响应侧 string 口径不对称，Number() 收口于此）+ reason 去空格（body 载荷）。
 */
export function buildCloseBugInput(
  bugId: string,
  reason: string,
): { bugId: number; command: CloseBugCommand } {
  return { bugId: Number(bugId), command: { reason: reason.trim() } };
}
