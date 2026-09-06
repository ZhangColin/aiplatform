import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { StartSystemButton } from "./start-generation";

// 重新发起（生成失败兜底，#101 生成无门自动发起后「开始做系统」按钮退役）：系统
// 面板失败态的人工兜底入口——run-failed 后重发 POST /generate 再触发（异常态，非
// 常驻门）。正常流不再出现任何「开始做系统」按钮。mutation 面 mock 掉（SSR 断言
// 呈现，不跑点击）。
vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

function withProvider(children: React.ReactElement) {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>,
  );
}

describe("StartSystemButton · 重新发起兜底（#101）", () => {
  it("失败态兜底按钮出「重新发起」（异常态出口，非常驻门）", () => {
    const html = withProvider(
      <StartSystemButton projectId="p1" onGenerated={() => {}} />,
    );

    expect(html).toContain("重新发起");
    expect(html).not.toContain("开始做系统");
  });

  it("按钮文案可换（调用方显式指定 label）", () => {
    const html = withProvider(
      <StartSystemButton projectId="p1" onGenerated={() => {}} label="重新发起" />,
    );

    expect(html).toContain("重新发起");
  });
});
