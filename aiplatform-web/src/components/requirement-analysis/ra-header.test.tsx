import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { PrdBadge } from "@/lib/projects/prd-badge";

import { RequirementAnalysisHeader } from "./ra-header";

/**
 * 需求分析页粘性二级 header（#346，照原型）：代号胶囊（真实项目标识／
 * STAGE-01）＋项目名＋PRD 版本徽标（#330 版本事实＋#334 停驻草稿）＋
 * 「对话调优」「导出 (PDF/Word)」＋主 CTA（草稿/新草稿＝确认门「确认此版
 * PRD 并生成 Demo」——#332 门机制；已确认无停驻＝「前往 Demo 体验」阶段
 * 推进位）。确认门本体在此 mock 成桩（两段式层归 prd-confirm-gate 自测）。
 */

vi.mock("../project-page/prd-confirm-gate", () => ({
  PrdConfirmGate: () => <button type="button">确认此版 PRD 并生成 Demo</button>,
}));

function renderHeader({
  badge = null,
  hasPrd = false,
}: {
  badge?: PrdBadge | null;
  hasPrd?: boolean;
} = {}) {
  return renderToStaticMarkup(
    <RequirementAnalysisHeader
      projectId="p1"
      projectName="智能企业客户预约与工单调度系统"
      badge={badge}
      hasPrd={hasPrd}
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

  it("草稿待确认徽标＋确认门主 CTA", () => {
    const html = renderHeader({ badge: { kind: "draft" }, hasPrd: true });
    expect(html).toContain("PRD 草稿待确认");
    expect(html).toContain("确认此版 PRD 并生成 Demo");
  });

  it("vN 已确认徽标＋主 CTA 换「前往 Demo 体验」（链 Demo 阶段页）", () => {
    const html = renderHeader({ badge: { kind: "confirmed", versionNo: 2 }, hasPrd: true });
    expect(html).toContain("PRD v2 已确认");
    expect(html).not.toContain("确认此版 PRD 并生成 Demo");
    expect(html).toContain("前往 Demo 体验");
    expect(html).toContain('href="/projects/p1/demo"');
  });

  it("新草稿待确认徽标（基于 vN）＋确认门主 CTA 回场", () => {
    const html = renderHeader({ badge: { kind: "redraft", versionNo: 2 }, hasPrd: true });
    expect(html).toContain("PRD 新草稿待确认 · 当前 v2");
    expect(html).toContain("确认此版 PRD 并生成 Demo");
  });

  it("无 PRD（闲聊期）：不出徽标、不出确认门 CTA", () => {
    const html = renderHeader({ badge: null, hasPrd: false });
    expect(html).not.toContain("PRD ");
    expect(html).not.toContain("确认此版 PRD 并生成 Demo");
    expect(html).not.toContain("前往 Demo 体验");
  });
});
