import { ApiError } from "@/lib/api/api-error";
import type { CoderRunStatus } from "@/lib/store/generation";
import type { WorkPart } from "@/lib/store/work-message";

/**
 * 系统面板呈现态推导（#45 预览门禁解除 + 空态两档）：纯函数、无 React——
 * 输入 = 生成面事实（coderStatus / generatedAt）+ 预览查询结果 + 工作消息部件，输出
 * 面板该呈现哪一档。门禁口径：run 开始（含发起成功的乐观登记）或已有生成事实
 * 即启动预览机制，不等 run-finish 纪元；应用可访问的判据 = REST 探活通过才返回
 * URL（后端 WSP_012 语义），有 URL 即上页面。
 *
 * <p>空态两档（CONTEXT.md「预览」）：无应用 = 占位随工作消息部件推进的步骤
 * 提示（解说自述优先、动作对象兜底，无信号落初始文案）；有应用且 run 中 =
 * 保留页面 +「更新中」轻提示——生成长出与修正同一套，不两套并存。重试静默
 * （#84：run 失败为唯一失败终态）与跨会话不闪断：有 URL 就不退占位。</p>
 */

/** run 进行中、页面已可见的统一轻提示（生成长出与修正同一套，#45 合并）。 */
export const UPDATING_NOTICE = "正在更新系统，完成后自动刷新";

/** 预览真故障（非未就绪）的打不开口径（系统面板与新窗口独立页共用，#80）。 */
export const TROUBLE_NOTICE = "预览暂时打不开，稍后会自动重试";

/** 后端「预览应用尚未就绪」错误码（WSP_012，503——轮询继续，非故障）。 */
const PREVIEW_NOT_SERVING_CODE = "WSP_012";

/** 页面/占位上的进行中轻提示（一套：进行中 / 重试 / 失败）。 */
export type PanelNotice = {
  failed: boolean;
  text: string;
  /** 超限终态的人工兜底入口（#48）：restart = 重新发起（从未生成）/ refix = 重新修改（修正轮）。 */
  recovery?: "restart" | "refix";
};

/** 系统面板呈现档位。 */
export type SystemPanelPhase =
  /** 未开始：空白浏览器窗 + 引导占位。 */
  | { kind: "idle" }
  /** 第一档（无应用，run 中）：随工作消息部件推进的步骤提示。 */
  | { kind: "hint"; text: string }
  /** 超限终态且无页面：问题提示 + 人工兜底入口（restart / refix，可缺省）。 */
  | { kind: "failed"; text: string; recovery?: "restart" | "refix" }
  /** 已有生成事实但 URL 未到：接通中；trouble = 真故障（非未就绪）。 */
  | { kind: "connecting"; trouble: boolean }
  /** 第二档（应用可访问）：真页面 + 进行中轻提示（可缺省）。 */
  | { kind: "page"; notice?: PanelNotice };

/** 预览门禁（#45 解除完成门禁）：run 开始或已有生成事实即启动预览机制。 */
export function previewActive(
  coderStatus: CoderRunStatus | undefined,
  generatedAt: string | null | undefined,
): boolean {
  return coderStatus !== undefined || generatedAt != null;
}

/**
 * 占位步骤提示信号（起源 = 工作消息部件）：解说自述优先、
 * 动作对象短语兜底；步骤分组段非用户语言不参与。自述取最新一段并压过其后的动作
 * 行——提示停在解说口径（「正在创建首页」），不随逐文件动作跳变。
 */
export function workHintOf(parts: readonly WorkPart[]): string | undefined {
  let action: string | undefined;
  for (let i = parts.length - 1; i >= 0; i--) {
    const part = parts[i];
    if (part.kind === "text") return part.text;
    if (part.kind === "action" && action === undefined) action = part.label;
  }
  return action;
}

/** 预览查询 error 是否「应用尚未就绪」（WSP_012——视同待期，轮询继续）。 */
export function isPreviewNotServing(error: unknown): boolean {
  return error instanceof ApiError && error.code === PREVIEW_NOT_SERVING_CODE;
}

/** 预览查询 error 是否真故障（有错且非未就绪；系统面板与新窗口独立页共用，#80）。 */
export function previewTrouble(error: unknown): boolean {
  return error != null && !isPreviewNotServing(error);
}

/** 系统面板呈现档位的唯一推导入口。 */
export function systemPanelPhase(input: {
  coderStatus?: CoderRunStatus;
  generatedAt?: string | null;
  /** REST 探活通过才返回——有 URL 即应用可访问。 */
  url?: string;
  /** 预览查询的 error（未就绪 WSP_012 视同待期）。 */
  error?: unknown;
  /** 当前工作消息部件（占位提示信号源）。 */
  parts: readonly WorkPart[];
}): SystemPanelPhase {
  const { coderStatus, generatedAt, url, error, parts } = input;

  // 有 URL 即上页面（跨会话直接显示系统现状；重试静默期间不退占位——不闪断）
  if (url) {
    return { kind: "page", notice: noticeOf(coderStatus, generatedAt) };
  }
  if (!previewActive(coderStatus, generatedAt)) return { kind: "idle" };

  if (coderStatus === "error") {
    return { kind: "failed", ...failedOutcome(generatedAt) };
  }
  if (coderStatus === "running") {
    const hint = workHintOf(parts);
    return {
      kind: "hint",
      text: hint ?? (generatedAt != null ? "正在更新系统" : "正在初始化"),
    };
  }
  // finished / 仅有生成事实：URL 未到 = 接通中；未就绪（WSP_012）同接通中
  return { kind: "connecting", trouble: previewTrouble(error) };
}

/** 页面上的进行中轻提示（一套话术面：进行中 / 失败）。 */
function noticeOf(
  coderStatus: CoderRunStatus | undefined,
  generatedAt: string | null | undefined,
): PanelNotice | undefined {
  switch (coderStatus) {
    case "running":
      return { failed: false, text: UPDATING_NOTICE };
    case "error":
      return { failed: true, ...failedOutcome(generatedAt) };
    default:
      return undefined;
  }
}

/**
 * 超限终态文案与兜底入口（占位与页面轻提示同源，#48 对齐两档）：从未生成给
 * 「重新发起」，修正轮给「重新修改」（重派终态那场的交接物）——正常流程全自动，
 * 手动触发只出现在故障终态。
 */
function failedOutcome(generatedAt: string | null | undefined): {
  text: string;
  recovery: "restart" | "refix";
} {
  return generatedAt == null
    ? { text: "生成遇到了问题", recovery: "restart" }
    : { text: "修正遇到了问题", recovery: "refix" };
}
