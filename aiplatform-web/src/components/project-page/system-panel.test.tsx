import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { CoderRunStatus } from "@/lib/store/generation";
import { useGenerationStore } from "@/lib/store/generation";
import type { LiveSegment } from "@/lib/store/live";

import { SystemPanel, previewFrameKey } from "./system-panel";

// 系统模式主区域（#45 渐进预览第一片 + #48 修正超限终态恢复出口）：门禁解除——
// run 开始即取预览地址；空态两档——无应用随直播推进步骤提示（自述优先、动作
// 兜底），有应用保留页面 + 「更新中」轻提示一套；跨会话/重试不闪断；超限终态
// 给人工兜底入口——从未生成「重新发起」、修正轮「重新修改」，正常态全无。
// 预览地址读口 mock 掉（每用例摆 url 有无与 error）。
let previewResult: {
  data?: { url: string };
  error?: unknown;
  isPending: boolean;
  isError: boolean;
} = { isPending: false, isError: false };

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: (_projectId: string, active: boolean) =>
    active
      ? previewResult
      : { data: undefined, error: undefined, isPending: false, isError: false },
}));

vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-restart-fix", () => ({
  useRestartFix: () => ({ isPending: false, mutate: vi.fn() }),
}));

// 直播段读口换直摆对象（zustand SSR 快照冻在建店时刻，setState 后渲染读不到
// ——同 previewFrameKey 测试注释的约束；liveSegmentsOf 留真实现走真实推导）
const liveLives: Record<string, { runId: string; segments: LiveSegment[] }> = {};
vi.mock("@/lib/store/live", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/live")>();
  return {
    ...actual,
    useLiveStore: (selector: (state: { lives: typeof liveLives }) => unknown) =>
      selector({ lives: liveLives }),
  };
});

const seg = {
  text: (id: string, text: string): LiveSegment => ({ kind: "text", id, text }),
  action: (id: string, action: string): LiveSegment => ({ kind: "action", id, action }),
};

function renderPanel({
  generatedAt,
  coderStatus,
  epoch = 0,
  liveSegments = [],
  url,
  error,
}: {
  generatedAt?: string | null;
  coderStatus?: CoderRunStatus;
  epoch?: number;
  liveSegments?: LiveSegment[];
  url?: string;
  error?: unknown;
}) {
  useGenerationStore.setState({
    generations: { p1: { coderRunIds: [], coderStatus, previewEpoch: epoch, seenFinishEventIds: [] } },
  });
  if (liveSegments.length) {
    liveLives.p1 = { runId: "run-1", segments: liveSegments };
  } else {
    delete liveLives.p1;
  }
  previewResult = { data: url ? { url } : undefined, error, isPending: false, isError: error != null };
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <SystemPanel
        projectId="p1"
        generatedAt={generatedAt}
        coderStatus={coderStatus}
        onGenerated={() => {}}
      />
    </QueryClientProvider>,
  );
}

describe("SystemPanel · 系统模式主区域（#45 门禁解除 + 空态两档）", () => {
  beforeEach(() => {
    useGenerationStore.setState({ generations: {} });
    for (const key of Object.keys(liveLives)) delete liveLives[key];
    previewResult = { isPending: false, isError: false };
  });

  it("未开始（idle）：空白浏览器窗 + 引导占位，无 iframe，门禁未开", () => {
    const html = renderPanel({});

    expect(html).toContain("你的系统");
    expect(html).toContain("开始做系统后，这里会出现可以操作的你的系统");
    expect(html).not.toContain("<iframe");
    expect(html).not.toContain("正在接通系统");
  });

  // ---------- 第一档：无应用，占位随直播事件推进 ----------

  it("生成中且无应用：初始「正在初始化」，无 iframe、无文件列表", () => {
    const html = renderPanel({ coderStatus: "running" });

    expect(html).toContain("正在初始化");
    expect(html).not.toContain("<iframe");
    // 门禁解除：run 一开始就在接通（地址栏口径），预览机制已启动
    expect(html).toContain("正在接通系统…");
  });

  it("生成中且无应用：直播自述推进占位（最新自述优先于更晚的动作行）", () => {
    const html = renderPanel({
      coderStatus: "running",
      liveSegments: [
        seg.text("t1", "正在创建首页"),
        seg.action("a1", "正在编写【index.html】"),
      ],
    });

    expect(html).toContain("正在创建首页");
    expect(html).not.toContain("正在编写【index.html】");
  });

  it("生成中且无应用：无自述时动作摘要兜底", () => {
    const html = renderPanel({
      coderStatus: "running",
      liveSegments: [seg.action("a1", "正在编写【index.html】")],
    });

    expect(html).toContain("正在编写【index.html】");
  });

  it("重试中且无应用：播「遇到问题，正在重试」话术", () => {
    const html = renderPanel({ coderStatus: "retrying" });

    expect(html).toContain("遇到问题，正在重试");
    expect(html).not.toContain("<iframe");
  });

  it("超限终态且未生成：问题提示 + 重新发起入口（人工兜底）", () => {
    const html = renderPanel({ coderStatus: "error" });

    expect(html).toContain("生成遇到了问题");
    expect(html).toContain("重新发起");
    expect(html).not.toContain("<iframe");
  });

  // ---------- 第二档：应用可访问（探活通过出 URL），页面 + 一套轻提示 ----------

  it("应用可访问且 run 中：真页面 + 统一「更新中」轻提示（生长期同修正期一套）", () => {
    const html = renderPanel({
      coderStatus: "running",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).toContain("正在更新系统");
    expect(html).toContain("完成后自动刷新");
    // 合并为一套：旧修正专用话术不再并存
    expect(html).not.toContain("正在按您的意见修改系统");
  });

  it("应用可访问且重试中：页面不退占位（不闪断），轻提示播重试话术", () => {
    const html = renderPanel({
      coderStatus: "retrying",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain("遇到问题，正在重试");
  });

  it("超限终态且已生成（修正失败、应用探不到）：修正口径 + 重新修改入口（人工兜底）", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "error",
    });

    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("重新修改");
    // 修正轮不给「重新发起」（系统已生成，重做的事是修正不是重做系统）
    expect(html).not.toContain("重新发起");
    expect(html).not.toContain("<iframe");
  });

  it("应用可访问且超限终态：轻提示转失败 + 重新修改入口，页面仍可见", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "error",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("重新修改");
    expect(html).not.toContain("重新发起");
  });

  it("正常态无任何手动触发：run 中 / 收口后既无重新发起也无重新修改", () => {
    // 正常流程全自动（#48：恢复入口只在超限终态出现）
    const updating = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "running",
      url: "http://localhost:42659",
    });
    expect(updating).toContain("正在更新系统");
    expect(updating).not.toContain("重新发起");
    expect(updating).not.toContain("重新修改");

    const finished = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "finished",
      url: "http://localhost:42659",
    });
    expect(finished).not.toContain("重新发起");
    expect(finished).not.toContain("重新修改");
  });

  it("run 收口后：轻提示消失，预览照常", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "finished",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).not.toContain("正在更新系统");
  });

  it("跨会话回来（generatedAt 事实 + 应用在）：直接显示系统现状", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).not.toContain("正在初始化");
  });

  it("已生成但 URL 未到：接通中等待，非故障不打扰", () => {
    const html = renderPanel({ generatedAt: "2026-08-31T08:00:00Z" });

    expect(html).toContain("正在接通系统…");
    expect(html).not.toContain("预览暂时打不开");
    expect(html).not.toContain("<iframe");
  });

  it("已生成但预览真故障（非未就绪）：打不开口径，稍后自动重试", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      error: new Error("network down"),
    });

    expect(html).toContain("预览暂时打不开，稍后会自动重试");
  });

  it("预览重挂 key：run 收口纪元变化即换 key（同 URL 也强制重建 iframe）", () => {
    // epoch → key 的推导是纯函数（SSR 读不到 setState 后的 store 快照，纪元流
    // 归 bridge.test 断言）；同纪元同 key、新收口新 key
    expect(previewFrameKey("http://localhost:42659", 0)).toBe(
      previewFrameKey("http://localhost:42659", 0),
    );
    expect(previewFrameKey("http://localhost:42659", 1)).not.toBe(
      previewFrameKey("http://localhost:42659", 0),
    );
    expect(previewFrameKey("http://localhost:42659", 2)).not.toBe(
      previewFrameKey("http://localhost:42659", 1),
    );
  });
});
