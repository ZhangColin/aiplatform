import { ApiError } from "@/lib/api/api-error";
import type { GenerationState } from "@/lib/projects/detail";
import type { CoderRunStatus } from "@/lib/store/generation";
import type { WorkPart } from "@/lib/store/work-message";

/**
 * 系统面板呈现态推导（#45 预览门禁解除 + 空态两档；#222 档位改吃四态投影）：
 * 纯函数、无 React——输入 = 生成态四态投影（REST：never/generating/interrupted/
 * generated，与 SSE 会话态无关——刷新/回访后档位仍正确）+ 更新轨会话信号
 * （coderStatus——更新中/更新失败）+ 预览查询结果 +
 * 工作消息部件，输出面板该呈现哪一档。门禁口径：非「从未生成」（起跑/中断/已生成）
 * 即启动预览机制；应用可访问的判据 = REST 探活通过才返回 URL（后端 WSP_012 语义），
 * 有 URL 即上页面。
 *
 * <p>空态两档（CONTEXT.md「预览」）：无应用 = 占位随工作消息部件推进的步骤
 * 提示（解说自述优先、动作对象兜底，无信号落初始文案）；有应用且 run 中 =
 * 保留页面 +「更新中」轻提示——生成长出与更新同一套，不两套并存。重试静默
 * （#84：run 失败为唯一失败终态）与跨会话不闪断：有 URL 就不退占位。</p>
 *
 * <p><b>「继续生成」单出口（#222，ADR-0020）</b>：生成中断（REST 投影）与从未生成
 * （面板仅存在于 PRD 产出后——含存量无轨道表的中断项目，恢复同走计划重派）都给
 * 出口；无推倒重来按钮（重走对话提意见改 PRD）。更新轮失败仍归「继续更新」
 * （更新轨恢复出口）。</p>
 */

/** run 进行中、页面已可见的统一轻提示（生成长出与更新同一套，#45 合并）。 */
export const UPDATING_NOTICE = "正在更新系统，完成后自动刷新";

/** 预览真故障（非未就绪）的打不开口径（#80）。 */
export const TROUBLE_NOTICE = "预览暂时打不开，稍后会自动重试";

/** 恢复期用户可感知口径（CONTEXT.md「休眠」：可感知的只有「系统启动中」，#170）。 */
export const STARTING_NOTICE = "系统启动中";

/** 生成中断的呈现口径（#222 四态投影的中断档——失败终态/进程重启/中断同档，不判死）。 */
export const INTERRUPTED_NOTICE = "生成中断了，已完成的进度都保留";

/**
 * 后端「预览应用尚未就绪」的数字业务码（WSP_012 → 1012＝域码 WSP=1×1000＋序号，
 * 见 aiplatform-server ErrorCodePrefix）：HTTP 503 只是状态，判定认业务码。
 */
const PREVIEW_NOT_SERVING_CODE = 1012;

/**
 * 后端「系统启动中」的数字业务码（WSP_013 → 1013，#170 唤醒待期）：沙箱置备/
 * 唤醒重建/应用拉起进行中——触碰项目自动恢复，轮询续探，与 1012 同为待期非故障。
 */
const WORKSPACE_STARTING_CODE = 1013;

/**
 * 恢复入口判别（#222 单出口）：resume = 继续生成（生成中断/从未生成——断点续跑
 * 或计划重派）/ restart-update = 继续更新（更新轮失败——重派终态那场的交接物）。
 */
export type RecoveryAction = "resume" | "restart-update";

/** 页面/占位上的轻提示（一套：进行中 / 失败）。 */
export type PanelNotice = {
  failed: boolean;
  text: string;
  /** 恢复入口（RecoveryAction 同源）。 */
  recovery?: RecoveryAction;
};

/** 系统面板呈现档位。 */
export type SystemPanelPhase =
  /** 未开始（从未生成）：空白浏览器窗 + 引导占位（带「继续生成」出口——存量/未起跑恢复）。 */
  | { kind: "idle" }
  /** 第一档（无应用，run 中）：随工作消息部件推进的步骤提示。 */
  | { kind: "hint"; text: string }
  /** 生成中断（无页面）：中断提示 +「继续生成」入口；更新轮失败给「继续更新」。 */
  | { kind: "failed"; text: string; recovery?: RecoveryAction }
  /** 已有生成事实但 URL 未到：接通中；trouble = 真故障（非未就绪）。 */
  | { kind: "connecting"; trouble: boolean }
  /** 第二档（应用可访问）：真页面 + 进行中轻提示（可缺省）。 */
  | { kind: "page"; notice?: PanelNotice };

/**
 * 预览门禁（#45 解除完成门禁；#222 投影口径）：四态投影非「从未生成」即启动预览
 * 机制（生成中/中断——阶段 0 收口后应用可能已在跑/已生成）；更新轨会话信号在场
 * 同样启动。
 */
export function previewActive(
  generationState: GenerationState | undefined,
  coderStatus: CoderRunStatus | undefined,
): boolean {
  if (coderStatus !== undefined) return true;
  return generationState !== undefined && generationState !== "never";
}

/**
 * 占位步骤提示信号（起源 = 工作消息部件）：解说自述优先、
 * 动作对象短语兜底。自述取最新一段并压过其后的动作行——提示停在解说口径
 * （「正在创建首页」），不随逐文件动作跳变。
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

/**
 * 预览查询 error 是否「待期」（WSP_012 未就绪 / WSP_013 系统启动中——轮询继续，
 * 非 trouble）。判定只认数字业务码、不认 HTTP 状态（503 是传输层事实，语义归业务码）。
 */
export function isPreviewNotServing(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    (error.code === PREVIEW_NOT_SERVING_CODE || error.code === WORKSPACE_STARTING_CODE)
  );
}

/** 预览查询 error 是否真故障（有错且非未就绪；#80）。 */
export function previewTrouble(error: unknown): boolean {
  return error != null && !isPreviewNotServing(error);
}

/**
 * 地址栏 goto 解析（#125 地址胶囊改可编辑跳转）：把用户输入解析为应用 origin 内
 * 的目标地址——路径拼接（相对/绝对路径归到 origin 根）、同源绝对 URL 放行、
 * 跨源绝对 URL 拒绝（返回 undefined，不跳出沙箱预览）。baseUrl 无有效层级 origin
 * （about:blank 等不透明 origin、或无法解析）也返回 undefined（无可导航 origin）。
 */
export function resolvePreviewAddress(baseUrl: string, input: string): string | undefined {
  const origin = tryOrigin(baseUrl);
  if (!origin) return undefined;
  const trimmed = input.trim();
  if (!trimmed) return undefined;

  // 带 scheme 的绝对 URL：只放行同源，跨源拒绝
  const absolute = tryUrl(trimmed);
  if (absolute) return absolute.origin === origin ? absolute.href : undefined;

  // 其余视作应用内路径：拼到 origin 根，再校验未逃逸（协议相对 //host 等会换 origin）
  const path = trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  const resolved = new URL(path, origin);
  return resolved.origin === origin ? resolved.href : undefined;
}

/** URL → origin；解析失败或非层级 origin（不透明 origin 串为 "null"）返回 undefined。 */
function tryOrigin(raw: string): string | undefined {
  try {
    const origin = new URL(raw).origin;
    return origin === "null" ? undefined : origin;
  } catch {
    return undefined;
  }
}

/** 绝对 URL（带 scheme）才解析成功；相对输入抛错 → undefined。 */
function tryUrl(raw: string): URL | undefined {
  try {
    return new URL(raw);
  } catch {
    return undefined;
  }
}

/** 系统面板呈现档位的唯一推导入口。 */
export function systemPanelPhase(input: {
  /** 生成态四态投影（REST 事实；缺省 = 后端未透出，按会话态兜底）。 */
  generationState?: GenerationState;
  /** 本会话编码 run 状态（更新轨信号——更新中/更新失败；生成档位不依赖它）。 */
  coderStatus?: CoderRunStatus;
  /** REST 探活通过才返回——有 URL 即应用可访问。 */
  url?: string;
  /** 预览查询的 error（未就绪 WSP_012 视同待期）。 */
  error?: unknown;
  /** 当前工作消息部件（占位提示信号源）。 */
  parts: readonly WorkPart[];
}): SystemPanelPhase {
  const { generationState, coderStatus, url, error, parts } = input;

  // 有 URL 即上页面（跨会话直接显示系统现状；重试静默期间不退占位——不闪断）
  if (url) {
    return { kind: "page", notice: noticeOf(generationState, coderStatus) };
  }

  // 会话内失败终态先行呈现（#56 run 失败为唯一失败终态；REST 投影随失效重拉收敛
  // ——run-failed 即 invalidate 项目域）：已生成 → 更新失败口径，否则生成中断口径
  if (coderStatus === "error") {
    return { kind: "failed", ...failedOutcome(generationState) };
  }

  if (generationState === "generating" || coderStatus === "running") {
    // 无应用占位（生成中/更新在途同档）：随工作消息部件推进的步骤提示。回退文案
    // 以「系统已存在与否」取词（generated → 更新；其余 → 初始化）——idle 档点
    // 「继续生成」后的乐观登记窗口（投影未刷成生成中）同落初始化口径
    const hint = workHintOf(parts);
    return {
      kind: "hint",
      text: hint ?? (generationState === "generated" ? "正在更新系统" : "正在初始化"),
    };
  }
  if (generationState === "interrupted") {
    // 生成中断（投影）：刷新/回访后档位仍正确——「继续生成」出口挂本档
    return { kind: "failed", ...interruptedOutcome() };
  }
  if (generationState === "generated" || coderStatus === "finished") {
    // 已生成但 URL 未到 = 接通中；未就绪（WSP_012）同接通中。会话内已收口而投影
    // 未刷新（失效重拉在途）也走接通中平滑过渡，不闪回引导占位
    return { kind: "connecting", trouble: previewTrouble(error) };
  }
  return { kind: "idle" };
}

/** 页面上的轻提示（一套话术面：进行中 / 中断 / 更新失败）。 */
function noticeOf(
  generationState: GenerationState | undefined,
  coderStatus: CoderRunStatus | undefined,
): PanelNotice | undefined {
  // 进行中优先（「继续生成」点击后的乐观起跑——投影仍中断时会话信号先行回进行中）
  if (coderStatus === "running" || generationState === "generating") {
    return { failed: false, text: UPDATING_NOTICE };
  }
  if (coderStatus === "error") {
    return { failed: true, ...failedOutcome(generationState) };
  }
  if (generationState === "interrupted") {
    // 中断 + 有页面（部分切片已收口、应用在跑）：细条带「继续生成」，页面保留
    return { failed: true, ...interruptedOutcome() };
  }
  return undefined;
}

/** 生成中断口径（#222 单出口「继续生成」——占位与页面轻提示同源）。 */
function interruptedOutcome(): { text: string; recovery: "resume" } {
  return { text: INTERRUPTED_NOTICE, recovery: "resume" };
}

/**
 * 失败终态文案与恢复入口（占位与页面轻提示同源）：已生成（投影）→ 更新失败口径
 * 「继续更新」（更新轨恢复出口）；否则生成中断口径「继续生成」。
 */
function failedOutcome(generationState: GenerationState | undefined): {
  text: string;
  recovery: RecoveryAction;
} {
  return generationState === "generated"
    ? { text: "更新遇到了问题", recovery: "restart-update" }
    : interruptedOutcome();
}
