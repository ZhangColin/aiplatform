import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ProjectSummary } from "@/lib/projects/list";

import { RecentProjectRow } from "./create-project-hero";

const project: ProjectSummary = {
  id: "123456",
  name: "官网改版",
  archived: false,
  createdAt: "2026-08-20T00:00:00Z",
};

describe("RecentProjectRow（首页最近项目行：四态 · 更新时间）", () => {
  it("点击整行进项目页；副行 = 四态标签 + 相对时间", () => {
    const html = renderToStaticMarkup(<RecentProjectRow project={project} />);
    expect(html).toContain('href="/projects/123456"');
    expect(html).toContain("官网改版");
    expect(html).toContain("进行中");
    expect(html).toContain("前"); // formatRelativeTime（zhCN 相对时间）
  });

  it("已归档行不进首页（消费侧过滤），行内四态如实标注", () => {
    const html = renderToStaticMarkup(
      <RecentProjectRow project={{ ...project, archived: true }} />,
    );
    expect(html).toContain("已归档");
  });
});
