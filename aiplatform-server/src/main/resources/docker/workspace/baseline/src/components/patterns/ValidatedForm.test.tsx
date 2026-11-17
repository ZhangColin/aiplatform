import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ValidatedForm } from "./ValidatedForm";

describe("表单模式件·校验流程（机制）", () => {
  it("字段按定义渲染：label/placeholder/控件形态（input/textarea/select）", () => {
    render(
      <ValidatedForm
        fields={[
          { name: "title", label: "商品名称", placeholder: "请输入名称" },
          { name: "price", label: "价格", type: "number" },
          { name: "desc", label: "描述", type: "textarea" },
          {
            name: "status",
            label: "状态",
            type: "select",
            options: [
              { value: "on", label: "上架" },
              { value: "off", label: "下架" },
            ],
          },
        ]}
        onSubmit={() => {}}
      />,
    );
    expect(screen.getByLabelText("商品名称")).toBeTruthy();
    expect(screen.getByPlaceholderText("请输入名称")).toBeTruthy();
    expect(screen.getByLabelText("价格")).toBeTruthy();
    expect(screen.getByLabelText("描述").tagName).toBe("TEXTAREA");
    expect(screen.getByLabelText("状态").tagName).toBe("SELECT");
    expect(screen.getByRole("option", { name: "上架" })).toBeTruthy();
  });

  it("必填缺失：逐字段错误文案出现、onSubmit 不被调、焦点落第一个错字段", () => {
    const onSubmit = vi.fn();
    render(
      <ValidatedForm
        fields={[
          { name: "phone", label: "手机号", required: true },
          { name: "name", label: "姓名", required: true },
        ]}
        onSubmit={onSubmit}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getAllByText("此项必填")).toHaveLength(2);
    const phone = screen.getByLabelText("手机号");
    expect(phone.getAttribute("aria-invalid")).toBe("true");
    expect(document.activeElement).toBe(phone);
  });

  it("自定义校验：错误文案透出；修正后错误即清", () => {
    const onSubmit = vi.fn();
    render(
      <ValidatedForm
        fields={[
          {
            name: "email",
            label: "邮箱",
            validate: (v) => (v.includes("@") ? null : "邮箱格式不正确"),
          },
        ]}
        onSubmit={onSubmit}
      />,
    );
    fireEvent.change(screen.getByLabelText("邮箱"), {
      target: { value: "no-at" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    expect(screen.getByText("邮箱格式不正确")).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText("邮箱"), {
      target: { value: "a@b.c" },
    });
    expect(screen.queryByText("邮箱格式不正确")).toBeNull();
  });

  it("合法提交：onSubmit 收到字段值对象（含 defaultValue）", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(
      <ValidatedForm
        fields={[
          { name: "city", label: "城市", defaultValue: "上海" },
          { name: "note", label: "备注" },
        ]}
        onSubmit={onSubmit}
      />,
    );
    fireEvent.change(screen.getByLabelText("备注"), {
      target: { value: "加急" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit.mock.calls[0][0]).toEqual({ city: "上海", note: "加急" });
  });
});

describe("表单模式件·提交态（机制）", () => {
  it("提交中：按钮禁用＋文案切换＋连点不重复提交", async () => {
    let release!: () => void;
    const onSubmit = vi.fn(
      () => new Promise<void>((resolve) => (release = resolve)),
    );
    render(
      <ValidatedForm fields={[{ name: "a", label: "甲" }]} onSubmit={onSubmit} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    const submitting = await screen.findByRole("button", { name: "提交中…" });
    expect(submitting.hasAttribute("disabled")).toBe(true);
    fireEvent.click(submitting);
    fireEvent.click(submitting);
    expect(onSubmit).toHaveBeenCalledTimes(1);
    release();
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "提交" })).toBeTruthy(),
    );
  });

  it("提交成功：成功文案出现（默认＋覆盖形各证）", async () => {
    const { unmount } = render(
      <ValidatedForm
        fields={[{ name: "a", label: "甲" }]}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    await screen.findByText("已提交");
    unmount();

    render(
      <ValidatedForm
        fields={[{ name: "a", label: "甲" }]}
        onSubmit={vi.fn().mockResolvedValue(undefined)}
        submitText="保存"
        successText="保存成功"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await screen.findByText("保存成功");
  });

  it("提交失败：失败文案出现；改动任一字段即撤下失败横幅", async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error("挂了"));
    render(
      <ValidatedForm
        fields={[{ name: "a", label: "甲" }]}
        onSubmit={onSubmit}
        errorText="服务器开小差了"
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "提交" }));
    await screen.findByText("服务器开小差了");
    fireEvent.change(screen.getByLabelText("甲"), {
      target: { value: "再试试" },
    });
    expect(screen.queryByText("服务器开小差了")).toBeNull();
  });

  it("表单原生提交（回车）同走校验与提交链", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const { container } = render(
      <ValidatedForm fields={[{ name: "a", label: "甲" }]} onSubmit={onSubmit} />,
    );
    fireEvent.submit(container.querySelector("form")!);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
  });

  it("description 说明文案与 hint 字段提示渲染", () => {
    render(
      <ValidatedForm
        fields={[{ name: "a", label: "甲", hint: "六位以内" }]}
        onSubmit={() => {}}
        description="带 * 为必填"
      />,
    );
    expect(screen.getByText("带 * 为必填")).toBeTruthy();
    expect(screen.getByText("六位以内")).toBeTruthy();
  });
});
