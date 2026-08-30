import { describe, expect, it } from "vitest";

import { deriveStageProgress } from "./stages";

/** 六段主链样本（形状镜像 swagger StageView；值取 A3 后端口径）。 */
function sixStages() {
  return [
    { name: "BA", label: "需求", gateActor: "USER" },
    { name: "DEMO", label: "原型", gateActor: "USER" },
    { name: "DEV", label: "开发", gateActor: "DEV" },
    { name: "TEST", label: "测试" },
    { name: "ACCEPT", label: "验收", gateActor: "USER" },
    { name: "CLOSED", label: "关闭", terminal: true },
  ];
}

describe("deriveStageProgress · 段序列推导", () => {
  it("中段命中：前面的段 done、命中段 current、其后 future", () => {
    const progress = deriveStageProgress(sixStages(), "TEST");
    expect(progress.currentIndex).toBe(3);
    expect(progress.current).toMatchObject({ name: "TEST" });
    expect(progress.steps.map((s) => s.status)).toEqual([
      "done",
      "done",
      "done",
      "current",
      "future",
      "future",
    ]);
    expect(progress.terminal).toBe(false);
  });

  it("终段命中：terminal=true（页面按终态渲染）", () => {
    const progress = deriveStageProgress(sixStages(), "CLOSED");
    expect(progress.currentIndex).toBe(5);
    expect(progress.terminal).toBe(true);
    expect(progress.steps.map((s) => s.status)).toEqual([
      "done",
      "done",
      "done",
      "done",
      "done",
      "current",
    ]);
  });

  it("首段命中：无 done，全 future 于其后", () => {
    const progress = deriveStageProgress(sixStages(), "BA");
    expect(progress.currentIndex).toBe(0);
    expect(progress.steps[0].status).toBe("current");
    expect(progress.steps.at(-1)?.status).toBe("future");
  });

  it("stage 未命中序列（键漂移防御）：currentIndex -1，步骤全 future，不抛", () => {
    const progress = deriveStageProgress(sixStages(), "NO_SUCH");
    expect(progress.currentIndex).toBe(-1);
    expect(progress.current).toBeNull();
    expect(progress.terminal).toBe(false);
    expect(progress.steps.every((s) => s.status === "future")).toBe(true);
  });

  it("stages 缺失 / 空数组：空步骤序列防御，不抛", () => {
    expect(deriveStageProgress(undefined, "BA").steps).toEqual([]);
    expect(deriveStageProgress([], "BA").currentIndex).toBe(-1);
    // stage 字段缺失同样落入未命中防御
    expect(deriveStageProgress(sixStages(), undefined).currentIndex).toBe(-1);
  });

  it("步骤携带原段定义（label/gateActor/terminal 原样透传，供渲染消费）", () => {
    const progress = deriveStageProgress(sixStages(), "BA");
    expect(progress.steps[1]).toMatchObject({
      index: 1,
      stage: { name: "DEMO", label: "原型", gateActor: "USER" },
    });
    expect(progress.steps[5]).toMatchObject({ stage: { terminal: true } });
  });
});
