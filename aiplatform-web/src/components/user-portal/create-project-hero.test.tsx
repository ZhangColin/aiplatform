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

describe("RecentProjectRow（首页最近项目行，issue #17 单门户骨架）", () => {
  it("点击整行进项目页；副行 = 状态名", () => {
    const html = renderToStaticMarkup(<RecentProjectRow project={{ ...project, statusName: "进行中" }} />);
    expect(html).toContain('href="/projects/123456"');
    expect(html).toContain("官网改版");
    expect(html).toContain("进行中");
  });

  it("状态名缺失：退创建时间相对文案", () => {
    const html = renderToStaticMarkup(<RecentProjectRow project={project} />);
    // formatRelativeTime（zhCN 相对时间）非空即兜底生效，不落「进行中」缺省
    expect(html).toContain("前");
    expect(html).not.toContain("进行中");
  });
});
