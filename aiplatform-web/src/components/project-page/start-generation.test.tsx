import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { StartGenerationCard, StartSystemButton } from "./start-generation";

// 开始做系统动作两形态（#22）：对话流内卡片（解释性入口）与紧凑按钮（文件模式
// 操作条 / 失败重发）。eligibility 归装配层，本组件只认 eligible——不 eligible
// 不渲染。mutation 面 mock 掉（SSR 断言呈现，不跑点击）。
vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
}));

function withProvider(children: React.ReactElement) {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>,
  );
}

describe("StartGenerationCard / StartSystemButton · 开始做系统入口（#22）", () => {
  it("eligible：对话流卡片出解释文案 + 动作按钮（纯动作无门的话术）", () => {
    const html = withProvider(
      <StartGenerationCard projectId="p1" eligible={true} onGenerated={() => {}} />,
    );

    expect(html).toContain("需求整理好了，可以开始做系统");
    expect(html).toContain("开始做系统");
    expect(html).toContain("不影响先做，随时可以再提");
  });

  it("不 eligible（未产出 PRD / 已生成 / 生成中）：卡片不渲染", () => {
    const html = withProvider(
      <StartGenerationCard projectId="p1" eligible={false} onGenerated={() => {}} />,
    );

    expect(html).not.toContain("开始做系统");
  });

  it("紧凑形态：按钮文案可换（失败重发「重新发起」）", () => {
    const html = withProvider(
      <StartSystemButton projectId="p1" onGenerated={() => {}} label="重新发起" />,
    );

    expect(html).toContain("重新发起");
  });
});
