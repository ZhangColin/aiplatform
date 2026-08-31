import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { CoderRunStatus } from "@/lib/store/generation";
import { useGenerationStore } from "@/lib/store/generation";

import { SystemPanel, previewFrameKey } from "./system-panel";

// 系统模式主区域（#22 验收口径）：生成待期 = 空白浏览器窗 + 一句提示（无进度
// 剧场）；重试话术；超限终态给重新发起；ready（generatedAt / 本会话收口信号）
// 才挂预览 iframe。预览地址读口 mock 掉。
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: (_projectId: string, enabled: boolean) => ({
    data: enabled ? { url: "http://localhost:42659" } : undefined,
    isPending: false,
    isError: false,
  }),
}));

vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

function renderPanel({
  generatedAt,
  coderStatus,
  epoch = 0,
}: {
  generatedAt?: string | null;
  coderStatus?: CoderRunStatus;
  epoch?: number;
}) {
  useGenerationStore.setState({
    generations: { p1: { coderRunIds: [], coderStatus, previewEpoch: epoch, seenFinishEventIds: [] } },
  });
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

describe("SystemPanel · 系统模式主区域（#22 生成环①）", () => {
  beforeEach(() => {
    useGenerationStore.setState({ generations: {} });
  });

  it("未生成（idle）：空白浏览器窗 + 一句提示，无 iframe", () => {
    const html = renderPanel({});

    expect(html).toContain("你的系统");
    expect(html).toContain("开始做系统后，这里会出现可以操作的你的系统");
    expect(html).not.toContain("<iframe");
  });

  it("生成中（running）：一句提示「正在为您生成系统…」（无进度剧场）", () => {
    const html = renderPanel({ coderStatus: "running" });

    expect(html).toContain("正在为您生成系统");
    expect(html).not.toContain("<iframe");
  });

  it("重试中（retrying）：播「遇到问题，正在重试」话术", () => {
    const html = renderPanel({ coderStatus: "retrying" });

    expect(html).toContain("遇到问题，正在重试");
    expect(html).not.toContain("<iframe");
  });

  it("超限终态（error 且未生成）：问题提示 + 重新发起入口（人工兜底）", () => {
    const html = renderPanel({ coderStatus: "error" });

    expect(html).toContain("生成遇到了问题");
    expect(html).toContain("重新发起");
    expect(html).not.toContain("<iframe");
  });

  it("ready（generatedAt 事实）：挂预览 iframe（地址栏出 URL）", () => {
    const html = renderPanel({ generatedAt: "2026-08-31T08:00:00Z" });

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).toContain("http://localhost:42659");
  });

  // ---------- 修正期间（#26 迭代环①：系统保持可见 + 轻提示） ----------

  it("修正中（ready 后编码 run 再起）：预览保持可见 + 顶部轻提示，不闪断", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "running",
    });

    expect(html).toContain("<iframe"); // 系统保持可见（不是空白浏览器窗）
    expect(html).toContain("正在按您的意见修改系统");
    expect(html).toContain("完成后自动刷新");
  });

  it("修正重试中：轻提示播帧内话术（「遇到问题，正在重试」），预览仍可见", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "retrying",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain("遇到问题，正在重试");
    expect(html).not.toContain("正在按您的意见修改系统");
  });

  it("修正完成（run 收口）：轻提示消失，预览照常", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "finished",
    });

    expect(html).toContain("<iframe");
    expect(html).not.toContain("正在按您的意见修改系统");
  });

  it("修正超限终态（error 且已 ready）：轻提示转失败 + 再提意见重试口径，预览仍可见", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "error",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("再提一次意见重试");
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
