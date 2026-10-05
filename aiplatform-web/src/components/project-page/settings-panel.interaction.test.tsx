// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SettingsPanel } from "./settings-panel";
import type { SwitchEndpointTypePayload } from "@/hooks/use-endpoint-type";

/**
 * 设置 tab 终点控件交互契约（#285，ADR-0025）：三档单选不即点即切（确认区出
 * 确认才切）；系统→设计类（PRD 在）受理时选作用域——全部页面或从功能清单勾选
 * （勾选源＝PRD 功能清单条目解析）；下单即冻结（locked 全禁用）；同目标不出
 * 确认；访谈期（无 PRD）不问范围。沿 outputs-area.interaction 先例（happy-dom
 * 逐文件例外；断言用原生属性）。数据口 mock 掉。
 */
const mutate = vi.fn();

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({
    data: {
      content: [
        "# PRD",
        "",
        "## 功能清单",
        "1. 首页：展示产品与入口",
        "2. 订单管理：下单与查看订单",
        "3. 预约表单：留资提交",
      ].join("\n"),
      updatedAt: "2026-10-05T08:00:00Z",
    },
    isPending: false,
  }),
}));

vi.mock("@/hooks/use-endpoint-type", () => ({
  useSwitchEndpointType: () => ({
    mutate: (payload: SwitchEndpointTypePayload, options?: { onSuccess?: () => void }) => {
      mutate(payload);
      options?.onSuccess?.();
    },
    isPending: false,
  }),
}));

function setup(props?: Partial<Parameters<typeof SettingsPanel>[0]>) {
  return render(
    <SettingsPanel
      projectId="p1"
      endpointType={2}
      designScope={null}
      locked={false}
      hasPrd
      {...props}
    />,
  );
}

afterEach(() => {
  cleanup();
  mutate.mockClear();
});

/**
 * Base UI Checkbox 的点选缝：happy-dom 不让未受信 click 事件跑原生激活行为
 * （根 onClick 向 native input 转发的 dispatchEvent click 空转）——native
 * input 的 .click() 方法直达（同浏览器 label 转发语义）。
 */
function toggleFeatureCheckbox(name: RegExp) {
  const root = screen.getByRole("checkbox", { name });
  const native = root.closest("label")?.querySelector("input[type='checkbox']");
  (native as HTMLInputElement).click();
}

describe("SettingsPanel · 终点类型控件", () => {
  it("三档呈现、当前档（系统）选中，不弹确认区", () => {
    setup();

    for (const label of ["做系统", "做设计", "系统＋设计"]) {
      expect(screen.getByRole("radio", { name: new RegExp("^" + label) })).not.toBeNull();
    }
    expect(screen.getByRole("radio", { name: /^做系统/ }).getAttribute("aria-checked")).toBe("true");
    expect(screen.queryByRole("button", { name: "确认切换" })).toBeNull();
  });

  it("系统→做设计（PRD 在）：选作用域→从功能清单勾选→确认携勾选页", () => {
    setup();

    fireEvent.click(screen.getByRole("radio", { name: /^做设计/ }));
    expect(screen.getByRole("button", { name: "确认切换" })).not.toBeNull();

    fireEvent.click(screen.getByRole("radio", { name: /从功能清单勾选/ }));
    for (const item of [/首页/, /预约表单/]) {
      toggleFeatureCheckbox(item);
    }
    fireEvent.click(screen.getByRole("button", { name: "确认切换" }));

    expect(mutate).toHaveBeenCalledWith({
      endpointType: 1,
      scopeType: 2,
      scopePages: ["首页：展示产品与入口", "预约表单：留资提交"],
    });
  });

  it("全部页面为默认作用域：确认即 scopeType=1 无页清单", () => {
    setup();

    fireEvent.click(screen.getByRole("radio", { name: /^做设计/ }));
    fireEvent.click(screen.getByRole("button", { name: "确认切换" }));

    expect(mutate).toHaveBeenCalledWith({ endpointType: 1, scopeType: 1 });
  });

  it("勾选形未勾页时确认禁用；取消回到无确认区", () => {
    setup();

    fireEvent.click(screen.getByRole("radio", { name: /^系统＋设计/ }));
    fireEvent.click(screen.getByRole("radio", { name: /从功能清单勾选/ }));
    expect(screen.getByRole("checkbox", { name: /首页/ }).getAttribute("aria-checked")).toBe("false");
    expect((screen.getByRole("button", { name: "确认切换" }) as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByRole("button", { name: "确认切换" })).toBeNull();
    expect(mutate).not.toHaveBeenCalled();
  });

  it("访谈期（无 PRD）系统→设计：不问作用域，载荷不带 scope", () => {
    setup({ hasPrd: false });

    fireEvent.click(screen.getByRole("radio", { name: /^做设计/ }));
    expect(screen.queryByRole("radio", { name: /从功能清单勾选/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "确认切换" }));

    expect(mutate).toHaveBeenCalledWith({ endpointType: 1 });
  });

  it("设计主线项目（当前做设计）切换不问作用域（范围由设计物清单承载）", () => {
    setup({ endpointType: 1 });

    fireEvent.click(screen.getByRole("radio", { name: /^做系统/ }));
    expect(screen.queryByRole("radio", { name: /从功能清单勾选/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "确认切换" }));

    expect(mutate).toHaveBeenCalledWith({ endpointType: 2 });
  });

  it("下单即冻结：locked 全控件禁用、点不出确认区", () => {
    setup({ locked: true });

    for (const label of [/^做系统/, /^做设计/, /^系统＋设计/]) {
      expect(screen.getByRole("radio", { name: label }).getAttribute("aria-disabled")).toBe("true");
    }
    fireEvent.click(screen.getByRole("radio", { name: /^做设计/ }));
    expect(screen.queryByRole("button", { name: "确认切换" })).toBeNull();
  });

  it("系统＋设计项目透出已存设计范围（全部页面）", () => {
    setup({ endpointType: 3, designScope: { type: 1, typeName: "全部页面", pages: null } });

    expect(screen.getByText(/设计范围：全部页面/)).not.toBeNull();
  });
});
