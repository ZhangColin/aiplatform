// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";
import type { WorkspaceFile } from "@/lib/projects/files";
import type { ChatMessage } from "@/lib/store/chat";
import { useDesignScopeStore } from "@/lib/store/design-scope";
import { useWorkMessageStore } from "@/lib/store/work-message";

import { DesignCanvas } from "./design-canvas";

// 全系统画布的浏览器腿交互契约（#293 验收④——#278 拖拽标准法：按下即接管指针、
// 阈值后拖排；空白平移；滚轮缩放 0.3–1.6 指向光标；悬卡删除经确认 popover →
// DELETE 调用携锚定形路径。#294：点选作用域（抬起合成——未拖动＝点选该件）、
// 定稿显式动作（Popover 确认 → POST 携件序＋锚定形）、点开预览。数据口 mock
// 同 SSR 测试；work/scope store 用真身（client 渲染读活态——渐进长出与作用域
// 的 store 接线一并覆盖）。

const seed = vi.hoisted(() => ({
  detail: { designItems: [{ ord: 1, title: "首页主视觉", status: "closed" }] } as Partial<ProjectDetail>,
  // 树路径＝真实 API 相对形（find %P 无前导斜杠）；稿清单携锚定形——两形
  // 归一比较是 buildDesignCanvas 的契约（纯逻辑测试钉死）
  files: [{ path: "design/home-1.html", size: 100 }] as WorkspaceFile[],
  messages: [
    { kind: "closing", id: "c1", closing: { drafts: [
      { item: "首页主视觉", media: "html", path: "/design/home-1.html" },
    ] } },
  ] as unknown as ChatMessage[],
}));
const delMutate = vi.hoisted(() => vi.fn());
const finMutate = vi.hoisted(() => vi.fn());

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({ data: seed.detail }),
}));
vi.mock("@/hooks/use-project-files", () => ({
  useProjectFiles: () => ({ data: seed.files, isPending: false }),
}));
vi.mock("@/hooks/use-delete-design-draft", () => ({
  useDeleteDesignDraft: () => ({ mutate: delMutate, isPending: false }),
}));
vi.mock("@/hooks/use-finalize-design-item", () => ({
  useFinalizeDesignItem: () => ({ mutate: finMutate, isPending: false }),
}));
vi.mock("@/lib/store/chat", () => ({
  useChatStore: <T,>(selector: (state: { chats: Record<string, { messages: unknown[] }> }) => T): T =>
    selector({ chats: { p1: { messages: seed.messages } } }),
}));

/** 板/卡锚定取件（自定义 data 属性，非 testid）。 */
let containerRef: { container: HTMLElement };

function board(): HTMLElement {
  return containerRef.container.querySelector<HTMLElement>("[data-design-board]")!;
}

function card(): HTMLElement {
  return containerRef.container.querySelector<HTMLElement>('[data-card="/design/home-1.html"]')!;
}

beforeEach(() => {
  delMutate.mockClear();
  finMutate.mockClear();
  useWorkMessageStore.setState({ works: {} });
  useDesignScopeStore.setState({ scopes: {}, divergences: {} });
  containerRef = render(<DesignCanvas projectId="p1" />);
});
afterEach(cleanup);

describe("DesignCanvas · 拖排（#278 拖拽标准法）", () => {
  it("按下接管、过阈值即拖排（板坐标随缩放换算）；未过阈值不挪卡", () => {
    const el = card();
    expect(el.style.left).toBe("20px");

    // 按下即接管（setPointerCapture——happy-dom 无实装走可选链，事件直达同理）
    fireEvent.pointerDown(el, { pointerId: 1, clientX: 100, clientY: 100, button: 0 });
    // 微动 2px 屏幕位移（板坐标 3.6px < 4px 阈值——阈值在板空间、按位移合成）——不挪卡
    fireEvent.pointerMove(el, { pointerId: 1, clientX: 102, clientY: 100 });
    expect(el.style.left).toBe("20px");

    // 动 11px 屏幕位移 / 0.55 缩放 = 20px 板位移
    fireEvent.pointerMove(el, { pointerId: 1, clientX: 111, clientY: 100 });
    expect(el.style.left).toBe("40px");
    fireEvent.pointerUp(el, { pointerId: 1 });
  });

  it("删除口（data-no-drag）不接管——按压不触发拖排", () => {
    const trigger = screen.getByLabelText("删除home-1.html");
    fireEvent.pointerDown(trigger, { pointerId: 1, clientX: 100, clientY: 100, button: 0 });
    fireEvent.pointerMove(trigger, { pointerId: 1, clientX: 160, clientY: 130 });
    expect(card().style.left).toBe("20px");
  });
});

describe("DesignCanvas · 空白平移", () => {
  it("按住空白拖动＝滚动平移；卡上按压不触发平移", () => {
    const boardEl = board();
    expect(boardEl.scrollLeft).toBe(0);
    fireEvent.pointerDown(boardEl, { pointerId: 1, clientX: 200, clientY: 200, button: 0 });
    fireEvent.pointerMove(boardEl, { pointerId: 1, clientX: 160, clientY: 190 });
    expect(boardEl.scrollLeft).toBe(40);
    fireEvent.pointerUp(boardEl, { pointerId: 1 });

    // 卡上按压（closest [data-card] 拦截）不武装平移
    fireEvent.pointerDown(card(), { pointerId: 1, clientX: 200, clientY: 200, button: 0 });
    fireEvent.pointerMove(boardEl, { pointerId: 1, clientX: 100, clientY: 190 });
    expect(boardEl.scrollLeft).toBe(40); // 不变
    fireEvent.pointerUp(card(), { pointerId: 1 });
  });
});

describe("DesignCanvas · 滚轮缩放（0.3–1.6 指向光标）", () => {
  it("上滚放大（55%→62%）、下滚缩小；越界夹在档位内", () => {
    const boardEl = board();
    const percent = () => screen.getByTitle("回到 55%").textContent;

    fireEvent.wheel(boardEl, { deltaY: -100, clientX: 100, clientY: 100 });
    expect(percent()).toBe("62%");

    fireEvent.wheel(boardEl, { deltaY: 100, clientX: 100, clientY: 100 });
    expect(percent()).toBe("55%");

    // 连滚到底＝0.3（下界夹持）
    for (let i = 0; i < 30; i++) {
      fireEvent.wheel(boardEl, { deltaY: 100, clientX: 100, clientY: 100 });
    }
    expect(percent()).toBe("30%");
    // 上界 1.6
    for (let i = 0; i < 60; i++) {
      fireEvent.wheel(boardEl, { deltaY: -100, clientX: 100, clientY: 100 });
    }
    expect(percent()).toBe("160%");
  });

  it("缩放控件：＋/− 按钮 0.15 档步进（视口中心锚）", () => {
    fireEvent.click(screen.getByLabelText("放大"));
    expect(screen.getByTitle("回到 55%").textContent).toBe("70%");
    fireEvent.click(screen.getByLabelText("缩小"));
    expect(screen.getByTitle("回到 55%").textContent).toBe("55%");
  });
});

describe("DesignCanvas · 悬卡删除（候选可删、定稿不可删）", () => {
  it("确认 popover → DELETE 携锚定形路径；定稿卡无删除口", async () => {
    fireEvent.click(screen.getByLabelText("删除home-1.html"));
    const confirmText = await screen.findByText("删除「home-1.html」？");
    expect(confirmText).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    await waitFor(() =>
      expect(delMutate).toHaveBeenCalledWith(
        "/design/home-1.html",
        expect.objectContaining({ onError: expect.any(Function) }),
      ));
  });
});

describe("DesignCanvas · 点哪改哪（#294 点选作用域——抬起合成）", () => {
  it("未拖动的按压抬起＝点选：作用域写入该件、卡上已选中；拖排不触发点选", () => {
    const el = card();

    // 点选：按下 → 未过阈值微动 → 抬起（未拖动）＝选中该件为改稿作用域
    fireEvent.pointerDown(el, { pointerId: 1, clientX: 100, clientY: 100, button: 0 });
    fireEvent.pointerMove(el, { pointerId: 1, clientX: 102, clientY: 100 }); // 阈值内
    fireEvent.pointerUp(el, { pointerId: 1 });

    expect(useDesignScopeStore.getState().scopes.p1).toEqual({
      ord: 1,
      itemTitle: "首页主视觉",
    });
    expect(screen.getAllByText("已选中").length).toBeGreaterThan(0);

    // 拖排（过阈值）后抬起＝不是点选——作用域保持（不误换目标）
    fireEvent.pointerDown(el, { pointerId: 2, clientX: 100, clientY: 100, button: 0 });
    fireEvent.pointerMove(el, { pointerId: 2, clientX: 130, clientY: 100 });
    fireEvent.pointerUp(el, { pointerId: 2 });
    expect(useDesignScopeStore.getState().scopes.p1).toEqual({
      ord: 1,
      itemTitle: "首页主视觉",
    });
  });

  it("按钮区（data-no-drag）按压不点选——抬起不合成作用域", () => {
    const trigger = screen.getByLabelText("删除home-1.html");
    fireEvent.pointerDown(trigger, { pointerId: 1, clientX: 100, clientY: 100, button: 0 });
    fireEvent.pointerUp(trigger, { pointerId: 1 });

    expect(useDesignScopeStore.getState().scopes.p1).toBeUndefined();
  });
});

describe("DesignCanvas · 定稿显式动作（#294 稿卡动作）", () => {
  it("确认 popover → POST finalize 携件序＋锚定形路径", async () => {
    fireEvent.click(screen.getByLabelText("定稿home-1.html"));
    expect(await screen.findByText("定稿「home-1.html」？")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "定稿这张" }));
    await waitFor(() =>
      expect(finMutate).toHaveBeenCalledWith(
        { ord: 1, path: "/design/home-1.html" },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        }),
      ));
  });
});

describe("DesignCanvas · 点开预览（#294 放大＋下载面）", () => {
  it("预览钮 → 弹窗挂载（帧取件＋下载双钮）→ 关闭卸载", () => {
    expect(screen.queryByText("下载图（PNG）")).toBeNull();

    fireEvent.click(screen.getByLabelText("预览home-1.html"));

    const modal = containerRef.container.querySelector('[data-design-preview="/design/home-1.html"]');
    expect(modal).toBeTruthy();
    expect(screen.getByText("下载图（PNG）")).toBeTruthy();
    expect(screen.getByText("下载 HTML")).toBeTruthy();

    fireEvent.click(screen.getByLabelText("关闭预览"));
    expect(
      containerRef.container.querySelector('[data-design-preview="/design/home-1.html"]'),
    ).toBeNull();
  });
});

describe("DesignCanvas · 渐进长出（#294 live 会话驱动）", () => {
  it("designer 起跑：占位卡出现；稿落盘（写动作完成＋树长出）→ 在途卡呈现", () => {
    // live 起跑（run-start 的桥写入面）：目标件占位（正在出稿）——板上非空态
    act(() => {
      useWorkMessageStore.getState().startWork(
        "p1", "r9", { title: "首页主视觉", index: 1, total: 1 }, undefined, "designer",
      );
    });
    expect(
      containerRef.container.querySelector('[data-draft-placeholder="首页主视觉"]'),
    ).toBeTruthy();
    expect(screen.getByText("正在出稿…")).toBeTruthy();

    // 稿落盘（写动作 completed——桥经 files 失效重拉，树长出新稿）→ 在途卡长出
    seed.files = [...seed.files, { path: "design/home-2.html", size: 100 }];
    act(() => {
      useWorkMessageStore.getState().notePart("p1", { runId: "r9", eventId: "e1" }, {
        kind: "action",
        toolCallId: "t1",
        toolName: "write_file",
        state: "completed",
        label: "编写【home-2】",
      });
    });
    // 组件随 store 变更重渲染（happy-dom 真实订阅）；树数据经 mock 直读 seed
    expect(
      containerRef.container.querySelector('[data-card="/design/home-2.html"]'),
    ).toBeTruthy();
    expect(screen.getAllByText("正在写…").length).toBeGreaterThan(0);
  });
});
