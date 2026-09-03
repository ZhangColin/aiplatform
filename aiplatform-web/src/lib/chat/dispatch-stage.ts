/**
 * 派发阶段帧 → 状态条视图（#50 阶段状态条，纯逻辑单点）：`dispatch-stage` 帧
 * stage 值的名册镜像 + 帧→呈现映射。不署智能体名（分派对用户隐式，阶段进度
 * 可见）；文案遵循「生成」词条 Avoid（不出现「开发」「构建」）。
 *
 * 意见链：analyzing →（clarifying 挂起，答复后续跑 / updating-prd 可选）→
 * dispatching | queued → fixing（接直播——细节在直播侧栏）→ done（changed 区分
 * 「已修改」与「未动系统」）；派发失败落 dispatch-failed 终态（不悬死在派发中，
 * 重提即兜底）。咨询链：analyzing → answered。帧序即阶段序，
 * 项目内最新帧即当前阶段（跨 run：BA 轮锚 → 修正 run 锚，链在项目面上连续）。
 */

/** stage 值名册（镜像服务端 DispatchStage 枚举 wire 值，正本 = SSE事件清单）。 */
export const DISPATCH_STAGES = [
  "analyzing",
  "clarifying",
  "updating-prd",
  "dispatching",
  "fixing",
  "queued",
  "done",
  "dispatch-failed",
  "answered",
] as const;

export type DispatchStage = (typeof DISPATCH_STAGES)[number];

/** 状态条当前态（store 载荷；changed 仅 done 携带）。 */
export type DispatchBarState = {
  stage: DispatchStage;
  changed?: boolean;
};

/** tone 驱动呈现形态：active 转圈进行中 / waiting 等用户或排队 / settled 收口 /
 * failed 失败终态（派发失败如实呈现，非成功收口）。 */
export type DispatchBarTone = "active" | "waiting" | "settled" | "failed";

export type DispatchBarView = {
  /** 用户侧文案（单点正本；无智能体名、无「开发/构建」）。 */
  text: string;
  tone: DispatchBarTone;
};

/** stage 值收窄（payload 信任转型的配套守卫）：名册外 → undefined（忽略不炸）。 */
export function asDispatchStage(value: unknown): DispatchStage | undefined {
  return (DISPATCH_STAGES as readonly string[]).includes(value as string)
    ? (value as DispatchStage)
    : undefined;
}

/** 帧序步进（last-wins：帧序即阶段序，项目内最新帧即当前阶段）。 */
export function nextDispatchBarState(
  prev: DispatchBarState | undefined,
  frame: DispatchBarState,
): DispatchBarState {
  // 同帧重放幂等；新链的 analyzing 直接覆盖上一链的终态
  return prev?.stage === frame.stage && prev.changed === frame.changed ? prev : frame;
}

/** 状态条视图映射（帧 → 文案与形态；无状态 = 不渲染）。 */
export function dispatchBarView(state: DispatchBarState | undefined): DispatchBarView | undefined {
  if (!state) return undefined;
  switch (state.stage) {
    case "analyzing":
      return { text: "正在分析您的意见…", tone: "active" };
    case "clarifying":
      // 挂起停在等用户：问答卡在对话流里，状态条只说「在等」
      return { text: "等待您回答上面的问题，回答后继续处理", tone: "waiting" };
    case "updating-prd":
      return { text: "正在更新 PRD…", tone: "active" };
    case "dispatching":
      return { text: "正在安排修改系统…", tone: "active" };
    case "queued":
      return { text: "已并入下一轮修改，等待当前修改完成", tone: "waiting" };
    case "fixing":
      // 接直播：修正细节在直播侧栏逐段呈现，状态条只持阶段位
      return { text: "正在修改系统…", tone: "active" };
    case "done":
      // 完成态区分（#50）：已修改 / 未动系统（未动原因另见对话流通告）
      return state.changed
        ? { text: "已按您的意见修改了系统", tone: "settled" }
        : { text: "本轮意见未改动系统", tone: "settled" };
    case "dispatch-failed":
      // 派发失败终态（#51）：如实告知重提——意见已消费、不自动重试，重提即兜底
      return { text: "派发失败，请重提您的意见", tone: "failed" };
    case "answered":
      return { text: "已答复您的咨询", tone: "settled" };
  }
}
