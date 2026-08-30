import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { StageRejectionBanner } from "./rejection-banner";

// 驳回理由横幅（issue #19 / #43）：消费 project-notices store 的 rejection 载荷
// （桥唯一写入方），页内呈现段名 + 意见。store 写入与桥接线分别在
// project-notices.test / bridge.test 覆盖；此处断言横幅「消费载荷 → 渲染」与
// 「无载荷 → 不渲染」。renderToStaticMarkup 下 zustand 读初始态（空），故以 mock
// 驱动载荷返回，只测横幅渲染契约（node 环境，不引 DOM）。
const { mockUseNotices } = vi.hoisted(() => ({
  mockUseNotices: vi.fn<(selector: (s: unknown) => unknown) => unknown>(),
}));

vi.mock("@/lib/store/project-notices", () => ({
  useProjectNoticesStore: mockUseNotices,
}));

type Notice = { stageLabel: string; reason: string };

function setNotice(notice: Notice | undefined) {
  mockUseNotices.mockImplementation((selector) =>
    selector({
      notices: { p1: notice ? { rejection: notice } : {} },
      order: notice ? ["p1"] : [],
      clearRejection: () => {},
    }),
  );
}

describe("StageRejectionBanner", () => {
  it("无驳回通知不渲染", () => {
    setNotice(undefined);
    expect(renderToStaticMarkup(<StageRejectionBanner projectId="p1" />)).toBe("");
  });

  it("有驳回通知渲染段名 + 意见", () => {
    setNotice({ stageLabel: "测试", reason: "首页配色要改" });
    const html = renderToStaticMarkup(<StageRejectionBanner projectId="p1" />);
    expect(html).toContain("测试");
    expect(html).toContain("确认未通过");
    expect(html).toContain("首页配色要改");
  });
});
