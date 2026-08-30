import type { components } from "@/lib/api/schema";

/**
 * HITL 等待点数据层（issue #45，spec 0001 §5）：`GET /projects/{id}/agent/waits`
 * （跨会话 PENDING）→ 等待点归一，`POST …/waits/{waitId}/settle` 三型载荷构造，
 * body 收窄。要点：
 * - **body 是引擎载荷原样**（后端不归一，swagger 只给 `Map<String,Object>` 松散
 *   型），问答卡形状 = demo `pendingQuestions`、审批卡形状 = demo `APPROVAL`——
 *   收窄函数按字段存在性防御取值，缺字段给中性兜底，呈现不崩。
 * - **kind 两值**：1=问答 / 2=权限（统一承载，body 判别具体形状）。
 * - **settle 三型**：answer（answers 二维，按题序，每项=选中标签列表，custom 也
 *   作标签）/ permission（approve 布尔）/ deferred（转任务，assigneeAccountId
 *   string → int64 收口于此，同 task.ts 构造器）。
 */

export type ProjectWaitResponse = components["schemas"]["ProjectWaitResponse"];
export type ProjectWaitSettleCommand = components["schemas"]["ProjectWaitSettleCommand"];
export type DeferredTaskPayload = components["schemas"]["DeferredTaskPayload"];

/** wait kind Integer code（swagger 注释口径）。 */
export const WAIT_KIND = { QUESTION: 1, PERMISSION: 2 } as const;

/** wait status Integer code（1=待处理 2=已答复 3=已失效 4=已清理）。 */
export const WAIT_STATUS = { PENDING: 1, ANSWERED: 2, EXPIRED: 3, CLEARED: 4 } as const;

/** 消费口径：字段全缺防御归一（body 保留原样，收窄交给具体卡体）。 */
export type Wait = {
  waitId: string;
  /** 1=问答 / 2=权限。 */
  kind: number;
  kindName: string;
  status: number;
  statusName: string;
  /** 中性短文本（后端生成，不含智能体产出内容）。 */
  summary: string;
  runId: string;
  raisedAt: string;
  settledAt: string;
  settleOutcome: number;
  settleOutcomeName: string;
  /** 引擎载荷原样，卡体按 kind 收窄。 */
  body: unknown;
};

export function normalizeWait(raw: ProjectWaitResponse): Wait {
  return {
    waitId: raw.waitId ?? "",
    kind: raw.kind ?? 0,
    kindName: raw.kindName ?? "",
    status: raw.status ?? 0,
    statusName: raw.statusName ?? "",
    summary: raw.summary ?? "",
    runId: raw.runId ?? "",
    raisedAt: raw.raisedAt ?? "",
    settledAt: raw.settledAt ?? "",
    settleOutcome: raw.settleOutcome ?? 0,
    settleOutcomeName: raw.settleOutcomeName ?? "",
    body: raw.body,
  };
}

/** 是否为问答等待点（kind 未知时按 body 有无 questions 兜底判别，不炸不丢）。 */
export function isQuestionWait(wait: Wait): boolean {
  if (wait.kind === WAIT_KIND.QUESTION) return true;
  if (wait.kind !== WAIT_KIND.PERMISSION && typeof wait.body === "object" && wait.body !== null) {
    return Array.isArray((wait.body as Record<string, unknown>).questions);
  }
  return false;
}

// ── 问答卡 body 收窄（demo pendingQuestions 形状）────────────────────────────

export type QuestionOption = { label: string; description: string };
export type WaitQuestion = {
  header: string;
  question: string;
  multiple: boolean;
  custom: boolean;
  options: QuestionOption[];
};
export type QuestionBody = { from: string; questions: WaitQuestion[] };

/** 选项 label 必取（无 label 的选项无意义，整项丢弃）；description 缺省空串。 */
function narrowQuestionOption(raw: unknown): QuestionOption | null {
  if (typeof raw !== "object" || raw === null) return null;
  const record = raw as Record<string, unknown>;
  if (typeof record.label !== "string" || record.label === "") return null;
  return {
    label: record.label,
    description: typeof record.description === "string" ? record.description : "",
  };
}

function narrowWaitQuestion(raw: unknown): WaitQuestion | null {
  if (typeof raw !== "object" || raw === null) return null;
  const record = raw as Record<string, unknown>;
  const options = Array.isArray(record.options)
    ? record.options
        .map(narrowQuestionOption)
        .filter((opt): opt is QuestionOption => opt !== null)
    : [];
  const custom = record.custom === true;
  // 选项全缺且无自定义输入 → 该题无可答内容，整题丢弃（custom-only 题仍可作答，保留）
  if (options.length === 0 && !custom) return null;
  return {
    header: typeof record.header === "string" ? record.header : "",
    question: typeof record.question === "string" ? record.question : "",
    multiple: record.multiple === true,
    custom,
    options,
  };
}

/** body 收窄为问答卡形状：无 questions 数组时回退空题集（呈现层给空态文案）。 */
export function narrowQuestionBody(body: unknown): QuestionBody {
  if (typeof body !== "object" || body === null) return { from: "", questions: [] };
  const record = body as Record<string, unknown>;
  const questions = Array.isArray(record.questions)
    ? record.questions
        .map(narrowWaitQuestion)
        .filter((q): q is WaitQuestion => q !== null)
    : [];
  return {
    from: typeof record.from === "string" ? record.from : "",
    questions,
  };
}

// ── 审批卡 body 收窄（demo APPROVAL 形状）───────────────────────────────────

export type PermissionBody = {
  tool: string;
  /** 工具入参（pre 展示）；引擎侧可能用 args / pre 命名，二者都认。 */
  args: string;
  reason: string;
  /** 过期分钟数，缺省 30（spec 0001 §5）。 */
  expiresInMin: number;
};

/** 工具入参：demo APPROVAL 形状 `tool`/`args`，`pre` 作入参别名（spec 0001 §5「入参 pre」）。 */
export function narrowPermissionBody(body: unknown): PermissionBody {
  const record: Record<string, unknown> =
    typeof body === "object" && body !== null ? (body as Record<string, unknown>) : {};
  const stringAt = (key: string): string => {
    const value = record[key];
    return typeof value === "string" && value !== "" ? value : "";
  };
  return {
    tool: stringAt("tool"),
    args: stringAt("args") || stringAt("pre"),
    reason: stringAt("reason"),
    expiresInMin:
      typeof record.expiresInMin === "number" ? record.expiresInMin : 30,
  };
}

// ── settle 三型载荷构造（issue #45）────────────────────────────────────────

/**
 * 问答答复：answers 二维按题序，每项=选中标签列表（custom 也作标签）。每项
 * trim 后过滤空串（空 custom 归一，不产生空标签）。
 */
export function buildAnswerCommand(answers: string[][]): ProjectWaitSettleCommand {
  return {
    type: "answer",
    answers: answers.map((labels) => labels.map((label) => label.trim()).filter(Boolean)),
  };
}

/** 权限批准 / 拒绝：approve 布尔（true 批准 once / false 拒绝）。 */
export function buildPermissionCommand(approve: boolean): ProjectWaitSettleCommand {
  return { type: "permission", approve };
}

/** 转任务：task 必填；content 空串省略；assigneeAccountId string → int64 收口。 */
export function buildDeferredCommand(
  title: string,
  content: string,
  assigneeAccountId: string,
): ProjectWaitSettleCommand {
  const task: DeferredTaskPayload = {
    title: title.trim(),
    assigneeAccountId: Number(assigneeAccountId),
  };
  const trimmedContent = content.trim();
  if (trimmedContent) task.content = trimmedContent;
  return { type: "deferred", task };
}

// ── 呈现推导 ────────────────────────────────────────────────────────────────

/** kind → 队列卡类型文案（提问 / 审批）；未知回退「等待」。 */
export function waitKindLabel(kind: number): string {
  switch (kind) {
    case WAIT_KIND.QUESTION:
      return "提问";
    case WAIT_KIND.PERMISSION:
      return "审批";
    default:
      return "等待";
  }
}
