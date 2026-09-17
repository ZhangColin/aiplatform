// @vitest-environment happy-dom
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { OutputsArea, useOutputsTabs } from "./outputs-area";
import type { ParadigmCtx } from "./paradigms";

/**
 * 成果区 tab 簇交互契约（#79 验收锚）：「+ 新标签页」按范式注册表挂载并激活、
 * tab 可关闭（关激活面回退剩余首个、最后一面不可关）、tab 点选切换激活、
 * 收起键回调。沿 command-area.interaction 先例（happy-dom 逐文件例外）；
 * 断言用原生属性（本仓无 jest-dom）。数据口 mock 掉。
 */
vi.mock("@/hooks/use-project-files", () => ({
  useProjectFiles: () => ({
    data: [{ path: "AGENTS.md", size: 7 }, { path: "docs/PRD.md", size: 12 }],
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({
    data: { content: "# 需求背景\n宠物医院预约系统。", updatedAt: "2026-08-31T08:00:00Z" },
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({ data: undefined, isPending: false, isError: false }),
}));

const CTX: ParadigmCtx = { projectId: "p1", onGenerated: () => {} };
const onClose = vi.fn();

function Area() {
  const tabs = useOutputsTabs();
  return <OutputsArea tabs={tabs} ctx={CTX} onClose={onClose} />;
}

function setup() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <Area />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  cleanup();
  onClose.mockClear();
});

describe("OutputsArea ·「+ 新标签页」按注册表挂载", () => {
  it("菜单列出按需范式；点「文件」即挂载并激活（文件树可见）", () => {
    setup();

    fireEvent.click(screen.getByRole("button", { name: "新标签页" }));
    for (const label of ["文件", "数据", "订单", "终端", "设置"]) {
      expect(screen.getByRole("menuitem", { name: new RegExp(label) })).not.toBeNull();
    }
    // 已挂的「系统」「文档」不重复出现在菜单
    expect(screen.queryByRole("menuitem", { name: /^系统/ })).toBeNull();

    fireEvent.click(screen.getByRole("menuitem", { name: /文件树，点开看内容/ }));
    expect(screen.getAllByRole("tab", { name: "文件" }).length).toBeGreaterThan(0);
    expect(screen.getByText("AGENTS.md")).not.toBeNull(); // 文件范式主体已挂
  });

  it("挂满后菜单给「能挂的都挂上了」空态", () => {
    setup();

    fireEvent.click(screen.getByRole("button", { name: "新标签页" }));
    for (const label of ["文件树", "业务数据", "下单与发布", "运行命令与日志", "项目名、通知"]) {
      fireEvent.click(screen.getByRole("menuitem", { name: new RegExp(label) }));
      fireEvent.click(screen.getByRole("button", { name: "新标签页" }));
    }
    expect(screen.getByText("能挂的都挂上了")).not.toBeNull();
  });
});

describe("OutputsArea · tab 关闭与切换", () => {
  it("关闭激活面：回退剩余首个；关闭非激活面：激活不动", () => {
    setup();

    // 默认 [系统, 文档]，激活 = 系统：关「系统」→ 回退「文档」
    fireEvent.click(screen.getByRole("button", { name: "关闭系统" }));
    expect(screen.queryByRole("tab", { name: "系统" })).toBeNull();
    expect(screen.getByText("宠物医院预约系统。")).not.toBeNull(); // 文档主体挂上

    // 挂「文件」后关非激活面（文档）：激活「文件」不动
    fireEvent.click(screen.getByRole("button", { name: "新标签页" }));
    fireEvent.click(screen.getByRole("menuitem", { name: /文件树，点开看内容/ }));
    fireEvent.click(screen.getByRole("button", { name: "关闭文档" }));
    expect(screen.queryByRole("tab", { name: "文档" })).toBeNull();
    expect(screen.getByText("AGENTS.md")).not.toBeNull();
  });

  it("最后一面不可关（tab 簇至少留一面）", () => {
    setup();

    fireEvent.click(screen.getByRole("button", { name: "关闭文档" }));
    // 仅剩「系统」：关闭键不渲染（唯一面无 X）
    expect(screen.queryByRole("button", { name: "关闭系统" })).toBeNull();
  });

  it("tab 点选切换激活面", () => {
    setup();

    fireEvent.click(screen.getByRole("tab", { name: "文档" }));
    expect(screen.getByText("宠物医院预约系统。")).not.toBeNull();
  });
});

describe("OutputsArea · 收起", () => {
  it("收起键回调 onClose（呼出式的收回侧归装配层）", () => {
    setup();

    fireEvent.click(screen.getByRole("button", { name: "收起成果区" }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe("OutputsArea · attach 挂载不激活（#205 回访订单 tab 自动挂载）", () => {
  /** 附件层先例的最小复刻：按钮触发 attach，模拟装配层回访挂载订单 tab。 */
  function AttachArea() {
    const tabs = useOutputsTabs();
    return (
      <div>
        <button type="button" onClick={() => tabs.attach("order")}>
          挂订单
        </button>
        <OutputsArea tabs={tabs} ctx={CTX} onClose={() => {}} />
      </div>
    );
  }

  it("attach 把 tab 挂上簇但不抢激活面；挂载后照常可关", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AttachArea />
      </QueryClientProvider>,
    );

    // 初始：默认两枚，激活 = 系统
    expect(screen.getByRole("tab", { name: "系统" }).getAttribute("aria-selected")).toBe("true");

    fireEvent.click(screen.getByRole("button", { name: "挂订单" }));

    // 挂上「订单」但不激活：仍是系统一面被选中（不自动切换——主感知面在对话区）
    expect(screen.getAllByRole("tab", { name: "订单" }).length).toBeGreaterThan(0);
    expect(screen.getByRole("tab", { name: "订单" }).getAttribute("aria-selected")).toBe("false");
    expect(screen.getByRole("tab", { name: "系统" }).getAttribute("aria-selected")).toBe("true");

    // 挂载后照常可关（用户手动关闭优先）
    fireEvent.click(screen.getByRole("button", { name: "关闭订单" }));
    expect(screen.queryByRole("tab", { name: "订单" })).toBeNull();
  });
});
