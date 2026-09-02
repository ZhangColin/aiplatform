import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";

import { ProjectPageView } from "./project-page-view";

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

vi.mock("@/hooks/use-project-files", () => ({
  useProjectFiles: () => ({
    data: [{ path: "AGENTS.md", size: 7 }, { path: "docs/PRD.md", size: 12 }],
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

// 订单数据口（#28）：下单动作 stub；订单详情按用例播种（OrderPanel 消费）
const orderSeed = vi.hoisted(() => ({
  order: undefined as Record<string, unknown> | undefined,
}));
vi.mock("@/hooks/use-order", () => ({
  usePlaceOrder: () => ({ isPending: false, mutate: vi.fn() }),
  useOrder: () => ({ data: orderSeed.order, isPending: orderSeed.order === undefined }),
  useCancelOrder: () => ({ isPending: false, mutate: vi.fn() }),
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

describe("ProjectPageView · 闲聊态 ↔ 成果区长出（#20）", () => {
  it("闲聊期（prdProducedAt 未落）：指令区占满全宽、无成果区页签与三模式", () => {
    seed.detail = detail({ prdProducedAt: null });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).not.toContain('data-slot="resizable-panel-group"');
    expect(html).not.toContain("docs/PRD.md");
    expect(html).toContain("和平台聊聊你的想法");
  });

  it("PRD 产出（prdProducedAt 落定）：双槽长出——三模式页签 + PRD 正文", () => {
    seed.detail = detail({ prdProducedAt: "2026-08-31T08:00:00Z" });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

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

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).toContain("需求整理好了，可以开始做系统");
    // 紧凑入口挂在文件模式操作条（PRD 头部）
    expect((html.match(/开始做系统/g) ?? []).length).toBeGreaterThanOrEqual(2);
  });

  it("闲聊期（PRD 未产出）：不出现「开始做系统」入口（无事可做）", () => {
    seed.detail = detail({ prdProducedAt: null });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).not.toContain("开始做系统");
  });

  it("已生成（generatedAt 落定）：入口退场——调整走指令区意见（迭代环）", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).not.toContain("开始做系统");
  });

  // ---------- 「确认下单」按钮装配（#26 迭代环①：随首次生成完成常驻输入条上方） ----------

  it("已生成（零迭代）：输入条上方出「确认下单」（可见性规则细节归纯逻辑测试）", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).toContain("确认下单");
  });

  // ---------- 订单锁定装配（#28 交易环①：下单即冻结——锁定式矩阵待报价行） ----------

  it("挂着待报价订单：指令区禁用出锁定提示、「确认下单」退场（锁定式矩阵接线）", () => {
    // 订单卡内容归 order-panel.test（SSR 只渲染激活 tab，项目模式非缺省）
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
      activeOrder: { id: "o1", status: 1, statusName: "待报价" },
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).toContain("订单处理中——如需继续修改，请取消订单");
    expect(html).toContain("disabled");
    expect(html).not.toContain("确认下单");
  });

  it("无订单（迭代态）：指令区可输入、无锁定提示", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).toContain("和平台聊聊你的想法");
    expect(html).not.toContain("订单处理中");
    expect(html).toContain("确认下单"); // 迭代态常驻入口
  });

  it("未生成 / 已归档：不出「确认下单」", () => {
    seed.detail = detail({ prdProducedAt: "2026-08-31T08:00:00Z", generatedAt: null });
    expect(renderToStaticMarkup(<ProjectPageView projectId="p1" />)).not.toContain("确认下单");

    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
      archived: true,
    });
    expect(renderToStaticMarkup(<ProjectPageView projectId="p1" />)).not.toContain("确认下单");
  });
});
