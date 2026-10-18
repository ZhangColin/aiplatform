import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { components } from "@/lib/api/schema";

import { ConvergenceCard } from "./convergence-card";

type DimensionStatus = components["schemas"]["DimensionStatus"];

/**
 * 收敛卡渲染契约（#346，spec #320 ⑥ 需求分析页左栏首张）：卡题「逐步澄清
 * 与收敛流水线」＋「n/5 维度就绪」徽章（#315 既定例外——不取原型「收敛度
 * 92%」百分比与进度条）＋五维 pill（✓ 就绪／⚡ 次要待定／灰 阻碍缺口——
 * 消费 #331 维度态投影）。无 PRD（闲聊期）＝五维全灰、0/5。
 */

function renderCard(dimensions?: DimensionStatus[]) {
  return renderToStaticMarkup(<ConvergenceCard dimensions={dimensions} />);
}

describe("ConvergenceCard（收敛卡）", () => {
  it("卡题与徽章话术逐字照原型（「逐步澄清与收敛流水线」＋「n/5 维度就绪」）", () => {
    const html = renderCard([
      { dimension: "目标与角色", status: "ready" },
      { dimension: "主流程", status: "ready" },
      { dimension: "功能与边界", status: "blocking" },
      { dimension: "数据与约束", status: "blocking" },
      { dimension: "验收", status: "blocking" },
    ]);
    expect(html).toContain("逐步澄清与收敛流水线");
    expect(html).toContain("2/5 维度就绪");
    // 无百分比（#315 既定例外：不用缺乏依据的精确百分比）
    expect(html).not.toContain("%");
  });

  it("五维 pill 三态：ready=✓／minor=⚡／blocking=灰（无图标）", () => {
    const html = renderCard([
      { dimension: "目标与角色", status: "ready" },
      { dimension: "主流程", status: "minor" },
      { dimension: "功能与边界", status: "blocking" },
      { dimension: "数据与约束", status: "ready" },
      { dimension: "验收", status: "blocking" },
    ]);
    expect(html).toContain("✓");
    expect(html).toContain("⚡");
    for (const name of ["目标与角色", "主流程", "功能与边界", "数据与约束", "验收"]) {
      expect(html).toContain(name);
    }
    expect(html).toContain("2/5 维度就绪");
  });

  it("无维度数据（闲聊期/后端未投影）：五维固定序词表全灰兜底＋0/5", () => {
    const html = renderCard(undefined);
    expect(html).toContain("0/5 维度就绪");
    for (const name of ["目标与角色", "主流程", "功能与边界", "数据与约束", "验收"]) {
      expect(html).toContain(name);
    }
    expect(html).not.toContain("✓");
    expect(html).not.toContain("⚡");
  });

  it("未知 status 值按灰 pill 防御（旧后端/形态漂移不炸）", () => {
    const html = renderCard([{ dimension: "验收", status: "weird" }]);
    expect(html).toContain("验收");
    expect(html).toContain("0/5 维度就绪");
  });
});
