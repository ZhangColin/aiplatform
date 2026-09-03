import { describe, expect, it } from "vitest";

import {
  asDispatchStage,
  DISPATCH_STAGES,
  dispatchBarView,
  nextDispatchBarState,
  type DispatchBarState,
} from "@/lib/chat/dispatch-stage";

/** 帧序走一遍（last-wins 步进），返回终态——边界用例的走链助手。 */
function walk(frames: DispatchBarState[], from?: DispatchBarState): DispatchBarState | undefined {
  return frames.reduce<DispatchBarState | undefined>(nextDispatchBarState, from);
}

describe("dispatch-stage 帧收窄", () => {
  it("名册全集可收窄", () => {
    for (const stage of DISPATCH_STAGES) {
      expect(asDispatchStage(stage)).toBe(stage);
    }
  });

  it("名册外值 → undefined（前向兼容忽略）", () => {
    expect(asDispatchStage("generating")).toBeUndefined();
    expect(asDispatchStage(undefined)).toBeUndefined();
  });
});

describe("dispatch-stage 帧→状态条视图（#50）", () => {
  it("意见链全程：分析中 → 更新 PRD 中 → 修正中 → 完成（已修改）", () => {
    const views = (["analyzing", "updating-prd", "dispatching", "fixing"] as const).map((stage) =>
      dispatchBarView({ stage }),
    );
    expect(views.map((view) => view?.text)).toEqual([
      "正在分析您的意见…",
      "正在更新 PRD…",
      "正在安排修改系统…",
      "正在修改系统…",
    ]);
    expect(views.map((view) => view?.tone)).toEqual(["active", "active", "active", "active"]);
    expect(dispatchBarView(walk([{ stage: "done", changed: true }]))).toEqual({
      text: "已按您的意见修改了系统",
      tone: "settled",
    });
  });

  it("完成态区分：changed=false → 未动系统", () => {
    expect(dispatchBarView({ stage: "done", changed: false })).toEqual({
      text: "本轮意见未改动系统",
      tone: "settled",
    });
    expect(dispatchBarView({ stage: "done", changed: true })).toEqual({
      text: "已按您的意见修改了系统",
      tone: "settled",
    });
  });

  it("咨询链：分析中 → 已答复", () => {
    const state = walk([{ stage: "analyzing" }, { stage: "answered" }]);
    expect(dispatchBarView(state)).toEqual({ text: "已答复您的咨询", tone: "settled" });
  });

  it("派发失败终态（#51）：分析中 → 派发中 → 派发失败——如实告知重提，不悬死在派发中", () => {
    const state = walk([{ stage: "analyzing" }, { stage: "dispatching" }, { stage: "dispatch-failed" }]);
    expect(dispatchBarView(state)).toEqual({
      text: "派发失败，请重提您的意见",
      tone: "failed",
    });
    // 重提即兜底：新链开口（发送侧重置 / analyzing 覆盖）不滞留失败终态
    const reopened = nextDispatchBarState(state, { stage: "analyzing" });
    expect(dispatchBarView(reopened)?.text).toBe("正在分析您的意见…");
  });

  it("无状态不渲染", () => {
    expect(dispatchBarView(undefined)).toBeUndefined();
  });

  it("用户可见文案不出现「开发」「构建」（生成词条 Avoid）且不署智能体名", () => {
    const all = [...DISPATCH_STAGES].flatMap((stage) => [
      dispatchBarView({ stage, changed: true }),
      dispatchBarView({ stage, changed: false }),
    ]);
    for (const view of all) {
      expect(view).toBeDefined();
      expect(view!.text).not.toMatch(/开发|构建/);
      expect(view!.text).not.toMatch(/智能体|分析师|编码|助理/);
    }
  });
});

describe("dispatch-stage 边界（挂起/排队/零动作）", () => {
  it("挂起边界：停在追问中（等用户），答复后续跑走完链", () => {
    // 意见 → 追问挂起：状态条停在「等您回答」，不静默不跳变
    const suspended = walk([
      { stage: "analyzing" },
      { stage: "clarifying" },
    ]);
    expect(dispatchBarView(suspended)).toEqual({
      text: "等待您回答上面的问题，回答后继续处理",
      tone: "waiting",
    });

    // 答复 → 回到分析 → 更新 PRD → 修正 → 完成
    const resumed = walk(
      [
        { stage: "analyzing" },
        { stage: "updating-prd" },
        { stage: "dispatching" },
        { stage: "fixing" },
        { stage: "done", changed: true },
      ],
      suspended,
    );
    expect(dispatchBarView(resumed)).toEqual({
      text: "已按您的意见修改了系统",
      tone: "settled",
    });
  });

  it("排队边界：修正 run 在途，新意见如实呈现排队（不误报起跑）", () => {
    // 第一条意见在修正中；第二条意见分析后并入下一轮（dispatching → queued）
    const state = walk([
      { stage: "analyzing" },
      { stage: "fixing" }, // 第一条意见的修正 run 在途
      { stage: "analyzing" }, // 第二条意见受理
      { stage: "dispatching" },
      { stage: "queued" },
    ]);
    expect(dispatchBarView(state)).toEqual({
      text: "已并入下一轮修改，等待当前修改完成",
      tone: "waiting",
    });

    // 首场完成后合并续场：回到修正中，终态如实到达
    const merged = walk([{ stage: "fixing" }, { stage: "done", changed: true }], state);
    expect(dispatchBarView(merged)?.text).toBe("已按您的意见修改了系统");
  });

  it("排队期间首场完成：done（首场）如实闪现后回到修正中（合并场），终态不缺席", () => {
    // 同一项目同一用户：排队中先见首场的完成（服务端不吞帧——项目内最新帧即
    // 当前阶段），紧接合并场起跑回修正中——最终终态覆盖首场终态，不误导
    const state = walk([
      { stage: "queued" },
      { stage: "done", changed: true }, // 首场收口（排队意见尚未处理）
      { stage: "fixing" }, // 排队意见合并续场起跑
      { stage: "done", changed: true },
    ]);
    expect(dispatchBarView(state)).toEqual({
      text: "已按您的意见修改了系统",
      tone: "settled",
    });
    // 中途快照：首场 done 后确有回落修正中（帧序可回放）
    const mid = walk([{ stage: "queued" }, { stage: "done", changed: true }]);
    expect(dispatchBarView(mid)?.text).toBe("已按您的意见修改了系统");
  });

  it("零动作边界：BA 不追问不改 PRD，状态条完整走完（不经追问/更新 PRD，不静默）", () => {
    const states: Array<DispatchBarState | undefined> = [];
    let state: DispatchBarState | undefined;
    for (const frame of [
      { stage: "analyzing" },
      { stage: "dispatching" },
      { stage: "fixing" },
      { stage: "done", changed: false },
    ] as DispatchBarState[]) {
      state = nextDispatchBarState(state, frame);
      states.push(state);
    }
    // 逐阶段推进：每一步都有呈现（「意见被处理了」全程可见），终态未动系统
    expect(states.map((s) => dispatchBarView(s)?.tone)).toEqual([
      "active",
      "active",
      "active",
      "settled",
    ]);
    expect(dispatchBarView(state)).toEqual({
      text: "本轮意见未改动系统",
      tone: "settled",
    });
  });

  it("新链开口直接覆盖上一链终态（重放/回声不滞留旧阶段）", () => {
    const finished = walk([
      { stage: "analyzing" },
      { stage: "done", changed: true },
    ]);
    const reopened = nextDispatchBarState(finished, { stage: "analyzing" });
    expect(dispatchBarView(reopened)?.text).toBe("正在分析您的意见…");
  });

  it("同帧重复到达幂等（重放不抖动）", () => {
    const first = nextDispatchBarState(undefined, { stage: "fixing" });
    expect(nextDispatchBarState(first, { stage: "fixing" })).toBe(first);
  });
});
