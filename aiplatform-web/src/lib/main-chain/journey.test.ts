import { describe, expect, it } from "vitest";

import { deriveStageProgress, type StageView } from "./stages";
import { JOURNEY_STEPS, mapStagesToJourney } from "./journey";

function sixStages(): StageView[] {
  return [
    { name: "BA", label: "需求", gateActor: "USER" },
    { name: "DEMO", label: "原型", gateActor: "USER" },
    { name: "DEV", label: "开发", gateActor: "DEV" },
    { name: "TEST", label: "测试" },
    { name: "ACCEPT", label: "验收", gateActor: "USER" },
    { name: "CLOSED", label: "关闭", terminal: true },
  ];
}

describe("journey · 段 ↔ 步映射（第 i 段 ↔ 第 i 步）", () => {
  it("六段按序 1:1 映射六步文案；状态随当前段推导", () => {
    const journey = mapStagesToJourney(deriveStageProgress(sixStages(), "DEV"));
    expect(journey.map((s) => s.label)).toEqual([
      "聊需求",
      "看原型",
      "制作中",
      "质检中",
      "验收",
      "交付",
    ]);
    expect(journey.map((s) => s.status)).toEqual([
      "done",
      "done",
      "current",
      "future",
      "future",
      "future",
    ]);
    // 每步透传段的 terminal（终步 = 交付）
    expect(journey[5]).toMatchObject({ terminal: true });
  });

  it("三扇门只挂用户侧门（BA/DEMO/ACCEPT 段）；开发段门（DEV）折叠不展示", () => {
    const journey = mapStagesToJourney(deriveStageProgress(sixStages(), "BA"));
    expect(journey[0].gateLabel).toBe("确认 PRD");
    expect(journey[1].gateLabel).toBe("确认原型");
    expect(journey[4].gateLabel).toBe("验收");
    expect(journey[2].gateLabel).toBeUndefined(); // DEV 段有 gateActor 但非用户侧门
    expect(journey[3].gateLabel).toBeUndefined();
    expect(journey[5].gateLabel).toBeUndefined();
  });

  it("终段命中：交付步为 current + terminal（终态渲染）", () => {
    const journey = mapStagesToJourney(deriveStageProgress(sixStages(), "CLOSED"));
    expect(journey[5]).toMatchObject({ status: "current", terminal: true, label: "交付" });
  });

  it("段序列长于六步（后端增段防御）：超出部分回退段 label，不抛不断", () => {
    const stages = [...sixStages(), { name: "EXTRA", label: "扩展段" }];
    const journey = mapStagesToJourney(deriveStageProgress(stages, "EXTRA"));
    expect(journey).toHaveLength(7);
    expect(journey[6]).toMatchObject({ label: "扩展段", status: "current" });
  });

  it("段序列短于六步（后端减段防御）：按实际段数映射，不越界", () => {
    const journey = mapStagesToJourney(deriveStageProgress(sixStages().slice(0, 3), "DEV"));
    expect(journey.map((s) => s.label)).toEqual(["聊需求", "看原型", "制作中"]);
  });

  it("空序列 / 未命中：产出空旅程或全 future，组件走防御分支", () => {
    expect(mapStagesToJourney(deriveStageProgress(undefined, "BA"))).toEqual([]);
    const missed = mapStagesToJourney(deriveStageProgress(sixStages(), "NO_SUCH"));
    expect(missed.every((s) => s.status === "future")).toBe(true);
  });
});

describe("journey · 用户侧文案红线（spec 0002 §5 禁词）", () => {
  /** 禁词：用户不得看到「阶段 / 状态机 / 智能体 / 期 / OPC / HITL / Demo」。 */
  const FORBIDDEN = ["阶段", "状态机", "智能体", "OPC", "HITL", "Demo", "期"];

  it("六步 label / hint 不含禁词", () => {
    for (const step of JOURNEY_STEPS) {
      for (const text of [step.label, step.hint]) {
        for (const word of FORBIDDEN) {
          expect(text, `「${text}」含禁词「${word}」`).not.toContain(word);
        }
      }
    }
  });

  it("映射产出的全部文案（含门名与防御回退 label）不含禁词", () => {
    const journey = mapStagesToJourney(deriveStageProgress(sixStages(), "BA"));
    for (const step of journey) {
      for (const text of [step.label, step.hint, step.gateLabel ?? ""]) {
        for (const word of FORBIDDEN) {
          expect(text, `「${text}」含禁词「${word}」`).not.toContain(word);
        }
      }
    }
  });
});
