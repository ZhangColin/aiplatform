import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ChatState, ChatMessage, ProjectChat } from "@/lib/store/chat";
import type { PrdNoticesState } from "@/lib/store/prd-notices";

import { CommandArea } from "./command-area";

/**
 * CommandArea 阶段页形态（#346，spec #320 ⑥ 需求分析页 P02/P03）：variant=
 * "stage"——时间流与澄清输入各自成卡（照原型左栏三卡构图）、PRD 更新呈现
 * 迁移位＝时间流内横幅（「PRD 规范已增量刷新」系统横幅＝现「PRD 有更新」
 * 胶囊迁移位）、澄清输入照原型（「提交澄清」文本键＋语音/附件/数据模板钮）。
 * 气泡形态照原型（用户右对齐＋头像／智能体渐变气泡＋渐变头像——ADR 0006
 * 无角色署名口径不动：头像只作视觉锚，不落「Gravity Clarifier」角色名）。
 * mock 搭法同 command-area.test。
 */
const seed = vi.hoisted(() => ({
  chats: { chats: {} } as Pick<ChatState, "chats">,
  notices: { seen: {}, pending: {} } as Pick<PrdNoticesState, "seen" | "pending">,
  works: {} as Record<string, unknown>,
  prd: { data: undefined as { draftChangeSummary?: string | null } | undefined },
}));

vi.mock("@/lib/store/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/chat")>();
  return {
    ...actual,
    useChatStore: <T,>(selector: (state: Pick<ChatState, "chats">) => T): T =>
      selector(seed.chats),
  };
});

vi.mock("@/lib/store/prd-notices", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/prd-notices")>();
  return {
    ...actual,
    usePrdNoticesStore: <T,>(selector: (state: Pick<PrdNoticesState, "seen" | "pending">) => T): T =>
      selector(seed.notices),
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

vi.mock("@/hooks/use-conversation", () => ({
  useConversation: () => ({}),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({ data: seed.prd.data }),
}));

vi.mock("@/hooks/use-upload-material", () => ({
  useUploadMaterial: () => vi.fn(),
}));
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

function seedChat(messages: ChatMessage[], overrides: Partial<ProjectChat> = {}) {
  seed.works = {};
  seed.chats = {
    chats: {
      p1: { messages, chatRunIds: [], ingestedRunIds: [], seenEventIds: [], turnActive: false, ...overrides },
    },
  };
}

function renderStage() {
  return renderToStaticMarkup(<CommandArea projectId="p1" variant="stage" generated />);
}

describe("CommandArea · 阶段页形态（variant=stage）", () => {
  it("时间流卡＋澄清输入卡各自成帧（照原型左栏三卡构图）", () => {
    seedChat([]);
    const html = renderStage();
    expect(html).toContain('data-frame="timeline-card"');
    expect(html).toContain('data-frame="composer-card"');
  });

  it("澄清输入照原型：「提交澄清」文本键＋语音/附件/数据模板钮在位＋原型占位话术", () => {
    seedChat([]);
    const html = renderStage();
    expect(html).toContain("提交澄清");
    expect(html).toContain("插入数据模板");
    expect(html).toContain("语音输入");
    expect(html).toContain("补充您的约束逻辑或回答 AI 提问");
  });

  it("PRD 修订未认领：时间流内出「PRD 规范已增量刷新并重算基线」横幅（迁移位），不出旧胶囊", () => {
    seedChat([]);
    seed.notices = { seen: { p1: true }, pending: { p1: true } };
    const html = renderStage();
    expect(html).toContain("PRD 规范已增量刷新并重算基线");
    expect(html).not.toContain("去看看");
    seed.notices = { seen: {}, pending: {} };
  });

  it("范围级新草稿停驻（#334）：横幅出「PRD 已更新，确认新版后生成下一版 Demo」（指路确认门，非跳转）", () => {
    seedChat([]);
    seed.prd.data = { draftChangeSummary: "新增「管理后台」" };
    const html = renderStage();
    expect(html).toContain("PRD 已更新，确认新版后生成下一版 Demo");
    seed.prd.data = undefined;
  });
});

describe("CommandArea · 气泡形态照原型（两形态共用）", () => {
  it("用户发言＝右对齐气泡＋用户头像锚；智能体话语＝渐变气泡＋渐变头像（无角色署名，ADR 0006）", () => {
    seedChat([
      { kind: "user", id: "u1", text: "给宠物医院做预约系统" },
      { kind: "agent", id: "a1", text: "需求结构已建档。" },
    ]);
    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);
    // 用户行：右对齐＋头像视觉锚
    expect(html).toContain('data-avatar="user"');
    // 智能体行：渐变头像锚＋渐变气泡（rounded-tl-sm 圆角照原型）
    expect(html).toContain('data-avatar="agent"');
    expect(html).toContain("rounded-tl-sm");
    // 无角色署名口径不动：不落原型角色名
    expect(html).not.toContain("Gravity Clarifier");
    expect(html).not.toContain("客户业务架构师");
  });
});
