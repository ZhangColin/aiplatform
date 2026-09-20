import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";

import { ProjectPageView } from "./project-page-view";

// 项目页装配的双态切换（#20 验收口径）：闲聊期（prdProducedAt 未落）对话区
// 占满全宽、成果区不渲染；PRD 产出后成果区长出（三模式 + PRD 正文）、对话区
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

vi.mock("@/hooks/use-conversation", () => ({
  useConversation: () => ({}),
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

vi.mock("@/hooks/use-resume-generation", () => ({
  useResumeGeneration: () => ({ isPending: false, mutate: vi.fn() }),
}));

// 系统范式（默认激活）内「重新修改」按钮的 mutation 口 + 预览地址读口
//（system-panel.test 同款；无 QueryClientProvider，query 口全 mock）
vi.mock("@/hooks/use-restart-fix", () => ({
  useRestartFix: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({ data: undefined, isPending: false, isError: false }),
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

vi.mock("@/lib/sse/agent-event-channel", () => ({
  useAgentEventChannel: () => {},
}));

vi.mock("@/lib/sse/provider", () => ({
  useSseStatus: () => "connected",
  useSseFallbackPolling: () => undefined,
}));

function detail(overrides: Partial<ProjectDetail> = {}): ProjectDetail {
  return { id: "p1", name: "宠物医院预约系统", ...overrides };
}

describe("ProjectPageView · 闲聊态 ↔ 成果区长出（#20）", () => {
  it("闲聊期（prdProducedAt 未落）：对话区占满全宽、无成果区页签与三模式", () => {
    seed.detail = detail({ prdProducedAt: null });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).not.toContain('data-slot="resizable-panel-group"');
    expect(html).not.toContain("docs/PRD.md");
    expect(html).toContain("和平台聊聊你的想法");
  });

  it("PRD 产出（prdProducedAt 落定）：成果区自动滑出——双槽 + tab 簇（系统/文档默认挂载，激活 = 系统）", () => {
    seed.detail = detail({ prdProducedAt: "2026-08-31T08:00:00Z" });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).toContain('data-slot="resizable-panel-group"');
    expect(html.match(/data-slot="resizable-panel"/g)).toHaveLength(2);
    // tab 簇即标题条：默认挂载「系统」「文档」+「+ 新标签页」；仅激活面渲染
    expect(html).toContain('role="tablist"');
    expect(html).toContain("系统");
    expect(html).toContain("文档");
    expect(html).toContain("新标签页");
    expect(html).not.toContain('data-tree-file'); // 文件范式未挂载（按需）
  });

  it("PRD 已产出且未生成（#101 生成无门）：不再渲染「开始做系统」卡片，系统范式空态留引导占位 +「继续生成」出口（#222 idle 档）", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: null,
      generationState: "never",
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    // 生成无门自动发起：主智能体产出 PRD 后平台自动派生成，正常流不再出现任何
    // 「开始做系统」按钮/卡片；系统范式空态留一句引导占位（生成自动发起即被取代）
    expect(html).not.toContain("差不多清楚了，剩下的交给我");
    expect(html).not.toContain("开始做系统");
    expect(html).toContain("系统生成后，这里会出现可以操作的你的系统");
    // #222 单出口：idle 档也挂「继续生成」（未起跑/存量无轨道的恢复口），无推倒重来
    expect(html).toContain("继续生成");
    expect(html).not.toContain("重新发起");
  });

  it("闲聊期（PRD 未产出）：不出现「开始做系统」入口（无事可做）", () => {
    seed.detail = detail({ prdProducedAt: null });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).not.toContain("开始做系统");
  });

  it("已生成（generatedAt 落定）：入口退场——调整走对话区意见（迭代环）", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).not.toContain("开始做系统");
  });

  // ---------- 「确认下单」按钮装配（#26 迭代环①：随首次生成完成常驻顶栏右上角） ----------

  it("已生成（零迭代）：顶栏右上角出「确认下单」（可见性规则细节归纯逻辑测试）", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect(html).toContain("确认下单");
  });

  // ---------- 订单锁定装配（#28 交易环①：下单即冻结——锁定式矩阵待报价行） ----------

  it("挂着待报价订单：对话区禁用出锁定提示、「确认下单」退场（锁定式矩阵接线）", () => {
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

  it("无订单（迭代态）：对话区可输入、无锁定提示", () => {
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

  // ---------- 订单 tab 回访自动挂载（#205 三面渗透：挂载 + 状态点，不切换） ----------

  /** 成果区 tablist 切片（tab 条起至「新标签页」触发器——mobile 双页签也有
   * role=tab，全页计数会混入，切片内只数范式 tab）。 */
  function outputsTabStrip(html: string) {
    return html.slice(html.indexOf('aria-label="成果区"'), html.indexOf('aria-label="新标签页"'));
  }

  it("回访挂着待报价订单：订单 tab 自动挂载（簇上三枚）+ 琥珀状态点，激活面仍是「系统」", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
      activeOrder: { id: "o1", status: 1, statusName: "待报价" },
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    // 自动挂载：默认「系统」「文档」+「订单」= 三枚 tab
    const strip = outputsTabStrip(html);
    expect((strip.match(/role="tab"/g) ?? []).length).toBe(3);
    // 不自动切换：唯一激活面仍是「系统」，订单面板主体未渲染（挂载≠激活）
    expect((strip.match(/role="tab" aria-selected="true"/g) ?? []).length).toBe(1);
    const selectedAt = strip.indexOf('role="tab" aria-selected="true"');
    expect(strip.slice(selectedAt, selectedAt + 800)).toContain("系统");
    expect(html).not.toContain("已收到您的订单，后台正在评估报价");
    // 状态点：待报价 = 琥珀等待
    expect(html).toMatch(/data-slot="order-status-dot" title="待报价" aria-hidden="true" class="[^"]*bg-amber-500/);
  });

  it("回访挂着已报价订单（待支付）：状态点转主色行动档（与路标层最强档同语言）", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
      activeOrder: { id: "o1", status: 2, statusName: "已报价" },
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect((outputsTabStrip(html).match(/role="tab"/g) ?? []).length).toBe(3);
    expect(html).toMatch(/data-slot="order-status-dot" title="待支付" aria-hidden="true" class="[^"]*bg-primary/);
  });

  it("无未终结订单：订单 tab 不自动挂载、无状态点", () => {
    seed.detail = detail({
      prdProducedAt: "2026-08-31T08:00:00Z",
      generatedAt: "2026-08-31T09:00:00Z",
    });

    const html = renderToStaticMarkup(<ProjectPageView projectId="p1" />);

    expect((outputsTabStrip(html).match(/role="tab"/g) ?? []).length).toBe(2);
    expect(html).not.toContain('data-slot="order-status-dot"');
  });
});
