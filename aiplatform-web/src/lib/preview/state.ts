import { ApiError } from "@/lib/api/api-error";
import type { GenerationState } from "@/lib/projects/detail";
import type { CoderRunStatus } from "@/lib/store/generation";

/**
 * 系统面板呈现态推导（#45 预览门禁解除 + 空态两档；#222 档位改吃生成态投影）：
 * 纯函数、无 React——输入 = 生成态投影（REST：never/generating/generated，与 SSE
 * 会话态无关——刷新/回访后档位仍正确）+ 更新轨会话信号（coderStatus——更新中/
 * 更新失败）+ 预览查询结果，输出面板该呈现哪一档。门禁口径：非「从未生成」
 * （起跑/已生成）即启动预览机制；应用可访问的判据 = REST 探活通过才返回 URL
 * （后端 WSP_012 语义），有 URL 即上页面。
 *
 * <p>空态两档（CONTEXT.md「预览」）：无应用 = 一句静态占位文案（#608 呈现面
 * 清场：「正在创建首页」渐进占位随工作消息部件退役——不再随过程部件推进）；
 * 有应用且 run 中 = 保留页面 +「更新中」轻提示——生成长出与更新同一套，不两套
 * 并存。重试静默（#84：run 失败为唯一失败终态）与跨会话不闪断：有 URL 就不退
 * 占位。</p>
 *
 * <p><b>失败只走会话态（#604 失败新语义＝明确展示＋重跑即恢复）</b>：原「生成
 * 中断」投影档随旧失败语义退役——run 失败/停止不落投影，失败档只由 SSE
 * run-failed（coderStatus=error）在会话内呈现；刷新/回访后未生成的项目回
 * 「从未生成」（idle 档）。恢复入口不留专用按钮（#608 呈现面清场：续跑/修正轨
 * 前端件与「继续生成」入口随续跑机器退役）——重跑即恢复走常规点火入口：首建
 * 失败后岔路口卡／PRD 确认按钮复现（#604），迭代失败后用户再提意见即重新起轨。</p>
 */

/** run 进行中、页面已可见的统一轻提示（生成长出与更新同一套，#45 合并）。 */
export const UPDATING_NOTICE = "正在更新系统，完成后自动刷新";

/** 预览真故障（非未就绪）的打不开口径（#80）。 */
export const TROUBLE_NOTICE = "预览暂时打不开，稍后会自动重试";

/** 恢复期用户可感知口径（CONTEXT.md「休眠」：可感知的只有「系统启动中」，#170）。 */
export const STARTING_NOTICE = "系统启动中";

/** 生成失败的呈现口径（会话内 run 失败终态——#604 起失败不落投影，只在此呈现；不判死）。 */
export const INTERRUPTED_NOTICE = "生成中断了，已完成的进度都保留";

/** 用户停止的呈现口径（#359 生成取消：停止是用户动作，不称「中断/失败」）。 */
export const STOPPED_NOTICE = "已停止生成，已完成的进度都保留";

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

/** 页面/占位上的轻提示（一套：进行中 / 失败）。 */
export type PanelNotice = {
  failed: boolean;
  text: string;
  /** 失败原因行（#355 run-failed 载荷 message；失败档可缺省）。 */
  reason?: string;
};

/** 系统面板呈现档位。 */
export type SystemPanelPhase =
  /** 未开始（从未生成）：空白浏览器窗 + 引导占位（重跑走常规点火入口，#608 面板不留专用按钮）。 */
  | { kind: "idle" }
  /** 第一档（无应用，run 中）：静态占位提示（#608 起不随过程部件推进）。 */
  | { kind: "hint"; text: string }
  /** 生成/更新失败（无页面，会话内 run 失败终态）：明确展示失败与原因（#604 新失败
   * 语义）；重跑即恢复走常规点火入口，面板不留专用恢复按钮。
   * reason = 失败原因行（#355 run-failed 载荷 message，可缺省）。 */
  | { kind: "failed"; text: string; reason?: string }
  /** 已有生成事实但 URL 未到：接通中；trouble = 真故障（非未就绪）。 */
  | { kind: "connecting"; trouble: boolean }
  /** 第二档（应用可访问）：真页面 + 进行中轻提示（可缺省）。 */
  | { kind: "page"; notice?: PanelNotice };

/**
 * 预览门禁（#45 解除完成门禁；#222 投影口径）：生成态投影非「从未生成」即启动预览
 * 机制（生成中/已生成）；更新轨会话信号在场同样启动。
 */
export function previewActive(
  generationState: GenerationState | undefined,
  coderStatus: CoderRunStatus | undefined,
): boolean {
  if (coderStatus !== undefined) return true;
  return generationState !== undefined && generationState !== "never";
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
  /** 生成态投影（REST 事实，三态无中断档；缺省 = 后端未透出，按会话态兜底）。 */
  generationState?: GenerationState;
  /** 本会话编码 run 状态（更新中/更新失败/生成失败——会话内失败终态只在此呈现）。 */
  coderStatus?: CoderRunStatus;
  /** 用户停止旗标（#359 run-failed 载荷 stopped；失败档文案分岔「已停止」）。 */
  coderStoppedByUser?: boolean;
  /** REST 探活通过才返回——有 URL 即应用可访问。 */
  url?: string;
  /** 预览查询的 error（未就绪 WSP_012 视同待期）。 */
  error?: unknown;
  /** 失败终态的用户面原因（#355 run-failed 载荷 message；失败档原因行）。 */
  failureReason?: string;
}): SystemPanelPhase {
  const { generationState, coderStatus, coderStoppedByUser, url, error, failureReason } = input;

  // 有 URL 即上页面（跨会话直接显示系统现状；重试静默期间不退占位——不闪断）
  if (url) {
    return { kind: "page", notice: noticeOf(generationState, coderStatus, coderStoppedByUser, failureReason) };
  }

  // 会话内失败终态先行呈现（#56 run 失败为唯一失败终态；#604 起失败不落投影——
  // 刷新后回 idle 档，恢复出口照常）：已生成 → 更新失败口径，否则生成失败口径；
  // 停止旗标（#359）改走「已停止」口径（用户动作，不称中断/失败）
  if (coderStatus === "error") {
    return { kind: "failed", reason: failureReason,
      ...failedOutcome(generationState, coderStoppedByUser) };
  }

  if (generationState === "generating" || coderStatus === "running") {
    // 无应用占位（生成中/更新在途同档）：静态文案以「系统已存在与否」取词
    // （generated → 更新；其余 → 初始化）——点火后的乐观登记窗口（投影未刷成
    // 生成中）同落初始化口径
    return {
      kind: "hint",
      text: generationState === "generated" ? "正在更新系统" : "正在初始化",
    };
  }
  if (generationState === "generated" || coderStatus === "finished") {
    // 已生成但 URL 未到 = 接通中；未就绪（WSP_012）同接通中。会话内已收口而投影
    // 未刷新（失效重拉在途）也走接通中平滑过渡，不闪回引导占位
    return { kind: "connecting", trouble: previewTrouble(error) };
  }
  return { kind: "idle" };
}

/** 页面上的轻提示（一套话术面：进行中 / 中断 / 停止 / 更新失败）。 */
function noticeOf(
  generationState: GenerationState | undefined,
  coderStatus: CoderRunStatus | undefined,
  coderStoppedByUser: boolean | undefined,
  failureReason?: string,
): PanelNotice | undefined {
  // 进行中优先（「继续生成」点击后的乐观起跑——投影未刷成生成中时会话信号先行回进行中）
  if (coderStatus === "running" || generationState === "generating") {
    return { failed: false, text: UPDATING_NOTICE };
  }
  if (coderStatus === "error") {
    return { failed: true, reason: failureReason,
      ...failedOutcome(generationState, coderStoppedByUser) };
  }
  return undefined;
}

/**
 * 失败终态文案（占位与页面轻提示同源）：已生成（投影）→ 更新失败口径；否则生成
 * 失败口径。停止旗标（#359 run-failed 载荷 stopped）改走「已停止」口径——停止是
 * 用户动作，不称「中断/失败」。恢复＝重跑（#604 新失败语义），不留专用按钮。
 */
function failedOutcome(generationState: GenerationState | undefined,
    coderStoppedByUser?: boolean): { text: string } {
  if (coderStoppedByUser) {
    return {
      text: generationState === "generated" ? "已停止更新" : STOPPED_NOTICE,
    };
  }
  return {
    text: generationState === "generated" ? "更新遇到了问题" : INTERRUPTED_NOTICE,
  };
}
