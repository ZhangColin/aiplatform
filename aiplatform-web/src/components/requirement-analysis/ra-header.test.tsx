import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { PrdBadge } from "@/lib/projects/prd-badge";

import { RequirementAnalysisHeader } from "./ra-header";

/**
 * 需求分析页粘性二级 header（#346，照原型；#462 去确认门改造，ADR-0039；#630
 * 旧 PRD 版本面退场）：代号胶囊（真实项目标识／STAGE-01）＋项目名＋PRD 草稿
 * 徽标＋「对话调优」「导出 (PDF/Word)」＋主 CTA（首个构建已起跑＝「前往 Demo
 * 体验」阶段推进位——未起跑无 CTA）。
 */

function renderHeader({
  badge = null,
  hasPrd = false,
  generationStarted = false,
}: {
  badge?: PrdBadge | null;
  hasPrd?: boolean;
  generationStarted?: boolean;
} = {}) {
  return renderToStaticMarkup(
    <RequirementAnalysisHeader
      projectId="p1"
      projectName="智能企业客户预约与工单调度系统"
      badge={badge}
      hasPrd={hasPrd}
      generationStarted={generationStarted}
      onFocusChat={() => {}}
      getArticleHtml={() => null}
    />,
  );
}

describe("RequirementAnalysisHeader · 结构与话术（照原型）", () => {
  it("代号胶囊（真实项目标识＋STAGE-01）＋项目名＋「对话调优」「导出 (PDF/Word)」", () => {
    const html = renderHeader();
    expect(html).toContain("p1");
    expect(html).toContain("STAGE-01");
    expect(html).toContain("智能企业客户预约与工单调度系统");
    expect(html).toContain("对话调优");
    expect(html).toContain("导出 (PDF/Word)");
  });

  it("草稿徽标、无 CTA（首个构建未起跑不出动作位）", () => {
    const html = renderHeader({ badge: { kind: "draft" }, hasPrd: true });
    expect(html).toContain("PRD 草稿");
    expect(html).not.toContain("确认此版 PRD");
    expect(html).not.toContain("前往 Demo 体验");
  });

  it("首个构建已起跑：主 CTA「前往 Demo 体验」（链 Demo 阶段页）", () => {
    const html = renderHeader({
      badge: { kind: "draft" },
      hasPrd: true,
      generationStarted: true,
    });
    expect(html).toContain("PRD 草稿");
    expect(html).toContain("前往 Demo 体验");
    expect(html).toContain('href="/projects/p1/demo"');
  });

  it("无 PRD（闲聊期）：不出徽标、不出 CTA", () => {
    const html = renderHeader({ badge: null, hasPrd: false });
    expect(html).not.toContain("PRD ");
    expect(html).not.toContain("前往 Demo 体验");
  });
});
