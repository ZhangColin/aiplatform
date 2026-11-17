import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { FilterableList } from "./FilterableList";

interface Order {
  id: number;
  title: string;
  status: string;
  amount: number;
}

const orders: Order[] = Array.from({ length: 12 }, (_, i) => ({
  id: i + 1,
  title: `订单-${String(i + 1).padStart(2, "0")}`,
  status: i % 3 === 0 ? "open" : "done",
  amount: (i + 1) * 10,
}));

const filters = [
  { name: "keyword", label: "搜索", placeholder: "搜订单名" },
  {
    name: "status",
    label: "状态",
    type: "select" as const,
    options: [
      { value: "open", label: "进行中" },
      { value: "done", label: "已完成" },
    ],
  },
];

function row(order: Order) {
  return (
    <div>
      {order.title}·{order.status}
    </div>
  );
}

describe("列表模式件·筛选状态管理（机制）", () => {
  it("条目经 renderItem 渲染（零筛选＝全量）", () => {
    render(
      <FilterableList items={orders} pageSize={20} renderItem={row} />,
    );
    expect(screen.getByText("订单-01·open")).toBeTruthy();
    expect(screen.getByText("订单-12·done")).toBeTruthy();
  });

  it("文本筛选命中全部字符串字段（搜索语义）；matchFields 收窄命中面", () => {
    const { unmount } = render(
      <FilterableList items={orders} filters={filters} pageSize={20} renderItem={row} />,
    );
    fireEvent.change(screen.getByLabelText("搜索"), {
      target: { value: "订单-0" },
    });
    expect(screen.getByText("订单-01·open")).toBeTruthy();
    expect(screen.queryByText("订单-10·done")).toBeNull();
    unmount();

    render(
      <FilterableList
        items={orders}
        filters={[{ name: "keyword", label: "搜索", matchFields: ["status"] }]}
        pageSize={20}
        renderItem={row}
      />,
    );
    fireEvent.change(screen.getByLabelText("搜索"), {
      target: { value: "open" },
    });
    expect(screen.getByText("订单-01·open")).toBeTruthy();
    expect(screen.queryByText("订单-02·done")).toBeNull();
  });

  it("下拉筛选按字段等值命中；「全部」选项清空筛选", () => {
    render(
      <FilterableList items={orders} filters={filters} pageSize={20} renderItem={row} />,
    );
    fireEvent.change(screen.getByLabelText("状态"), {
      target: { value: "open" },
    });
    expect(screen.getByText("订单-01·open")).toBeTruthy();
    expect(screen.queryByText("订单-02·done")).toBeNull();

    fireEvent.change(screen.getByLabelText("状态"), {
      target: { value: "" },
    });
    expect(screen.getByText("订单-02·done")).toBeTruthy();
  });

  it("组合筛选取交集", () => {
    render(
      <FilterableList items={orders} filters={filters} pageSize={20} renderItem={row} />,
    );
    fireEvent.change(screen.getByLabelText("状态"), {
      target: { value: "open" },
    });
    fireEvent.change(screen.getByLabelText("搜索"), {
      target: { value: "订单-04" },
    });
    expect(screen.getByText("订单-04·open")).toBeTruthy();
    expect(screen.queryByText("订单-01·open")).toBeNull();
  });

  it("筛选后零命中：空态文案（默认＋覆盖形各证）", () => {
    const { rerender } = render(
      <FilterableList items={orders} filters={filters} pageSize={20} renderItem={row} />,
    );
    fireEvent.change(screen.getByLabelText("搜索"), {
      target: { value: "不存在的关键词" },
    });
    expect(screen.getByText("暂无数据")).toBeTruthy();

    rerender(
      <FilterableList
        items={orders}
        filters={filters}
        pageSize={20}
        renderItem={row}
        emptyText="没有匹配的订单"
      />,
    );
    fireEvent.change(screen.getByLabelText("搜索"), {
      target: { value: "不存在的关键词" },
    });
    expect(screen.getByText("没有匹配的订单")).toBeTruthy();
  });

  it("onQueryChange 随筛选变更上抛（筛选重置回第 1 页）", () => {
    const onQueryChange = vi.fn();
    render(
      <FilterableList
        items={orders}
        filters={filters}
        pageSize={5}
        renderItem={row}
        onQueryChange={onQueryChange}
      />,
    );
    fireEvent.change(screen.getByLabelText("状态"), {
      target: { value: "open" },
    });
    expect(onQueryChange).toHaveBeenCalledTimes(1);
    expect(onQueryChange.mock.calls[0][0]).toEqual({
      filters: { status: "open" },
      page: 1,
      pageSize: 5,
    });
  });

  it("defaultFilters 生效：初值即参与筛选", () => {
    render(
      <FilterableList
        items={orders}
        filters={filters}
        defaultFilters={{ status: "open" }}
        pageSize={20}
        renderItem={row}
      />,
    );
    expect(screen.getByText("订单-01·open")).toBeTruthy();
    expect(screen.queryByText("订单-02·done")).toBeNull();
  });
});

describe("列表模式件·分页数据流（机制）", () => {
  it("客户端切片：首页 5 条＋页次摘要；点页码翻页换数据", () => {
    render(<FilterableList items={orders} pageSize={5} renderItem={row} />);
    expect(screen.getByText("订单-05·done")).toBeTruthy();
    expect(screen.queryByText("订单-06·done")).toBeNull();
    expect(screen.getByText("共 12 条 · 1 / 3 页")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "第 2 页" }));
    expect(screen.getByText("订单-06·done")).toBeTruthy();
    expect(screen.queryByText("订单-05·done")).toBeNull();
    expect(screen.getByText("共 12 条 · 2 / 3 页")).toBeTruthy();
  });

  it("上一页/下一页边界禁用（首页与末页）", () => {
    render(<FilterableList items={orders} pageSize={5} renderItem={row} />);
    const prev = screen.getByRole("button", { name: "上一页" });
    expect(prev.hasAttribute("disabled")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "第 3 页" }));
    expect(screen.getByRole("button", { name: "下一页" }).hasAttribute("disabled")).toBe(true);
    expect(prev.hasAttribute("disabled")).toBe(false);
  });

  it("当前页码带 aria-current 语义", () => {
    render(<FilterableList items={orders} pageSize={5} renderItem={row} />);
    expect(
      screen.getByRole("button", { name: "第 1 页" }).getAttribute("aria-current"),
    ).toBe("page");
  });

  it("翻页经 onQueryChange 上抛（筛选随行）", () => {
    const onQueryChange = vi.fn();
    render(
      <FilterableList
        items={orders}
        filters={filters}
        pageSize={5}
        renderItem={row}
        onQueryChange={onQueryChange}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "第 2 页" }));
    expect(onQueryChange).toHaveBeenCalledTimes(1);
    expect(onQueryChange.mock.calls[0][0]).toEqual({
      filters: {},
      page: 2,
      pageSize: 5,
    });
  });

  it("数据收缩页码钳制：末页筛选到只剩一页时回落展示内容（不空屏）", () => {
    render(
      <FilterableList
        items={orders}
        filters={filters}
        pageSize={5}
        renderItem={row}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "第 3 页" }));
    expect(screen.getByText("订单-11·done")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("状态"), { target: { value: "open" } });
    expect(screen.getByText("订单-01·open")).toBeTruthy();
    // 只剩一页时分页条整体撤下（单页形态＝纯列表）
    expect(screen.queryByRole("navigation", { name: "分页" })).toBeNull();
  });

  it("单页不渲染分页条", () => {
    render(<FilterableList items={orders.slice(0, 4)} pageSize={10} renderItem={row} />);
    expect(screen.queryByRole("button", { name: "上一页" })).toBeNull();
    expect(screen.queryByText(/共 \d+ 条/)).toBeNull();
  });

  it("服务端形：传 total 即不自切片，分页以 total 计、翻页只上抛", () => {
    const onQueryChange = vi.fn();
    const page3 = orders.slice(0, 5);
    render(
      <FilterableList
        items={page3}
        total={40}
        pageSize={5}
        renderItem={row}
        onQueryChange={onQueryChange}
      />,
    );
    expect(screen.getByText("订单-01·open")).toBeTruthy();
    expect(screen.getByText("共 40 条 · 1 / 8 页")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "第 8 页" }));
    expect(onQueryChange).toHaveBeenCalledTimes(1);
    expect(onQueryChange.mock.calls[0][0].page).toBe(8);
    // 服务端形不本地切片：items 原样呈现（等待调用方回填）
    expect(screen.getByText("订单-01·open")).toBeTruthy();
  });

  it("多页窗口折叠：首末页常驻、远页以省略号呈现", () => {
    render(<FilterableList items={orders} total={40} pageSize={5} renderItem={row} />);
    const nav = screen.getByRole("navigation", { name: "分页" });
    expect(within(nav).getByRole("button", { name: "第 1 页" })).toBeTruthy();
    expect(within(nav).getByRole("button", { name: "第 8 页" })).toBeTruthy();
    expect(within(nav).getByText("…")).toBeTruthy();
  });

  it("children 函数接管内容区（view 携切片后条目与页信息）", () => {
    render(
      <FilterableList items={orders} pageSize={5}>
        {(view) => (
          <table>
            <tbody>
              {view.items.map((o) => (
                <tr key={o.id}>
                  <td>行:{o.title}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </FilterableList>,
    );
    expect(screen.getByText("行:订单-01")).toBeTruthy();
    expect(screen.queryByText("行:订单-06")).toBeNull();
  });
});
