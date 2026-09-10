// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { Composer } from "./composer";
import type { AnnotationItem } from "@/lib/store/annotation";

/**
 * 共享发送框（#76 首页/项目页同一组件）的交互契约：受控输入 + Enter 提交
 * （Shift+Enter 换行、输入法组词不触发）+ 附件 chip 增删 + 类型下拉（v1 仅
 * 「做系统」可选）+ 圆形发送键启停 + 圈注 chip 形态（#135 序号 + 类型 + 摘要、
 * 撤评语输入框）。沿 command-area.interaction 先例（happy-dom 逐文件例外）；
 * 断言用原生属性（本仓无 jest-dom）。
 */

const submit = vi.fn();
const change = vi.fn();
const removeAnnotation = vi.fn();

function setup({ value = "" }: { value?: string } = {}) {
  render(
    <Composer
      value={value}
      onValueChange={change}
      onSubmit={submit}
      placeholder="一句话说说你想做什么"
    />,
  );
  return screen.getByPlaceholderText("一句话说说你想做什么") as HTMLTextAreaElement;
}

function sendButton(): HTMLButtonElement {
  return screen.getByRole("button", { name: "发送" }) as HTMLButtonElement;
}

beforeEach(() => {
  submit.mockClear();
  change.mockClear();
  removeAnnotation.mockClear();
});
afterEach(() => cleanup());

describe("Composer · 输入与提交", () => {
  it("受控输入：change 冒泡 onValueChange；Enter 提交带走文本与附件", () => {
    const input = setup({ value: "帮我的花店做个能下单的小程序" });

    fireEvent.change(input, { target: { value: "做餐厅点单" } });
    expect(change).toHaveBeenCalledWith("做餐厅点单");

    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
    expect(submit).toHaveBeenCalledWith("帮我的花店做个能下单的小程序", [], []);
  });

  it("空输入 Enter 不触发、Shift+Enter 换行不触发", () => {
    const input = setup();
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
    expect(submit).not.toHaveBeenCalled();

    fireEvent.keyDown(input, { key: "Enter", shiftKey: true });
    expect(submit).not.toHaveBeenCalled();
  });

  it("圆形发送键：空输入禁用", () => {
    setup();
    expect(sendButton().disabled).toBe(true);
  });

  it("发送键有值可点：点击 = onSubmit", () => {
    setup({ value: "做瑜伽馆预约页" });
    expect(sendButton().disabled).toBe(false);
    fireEvent.click(sendButton());
    expect(submit).toHaveBeenCalledWith("做瑜伽馆预约页", [], []);
  });

  it("submitPending 期间发送键禁用（防重复提交）", () => {
    render(
      <Composer
        value="做瑜伽馆预约页"
        onValueChange={change}
        onSubmit={submit}
        submitPending
        placeholder="一句话说说你想做什么"
      />,
    );
    expect(sendButton().disabled).toBe(true);
  });
});

describe("Composer · 附件 chip 行与物料区", () => {
  it("物料区传文件 → 附件 chip 出现（名字 + 大小）；提交随行、提交后清空", () => {
    const input = setup({ value: "给花店做小程序" });

    fireEvent.click(screen.getByRole("button", { name: /附件/ }));
    const fileInput = screen.getByLabelText("上传参考物料") as HTMLInputElement;
    fireEvent.change(fileInput, {
      target: { files: [new File(["x".repeat(2048)], "门店照片.png", { type: "image/png" })] },
    });

    // chip 行与打开的物料区各一份（同名两处命中，断言存在即可）
    expect(screen.getAllByText("门店照片.png").length).toBeGreaterThan(0);
    expect(screen.getAllByText("2 KB").length).toBeGreaterThan(0);

    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
    expect(submit).toHaveBeenCalledWith("给花店做小程序", [
      expect.objectContaining({ name: "门店照片.png" }),
    ], []);
    // 附件随消息发出后清空（输入归调用侧受控管理）
    expect(screen.queryByText("门店照片.png")).toBeNull();
  });

  it("chip 行可删：物料区传入后 X 移除对应附件", () => {
    render(
      <Composer value="" onValueChange={change} onSubmit={submit} placeholder="一句话说说你想做什么" />,
    );
    fireEvent.click(screen.getByRole("button", { name: /附件/ }));
    fireEvent.change(screen.getByLabelText("上传参考物料"), {
      target: { files: [new File([new ArrayBuffer(380 * 1024)], "旧价目表.pdf")] },
    });
    // chip 行与打开的物料区各一份（同名两处命中，断言存在即可）
    expect(screen.getAllByText("旧价目表.pdf").length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole("button", { name: "移除旧价目表.pdf" }));
    expect(screen.queryByText("旧价目表.pdf")).toBeNull();
  });

  it("物料区列出已挂附件（含大小）；未挂时给出空态", () => {
    render(
      <Composer value="" onValueChange={change} onSubmit={submit} placeholder="一句话说说你想做什么" />,
    );
    fireEvent.click(screen.getByRole("button", { name: /附件/ }));
    expect(screen.getByText("还没有物料")).not.toBeNull();

    fireEvent.change(screen.getByLabelText("上传参考物料"), {
      target: { files: [new File([new ArrayBuffer(380 * 1024)], "旧价目表.pdf")] },
    });
    expect(screen.getByText("380 KB")).not.toBeNull();
  });
});

describe("Composer · 类型下拉", () => {
  it("默认「做系统」；做页面/写文档为占位（disabled 敬请期待）", () => {
    setup();
    fireEvent.click(screen.getByRole("button", { name: /做系统/ }));
    expect(screen.getByRole("menuitem", { name: /做页面/ }).getAttribute("aria-disabled")).toBe(
      "true",
    );
    expect(screen.getByRole("menuitem", { name: /写文档/ }).getAttribute("aria-disabled")).toBe(
      "true",
    );
  });
});

describe("Composer · 圈注 chip 行（#135 序号化）", () => {
  const annotations: AnnotationItem[] = [
    { id: "an1", kind: "select", anchor: { selector: "button.submit", text: "提交订单" }, note: "" },
    {
      id: "an2",
      kind: "circle",
      anchor: { region: { x: 100, y: 200, width: 300, height: 80 } },
      note: "",
    },
  ];

  function setupWithAnnotations() {
    render(
      <Composer
        value="第 1 条改颜色"
        onValueChange={change}
        onSubmit={submit}
        annotations={annotations}
        onAnnotationRemove={removeAnnotation}
        placeholder="一句话说说你想做什么"
      />,
    );
  }

  it("chip 形态 = 序号 + 类型 + 摘要（多条靠序号指代），无评语输入框", () => {
    setupWithAnnotations();

    expect(screen.getByText("1·选择")).toBeTruthy();
    expect(screen.getByText("提交订单")).toBeTruthy();
    expect(screen.getByText("2·圈选")).toBeTruthy();
    expect(screen.getByText("区域 (100, 200) 300×80")).toBeTruthy();
    // 评语输入框已撤（描述统一写主输入框）
    expect(screen.queryByLabelText("圈注评语")).toBeNull();
    expect(screen.queryByPlaceholderText("评语（可选）")).toBeNull();
  });

  it("chip 可删：X 移除对应圈注（指认错了能修正）", () => {
    setupWithAnnotations();

    fireEvent.click(screen.getAllByRole("button", { name: "移除圈注" })[1]);

    expect(removeAnnotation).toHaveBeenCalledWith("an2");
  });

  it("圈注随下一句发送：onSubmit 第三参带走条目", () => {
    setupWithAnnotations();
    const input = screen.getByPlaceholderText("一句话说说你想做什么");

    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });

    expect(submit).toHaveBeenCalledWith("第 1 条改颜色", [], annotations);
  });
});
