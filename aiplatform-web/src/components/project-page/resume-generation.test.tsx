import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { ResumeGenerationButton } from "./resume-generation";

// 继续生成（#222 单出口，ADR-0020——「重新发起」连概念带词条删）：生成中断/从未
// 生成的恢复入口，重发 POST /generate 即断点续跑或计划重派；无推倒重来按钮。
// mutation 面 mock 掉（SSR 断言呈现，不跑点击）。
vi.mock("@/hooks/use-resume-generation", () => ({
  useResumeGeneration: () => ({ isPending: false, mutate: vi.fn() }),
}));

function withProvider(children: React.ReactElement) {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>{children}</QueryClientProvider>,
  );
}

describe("ResumeGenerationButton · 继续生成单出口（#222）", () => {
  it("恢复出口出「继续生成」（中断态/未起跑态的兜底，无「重新发起」旧词）", () => {
    const html = withProvider(
      <ResumeGenerationButton projectId="p1" onGenerated={() => {}} />,
    );

    expect(html).toContain("继续生成");
    expect(html).not.toContain("重新发起");
    expect(html).not.toContain("开始做系统");
  });
});
