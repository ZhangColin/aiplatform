import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { OutputsArea } from "./outputs-area";

// 成果区（#20）：文件 / 系统 / 项目三模式框架——文件模式 = PRD 文件呈现
// （本片唯一实装），系统 / 项目为后续切片占位。PRD 读口 mock 掉，正文断言
// 归 prd-panel.test。
vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({
    data: { content: "# 需求背景\n宠物医院预约系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  }),
}));

describe("OutputsArea · 成果区三模式（#20 文件模式实装）", () => {
  it("三模式页签就位：文件 / 系统 / 项目", () => {
    const html = renderToStaticMarkup(<OutputsArea projectId="p1" />);

    expect(html).toContain('data-slot="tabs-trigger"');
    expect(html).toContain("文件");
    expect(html).toContain("系统");
    expect(html).toContain("项目");
  });

  it("文件模式为默认：PRD 正文直出（docs/PRD.md 呈现）", () => {
    const html = renderToStaticMarkup(<OutputsArea projectId="p1" />);

    expect(html).toContain("docs/PRD.md");
    expect(html).toContain("宠物医院预约系统。");
  });
});
