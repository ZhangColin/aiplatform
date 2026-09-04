import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ProjectSummary } from "@/lib/projects/list";

import { RecentProjectCard } from "./create-project-hero";

const project: ProjectSummary = {
  id: "123456",
  name: "官网改版",
  archived: false,
  createdAt: "2026-08-20T00:00:00Z",
};

describe("RecentProjectCard（首页最近项目卡：四态 · 更新时间）", () => {
  it("点击整卡进项目页；副行 = 四态标签 + 相对时间", () => {
    const html = renderToStaticMarkup(<RecentProjectCard project={project} />);
    expect(html).toContain('href="/projects/123456"');
    expect(html).toContain("官网改版");
    expect(html).toContain("进行中");
    expect(html).toContain("前"); // formatRelativeTime（zhCN 相对时间）
  });

  it("已归档卡不进首页（消费侧过滤），卡内四态如实标注", () => {
    const html = renderToStaticMarkup(
      <RecentProjectCard project={{ ...project, archived: true }} />,
    );
    expect(html).toContain("已归档");
  });

  it("未命名项目兜底文案（首字色块随之取「未」）", () => {
    const html = renderToStaticMarkup(
      <RecentProjectCard project={{ ...project, name: "" }} />,
    );
    expect(html).toContain("未命名项目");
  });
});
