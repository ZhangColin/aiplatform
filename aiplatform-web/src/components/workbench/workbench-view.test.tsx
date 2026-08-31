import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";

import { WorkbenchView } from "./workbench-view";

// 项目页装配的双态切换（#20 验收口径）：闲聊期（prdProducedAt 未落）指令区
// 占满全宽、成果区不渲染；PRD 产出后成果区长出（三模式 + PRD 正文）、指令区
// 退为左槽。数据/SSE 面 mock 掉——壳层结构断言，PRD 正文经 use-prd mock 直出。
const seed = vi.hoisted(() => ({ detail: undefined as ProjectDetail | undefined }));

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({
    data: seed.detail,
    isPending: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  }),
}));

vi.mock("@/hooks/use-chat", () => ({
  usePostMessage: () => ({ isPending: false, mutate: vi.fn() }),
  useAnswerQuestion: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({
    data: { content: "# 需求背景\n给宠物医院做预约管理系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/lib/sse/agent-channel", () => ({
  useAgentStreamChannel: () => {},
}));

vi.mock("@/lib/sse/provider", () => ({
  useSseStatus: () => "connected",
  useSseFallbackPolling: () => undefined,
}));

function detail(overrides: Partial<ProjectDetail> = {}): ProjectDetail {
  return { id: "p1", name: "宠物医院预约系统", ...overrides };
}

describe("WorkbenchView · 闲聊态 ↔ 成果区长出（#20）", () => {
  it("闲聊期（prdProducedAt 未落）：指令区占满全宽、无成果区页签与三模式", () => {
    seed.detail = detail({ prdProducedAt: null });

    const html = renderToStaticMarkup(<WorkbenchView projectId="p1" />);

    expect(html).not.toContain('data-slot="resizable-panel-group"');
    expect(html).not.toContain("docs/PRD.md");
    expect(html).toContain("和需求分析师聊聊你的想法");
  });

  it("PRD 产出（prdProducedAt 落定）：双槽长出——三模式页签 + PRD 正文", () => {
    seed.detail = detail({ prdProducedAt: "2026-08-31T08:00:00Z" });

    const html = renderToStaticMarkup(<WorkbenchView projectId="p1" />);

    expect(html).toContain('data-slot="resizable-panel-group"');
    expect(html.match(/data-slot="resizable-panel"/g)).toHaveLength(2);
    expect(html).toContain("文件");
    expect(html).toContain("系统");
    expect(html).toContain("项目");
    expect(html).toContain("docs/PRD.md");
    expect(html).toContain("给宠物医院做预约管理系统。");
  });

  it("PRD 已产出且未生成（#22）：对话流卡片 + 文件模式操作条双入口「开始做系统」", () => {
    seed.detail = detail({ prdProducedAt: "2026-08-31T08:00:00Z", generatedAt: null });

    const html = renderToStaticMarkup(<WorkbenchView projectId="p1" />);

    expect(html).toContain("需求整理好了，可以开始做系统");
    // 紧凑入口挂在文件模式操作条（PRD 头部）
    expect((html.match(/开始做系统/g) ?? []).length).toBeGreaterThanOrEqual(2);
  });

  it("闲聊期（PRD 未产出）：不出现「开始做系统」入口（无事可做）", () => {
    seed.detail = detail({ prdProducedAt: null });

    const html = renderToStaticMarkup(<WorkbenchView projectId="p1" />);

    expect(html).not.toContain("开始做系统");
  });

  it("已生成（generatedAt 落定）：入口退场——调整走指令区意见（迭代环）", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
    });

    const html = renderToStaticMarkup(<WorkbenchView projectId="p1" />);

    expect(html).not.toContain("开始做系统");
  });
});
