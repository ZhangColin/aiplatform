import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ProjectSummary } from "@/lib/projects/list";
import type { ProjectAttention } from "@/lib/todos/todo";

import { RecentProjectRow } from "./create-project-hero";

const project: ProjectSummary = {
  id: "123456",
  name: "官网改版",
  stage: "PROTOTYPE",
  stageLabel: "看原型",
  archived: false,
  createdAt: "2026-08-20T00:00:00Z",
};

const attention: ProjectAttention = {
  label: "需要你：顾问在等你答复",
  href: "/projects/123456?wait=w789",
};

function rowHtml(props: { project: ProjectSummary; attention?: ProjectAttention }) {
  return renderToStaticMarkup(<RecentProjectRow {...props} />);
}

describe("RecentProjectRow（首页最近项目卡行，issue #49）", () => {
  it("有待办：整行深链直达等待点 +「需要你」amber 行（type 级文案，无禁词）", () => {
    const html = rowHtml({ project, attention });
    expect(html).toContain('href="/projects/123456?wait=w789"');
    expect(html).toContain("需要你：顾问在等你答复");
    expect(html).toContain("text-amber-600");
    // 安心态行不出现
    expect(html).not.toContain("看原型");
  });

  it("无待办：普通进项目 + 安心态行（阶段推进中，缺省「进行中」）", () => {
    const html = rowHtml({ project });
    expect(html).toContain('href="/projects/123456"');
    expect(html).toContain("看原型");
    expect(html).not.toContain("需要你：");
    expect(rowHtml({ project: { ...project, stageLabel: "" } })).toContain("进行中");
  });
});
