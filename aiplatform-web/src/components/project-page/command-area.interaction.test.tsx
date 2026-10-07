// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useDesignScopeStore } from "@/lib/store/design-scope";
import { usePrdNoticesStore } from "@/lib/store/prd-notices";
import { useWorkMessageStore } from "@/lib/store/work-message";
import type { ChatState, ChatMessage } from "@/lib/store/chat";

import { CommandArea } from "./command-area";

/**
 * 对话区发送路由的状态机（#19 验收口径）：有待答问题时 Enter = 当前问题的答复
 * （POST questions/{qid}/answer，可与已勾选合并）；无待答问题时 Enter = 新发言
 * （POST messages）；空输入不触发。SSR 断言不挂事件，此文件是本仓「客户端交互
 * 逐文件 happy-dom」例外（vitest.config 注）。#20 增：修订胶囊点击 = 认领
 * （prd-notices 真实 store）+ 跳转回调。
 */

const seed = vi.hoisted(() => ({ state: { chats: {} } as Pick<ChatState, "chats"> }));
const postMutate = vi.hoisted(() => vi.fn());
const answerMutate = vi.hoisted(() => vi.fn());
const seePrd = vi.hoisted(() => vi.fn());
const uploadMaterial = vi.hoisted(() => vi.fn());

vi.mock("@/lib/store/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/chat")>();
  return {
    ...actual,
    useChatStore: <T,>(selector: (state: Pick<ChatState, "chats">) => T): T =>
      selector(seed.state),
  };
});

vi.mock("@/hooks/use-conversation", () => ({
  useConversation: () => ({}),
}));

vi.mock("@/hooks/use-upload-material", () => ({
  useUploadMaterial: () => uploadMaterial,
}));
vi.mock("@/hooks/use-chat", () => ({
  usePostMessage: () => ({ isPending: false, mutate: postMutate }),
  useAnswerQuestion: () => ({ isPending: false, mutate: answerMutate }),
}));

// 收尾卡版本动作请求面（仅「查看当时」链路用例消费）：起快照直读就绪态
// （data 即回 previewUrl），请求面归 use-version 与后端测试
vi.mock("@/hooks/use-version", () => ({
  useStartVersionView: () => ({
    isPending: false,
    isError: false,
    data: { viewId: "v1", previewUrl: "about:blank" },
    mutate: vi.fn(),
    reset: vi.fn(),
  }),
  useStopVersionView: () => ({ mutate: vi.fn(), isPending: false }),
  useRollbackVersion: () => ({ mutate: vi.fn(), isPending: false }),
}));

function pendingQuestion(overrides: Partial<Extract<ChatMessage, { kind: "question" }>> = {}) {
  return {
    kind: "question",
    id: "q1",
    runId: "run-1",
    engineRef: "reply-1",
    header: "核心功能",
    question: "先做哪些能力?",
    multiple: true,
    options: ["预约", "提醒", "会员"],
    toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
    answered: false,
    ...overrides,
  } satisfies Extract<ChatMessage, { kind: "question" }>;
}

function seedChat(messages: ChatMessage[]) {
  seed.state = {
    chats: {
      p1: { messages, chatRunIds: [], ingestedRunIds: [], seenEventIds: [], turnActive: false },
    },
  };
}

function inputOf() {
  return screen.getByPlaceholderText(/回答上面的问题|和平台聊聊/) as HTMLTextAreaElement;
}

beforeEach(() => {
  postMutate.mockClear();
  answerMutate.mockClear();
  uploadMaterial.mockReset();
});

// vitest 未开 globals：RTL 的自动 cleanup 不挂，手动清（否则 DOM 跨用例累积）
afterEach(() => cleanup());

describe("CommandArea · Enter 发送路由（#19 状态机）", () => {
  it("有待答问题：Enter 走作答端点（qid = engineRef、runId + toolCalls 回传）", () => {
    seedChat([pendingQuestion()]);
    render(<CommandArea projectId="p1" />);

    fireEvent.change(inputOf(), { target: { value: "先做预约和提醒" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });

    expect(answerMutate).toHaveBeenCalledTimes(1);
    expect(answerMutate.mock.calls[0][0]).toMatchObject({
      qid: "reply-1",
      command: { runId: "run-1", answer: "先做预约和提醒" },
    });
    expect(answerMutate.mock.calls[0][0].command.toolCalls).toEqual([
      { id: "tc-1", name: "ask_user", input: {} },
    ]);
    expect(postMutate).not.toHaveBeenCalled();
  });

  it("已勾选 + 自由输入：Enter 合并作答（勾选在前、输入在后）", () => {
    seedChat([pendingQuestion()]);
    render(<CommandArea projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: "预约" }));
    fireEvent.click(screen.getByRole("button", { name: "会员" }));
    fireEvent.change(inputOf(), { target: { value: "还想加个提醒" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });

    expect(answerMutate.mock.calls[0][0].command.answer).toBe("预约；会员；还想加个提醒");
  });

  it("无待答问题：Enter 走发言端点；Shift+Enter 不提交；空输入不触发", () => {
    seedChat([{ kind: "agent", id: "b1", text: "开场", }]);
    render(<CommandArea projectId="p1" />);

    fireEvent.change(inputOf(), { target: { value: "加个会员功能" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: true }); // Shift+Enter = 换行
    expect(postMutate).not.toHaveBeenCalled();

    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });
    expect(postMutate).toHaveBeenCalledWith({ content: "加个会员功能", attachments: [] });
    expect(answerMutate).not.toHaveBeenCalled();

    fireEvent.change(inputOf(), { target: { value: "   " } }); // 空白输入
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });
    expect(postMutate).toHaveBeenCalledTimes(1);
  });
});

describe("CommandArea · 修订胶囊（#20 修订回路）", () => {
  beforeEach(() => {
    usePrdNoticesStore.setState({ seen: {}, pending: {} });
    seed.state = { chats: {} };
    seePrd.mockClear();
  });

  it("点击「去看看」：认领（pending 清）+ 跳转回调，胶囊即逝", () => {
    usePrdNoticesStore.setState({ seen: { p1: true }, pending: { p1: true } });
    render(<CommandArea projectId="p1" onSeePrd={seePrd} />);

    fireEvent.click(screen.getByRole("button", { name: /PRD 有更新 · 去看看/ }));

    expect(usePrdNoticesStore.getState().pending.p1).toBeUndefined();
    expect(seePrd).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: /PRD 有更新/ })).toBeNull();
  });
});

describe("CommandArea · 查看当时标题轮次语境（#142 整链：对话流 → 收尾卡序数/摘要 → 弹窗标题）", () => {
  it("收尾卡点「查看当时」：标题带轮次序数与收尾摘要（第 N 个收尾卡 = 第 N 轮）", () => {
    seedChat([
      { kind: "user", id: "u1", text: "把主色调改成绿色", runId: "run-1" },
      {
        kind: "closing",
        id: "c1",
        runId: "run-1",
        closing: {
          summary: "主色调已改为绿色",
          prdChanged: false,
          systemChanged: true,
          files: [],
          durationMs: 5000,
          version: "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1",
        },
      },
    ]);
    render(<CommandArea projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: "查看当时" }));

    expect(screen.getByText("第 1 轮结束时的系统——主色调已改为绿色")).toBeTruthy();
  });
});

describe("CommandArea · 图片物料真上传（#286 回形针接线）", () => {
  it("选文件即上传：完成态随话发出（载荷＝路径引用），Enter 发言 attachments 携 image 形态", async () => {
    seedChat([{ kind: "agent", id: "b1", text: "开场" }]);
    uploadMaterial.mockResolvedValue({
      path: "materials/3897654321098765432-logo.png",
      name: "logo.png",
      size: 2048,
    });
    render(<CommandArea projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: /附件/ }));
    fireEvent.change(screen.getByLabelText("上传参考物料"), {
      target: { files: [new File([new ArrayBuffer(2048)], "logo.png", { type: "image/png" })] },
    });
    expect(uploadMaterial).toHaveBeenCalledOnce();

    await vi.waitFor(() => expect(screen.queryByText("上传中…")).toBeNull());
    fireEvent.change(inputOf(), { target: { value: "照这张做 logo" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });

    expect(postMutate).toHaveBeenCalledWith({
      content: "照这张做 logo",
      attachments: [
        {
          attachmentType: "image",
          name: "logo.png",
          path: "materials/3897654321098765432-logo.png",
        },
      ],
    });
  });

  it("待答问题时物料随答复文本送达（作答通道无附件位，渲染进文本）", async () => {
    seedChat([pendingQuestion()]);
    uploadMaterial.mockResolvedValue({
      path: "materials/123-参考.png",
      name: "参考.png",
      size: 8,
    });
    render(<CommandArea projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: /附件/ }));
    fireEvent.change(screen.getByLabelText("上传参考物料"), {
      target: { files: [new File([new ArrayBuffer(8)], "参考.png", { type: "image/png" })] },
    });
    await vi.waitFor(() => expect(screen.queryByText("上传中…")).toBeNull());
    fireEvent.change(inputOf(), { target: { value: "按这张来" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });

    expect(answerMutate.mock.calls[0][0].command.answer).toBe(
      "按这张来\n【图片物料】1. 参考.png（materials/123-参考.png）",
    );
  });

  it("对话史回访：物料 chip 随用户气泡呈现（缩略图走 raw 直出直链、点开看大图）", () => {
    seedChat([
      {
        kind: "user",
        id: "u1",
        text: "照这张做",
        materials: [{ name: "logo.png", path: "materials/3897654321098765432-logo.png" }],
      },
    ]);
    const { container } = render(<CommandArea projectId="p1" />);

    const chip = container.querySelector(
      'a[data-material-chip="materials/3897654321098765432-logo.png"]',
    );
    expect(chip?.getAttribute("href")).toBe(
      "/api/projects/p1/files/raw?path=materials%2F3897654321098765432-logo.png",
    );
    expect(chip?.textContent).toContain("logo.png");
  });
});

describe("CommandArea · designer 直播卡计划区选送（#290 装配 seam：座席分岔选清单）", () => {
  beforeEach(() => {
    seed.state = { chats: {} };
    useWorkMessageStore.setState({ works: {} });
  });

  it("designer 座席在途：计划区出设计物清单（不出切片清单）——同构不混淆", () => {
    useWorkMessageStore.setState({
      works: {
        p1: {
          runId: "run-d1",
          frozen: false,
          seat: "designer",
          slice: { title: "logo 主标识", index: 2, total: 3 },
          parts: [
            {
              kind: "action",
              id: "run-d1:2",
              toolCallId: "tc-1",
              toolName: "write_file",
              state: "running",
              label: "编写【logo-1】",
            },
          ],
          seenEventIds: ["run-d1:2"],
        },
      },
    });
    render(
      <CommandArea
        projectId="p1"
        plan={[{ ord: 1, description: "用户能注册登录", status: "pending" }]}
        designPlan={[
          { ord: 1, description: "首页主视觉", status: "closed" },
          { ord: 2, description: "logo 主标识", status: "pending" },
        ]}
      />,
    );

    expect(screen.getByText("logo 主标识（2/3）")).toBeTruthy(); // 头部＝设计物标题进度
    expect(screen.getByText("正在出第 1 稿")).toBeTruthy(); // 活性行＝出稿动作
    expect(screen.getByText("首页主视觉")).toBeTruthy(); // 计划区＝设计物清单
    expect(screen.queryByText("用户能注册登录")).toBeNull(); // 切片清单不串台
  });

  it("executor 座席在途：计划区照旧切片清单（designPlan 不串台）", () => {
    useWorkMessageStore.setState({
      works: {
        p1: {
          runId: "run-c1",
          frozen: false,
          slice: { title: "用户能注册登录", index: 1, total: 1 },
          parts: [
            {
              kind: "action",
              id: "run-c1:2",
              toolCallId: "tc-1",
              toolName: "write_file",
              state: "running",
              label: "编写【订单管理】",
            },
          ],
          seenEventIds: ["run-c1:2"],
        },
      },
    });
    render(
      <CommandArea
        projectId="p1"
        plan={[{ ord: 1, description: "用户能注册登录", status: "pending" }]}
        designPlan={[{ ord: 1, description: "首页主视觉", status: "pending" }]}
      />,
    );

    expect(screen.getByText("用户能注册登录")).toBeTruthy();
    expect(screen.queryByText("首页主视觉")).toBeNull();
    expect(screen.getByText("编写【订单管理】")).toBeTruthy(); // label 滚动（executor 口径）
  });
});

describe("CommandArea · 设计改稿作用域＋发散度 chip（#294 点哪改哪）", () => {
  beforeEach(() => {
    useDesignScopeStore.setState({ scopes: {}, divergences: {} });
  });

  it("作用域在场：chip 行呈现（就「件」改＋三档）；发言携 designItem＋所选档位", () => {
    seedChat([{ kind: "agent", id: "b1", text: "开场" }]);
    act(() => {
      useDesignScopeStore.getState().pick("p1", { ord: 2, itemTitle: "品牌 logo" });
    });
    render(<CommandArea projectId="p1" />);

    expect(screen.getByText("就「品牌 logo」改")).toBeTruthy();
    for (const label of ["微调", "探索", "大胆"]) {
      expect(screen.getByText(label)).toBeTruthy();
    }

    fireEvent.change(inputOf(), { target: { value: "颜色再亮一点" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });

    // 缺省档＝探索（EXPLORE）；designItem 路由该件设计会话
    expect(postMutate).toHaveBeenCalledWith({
      content: "颜色再亮一点",
      attachments: [],
      designItem: 2,
      divergence: 2,
    });
  });

  it("调档即改：点「大胆」后发言携 REIMAGINE；作用域不清（连续改稿）", () => {
    seedChat([{ kind: "agent", id: "b1", text: "开场" }]);
    act(() => {
      useDesignScopeStore.getState().pick("p1", { ord: 1, itemTitle: "首页主视觉" });
    });
    render(<CommandArea projectId="p1" />);

    fireEvent.click(screen.getByText("大胆"));
    fireEvent.change(inputOf(), { target: { value: "索性换个方向" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });

    expect(postMutate).toHaveBeenCalledWith({
      content: "索性换个方向",
      attachments: [],
      designItem: 1,
      divergence: 3,
    });
    // 发送后作用域仍在（stitch 挑选语义——下一句继续改同一件零重复点选）
    expect(useDesignScopeStore.getState().scopes.p1).toEqual({ ord: 1, itemTitle: "首页主视觉" });
  });

  it("X 退出作用域：chip 行退场，发言回常规三分类（不携 designItem/divergence）", () => {
    seedChat([{ kind: "agent", id: "b1", text: "开场" }]);
    act(() => {
      useDesignScopeStore.getState().pick("p1", { ord: 1, itemTitle: "首页主视觉" });
    });
    render(<CommandArea projectId="p1" />);

    fireEvent.click(screen.getByLabelText("取消作用域"));
    expect(screen.queryByText("就「首页主视觉」改")).toBeNull();

    fireEvent.change(inputOf(), { target: { value: "帮我看下进度" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });
    expect(postMutate).toHaveBeenCalledWith({ content: "帮我看下进度", attachments: [] });
  });
});
