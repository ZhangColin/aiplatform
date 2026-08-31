// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ChatState, ChatMessage } from "@/lib/store/chat";

import { CommandArea } from "./command-area";

/**
 * 指令区发送路由的状态机（#19 验收口径）：有待答问题时 Enter = 当前问题的答复
 * （POST questions/{qid}/answer，可与已勾选合并）；无待答问题时 Enter = 新发言
 * （POST messages）；空输入不触发。SSR 断言不挂事件，此文件是本仓「客户端交互
 * 逐文件 happy-dom」例外（vitest.config 注）。
 */

const seed = vi.hoisted(() => ({ state: { chats: {} } as Pick<ChatState, "chats"> }));
const postMutate = vi.hoisted(() => vi.fn());
const answerMutate = vi.hoisted(() => vi.fn());

vi.mock("@/lib/store/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/chat")>();
  return {
    ...actual,
    useChatStore: <T,>(selector: (state: Pick<ChatState, "chats">) => T): T =>
      selector(seed.state),
  };
});

vi.mock("@/hooks/use-chat", () => ({
  usePostMessage: () => ({ isPending: false, mutate: postMutate }),
  useAnswerQuestion: () => ({ isPending: false, mutate: answerMutate }),
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
    chats: { p1: { messages, baRunIds: [], ingestedRunIds: [], seenEventIds: [], turnActive: false } },
  };
}

function inputOf() {
  return screen.getByPlaceholderText(/回答上面的问题|和需求分析师聊聊/) as HTMLTextAreaElement;
}

beforeEach(() => {
  postMutate.mockClear();
  answerMutate.mockClear();
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
    seedChat([{ kind: "ba", id: "b1", text: "开场" }]);
    render(<CommandArea projectId="p1" />);

    fireEvent.change(inputOf(), { target: { value: "加个会员功能" } });
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: true }); // Shift+Enter = 换行
    expect(postMutate).not.toHaveBeenCalled();

    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });
    expect(postMutate).toHaveBeenCalledWith({ content: "加个会员功能" });
    expect(answerMutate).not.toHaveBeenCalled();

    fireEvent.change(inputOf(), { target: { value: "   " } }); // 空白输入
    fireEvent.keyDown(inputOf(), { key: "Enter", shiftKey: false });
    expect(postMutate).toHaveBeenCalledTimes(1);
  });
});
