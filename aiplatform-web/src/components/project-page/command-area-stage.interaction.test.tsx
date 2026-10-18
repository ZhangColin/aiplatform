// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ChatState, ProjectChat } from "@/lib/store/chat";
import type { PrdNoticesState } from "@/lib/store/prd-notices";

import { CommandArea, DATA_TEMPLATE_SNIPPET } from "./command-area";

/**
 * CommandArea 阶段页形态交互（#346）：PRD 增量刷新横幅的 X＝认领（pending 清、
 * 横幅即逝）；数据模板钮＝向受控输入插入模板骨架（不直接发送）。mock 搭法同
 * command-area.interaction.test。
 */
const seed = vi.hoisted(() => ({
  chats: { chats: {} } as Pick<ChatState, "chats">,
  notices: { seen: {}, pending: {} } as Pick<PrdNoticesState, "seen" | "pending">,
  works: {} as Record<string, unknown>,
  acknowledge: vi.fn(),
}));

vi.mock("@/lib/store/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/chat")>();
  return {
    ...actual,
    useChatStore: <T,>(selector: (state: Pick<ChatState, "chats">) => T): T => selector(seed.chats),
  };
});

vi.mock("@/lib/store/prd-notices", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/prd-notices")>();
  return {
    ...actual,
    usePrdNoticesStore: Object.assign(
      <T,>(selector: (state: Pick<PrdNoticesState, "seen" | "pending">) => T): T => selector(seed.notices),
      { getState: () => ({ acknowledge: seed.acknowledge }) },
    ),
  };
});

vi.mock("@/lib/store/work-message", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/work-message")>();
  return {
    ...actual,
    useWorkMessageStore: <T,>(selector: (state: { works: typeof seed.works }) => T): T =>
      selector({ works: seed.works }),
  };
});

vi.mock("@/hooks/use-conversation", () => ({ useConversation: () => ({}) }));
vi.mock("@/hooks/use-prd", () => ({ usePrd: () => ({ data: undefined }) }));
vi.mock("@/hooks/use-upload-material", () => ({ useUploadMaterial: () => vi.fn() }));
vi.mock("@/hooks/use-chat", () => ({
  usePostMessage: () => ({ isPending: false, mutate: vi.fn() }),
  useAnswerQuestion: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-version", () => ({
  useRollbackVersion: () => ({ mutate: vi.fn(), isPending: false }),
  useVersionViewSession: () => ({
    startView: { mutate: vi.fn(), isPending: false, isError: false, data: undefined, reset: vi.fn() },
    start: vi.fn(),
    finish: vi.fn(),
  }),
}));

function seedChat(overrides: Partial<ProjectChat> = {}) {
  seed.works = {};
  seed.chats = {
    chats: {
      p1: { messages: [], chatRunIds: [], ingestedRunIds: [], seenEventIds: [], turnActive: false, ...overrides },
    },
  };
}

beforeEach(() => {
  seedChat();
  seed.notices = { seen: {}, pending: {} };
  seed.acknowledge.mockReset();
});
afterEach(cleanup);

describe("CommandArea · 阶段页形态交互（#346）", () => {
  it("PRD 增量刷新横幅：X 关闭＝认领（acknowledge 清 pending），横幅即逝", () => {
    seed.notices = { seen: { p1: true }, pending: { p1: true } };
    render(<CommandArea projectId="p1" variant="stage" />);

    fireEvent.click(screen.getByRole("button", { name: "知道了" }));

    expect(seed.acknowledge).toHaveBeenCalledWith("p1");
  });

  it("数据模板钮：向输入框插入模板骨架（受控输入、不直接发送）", () => {
    render(<CommandArea projectId="p1" variant="stage" />);
    const input = screen.getByLabelText(/补充您的约束逻辑/) as HTMLTextAreaElement;

    fireEvent.click(screen.getByRole("button", { name: "插入数据模板" }));

    expect(input.value).toBe(DATA_TEMPLATE_SNIPPET);
  });

  it("已有输入时插模板：追加不覆盖（用户的字不丢）", () => {
    render(<CommandArea projectId="p1" variant="stage" />);
    const input = screen.getByLabelText(/补充您的约束逻辑/) as HTMLTextAreaElement;
    fireEvent.change(input, { target: { value: "服务费不含备件费" } });

    fireEvent.click(screen.getByRole("button", { name: "插入数据模板" }));

    expect(input.value).toBe(`服务费不含备件费\n${DATA_TEMPLATE_SNIPPET}`);
  });
});
