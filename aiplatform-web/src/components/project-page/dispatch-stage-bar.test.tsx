import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { DispatchStageState } from "@/lib/store/dispatch-stage";

import { DispatchStageBar } from "./dispatch-stage-bar";

// 直读种子状态渲染（zustand v5 server snapshot 限制同 command-area.test）；
// 帧→状态映射的纯逻辑由 lib/chat/dispatch-stage.test 覆盖，这里只验呈现挂接。
const seed = vi.hoisted(() => ({
  stages: { stages: {} } as Pick<DispatchStageState, "stages">,
}));

vi.mock("@/lib/store/dispatch-stage", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/dispatch-stage")>();
  return {
    ...actual,
    useDispatchStageStore: <T,>(
      selector: (state: Pick<DispatchStageState, "stages">) => T,
    ): T => selector(seed.stages),
  };
});

function seedStage(stage: string, changed?: boolean) {
  seed.stages = { stages: { p1: { stage, changed } as never } };
}

describe("DispatchStageBar · 派发阶段状态条（#50）", () => {
  it("无状态（链未开口 / 发送重置后）不渲染", () => {
    seed.stages = { stages: {} };
    expect(renderToStaticMarkup(<DispatchStageBar projectId="p1" />)).toBe("");
  });

  it("进行中阶段呈现文案（分析中）", () => {
    seedStage("analyzing");
    const markup = renderToStaticMarkup(<DispatchStageBar projectId="p1" />);
    expect(markup).toContain("正在分析您的意见");
    expect(markup).toContain("animate-spin"); // 进行中转圈
  });

  it("挂起阶段等用户（不转圈）", () => {
    seedStage("clarifying");
    const markup = renderToStaticMarkup(<DispatchStageBar projectId="p1" />);
    expect(markup).toContain("等待您回答上面的问题");
    expect(markup).not.toContain("animate-spin");
  });

  it("完成态区分：已修改 / 未动系统", () => {
    seedStage("done", true);
    expect(renderToStaticMarkup(<DispatchStageBar projectId="p1" />)).toContain(
      "已按您的意见修改了系统",
    );
    seedStage("done", false);
    expect(renderToStaticMarkup(<DispatchStageBar projectId="p1" />)).toContain(
      "本轮意见未改动系统",
    );
  });

  it("派发失败终态（#51）：如实呈现重提文案，不转圈、失败配色", () => {
    seedStage("dispatch-failed");
    const markup = renderToStaticMarkup(<DispatchStageBar projectId="p1" />);
    expect(markup).toContain("派发失败，请重提您的意见");
    expect(markup).not.toContain("animate-spin");
    expect(markup).toContain("text-destructive"); // 失败终态非成功收口
  });

  it("别项目的阶段不串条", () => {
    seedStage("fixing");
    expect(renderToStaticMarkup(<DispatchStageBar projectId="p2" />)).toBe("");
  });
});
