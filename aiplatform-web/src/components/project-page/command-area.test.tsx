import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ChatState, ChatMessage } from "@/lib/store/chat";
import type { PrdNoticesState } from "@/lib/store/prd-notices";

import { CommandArea } from "./command-area";
import { lockRowOf } from "@/lib/orders/lock";

// 直读种子状态渲染（zustand v5 server snapshot 限制同 project-page-shell.test）；
// store 本体行为由 chat.test 覆盖。发送口 mock 掉——路由判定归纯逻辑测试。
const seed = vi.hoisted(() => ({
  chats: { chats: {} } as Pick<ChatState, "chats">,
  notices: { seen: {}, pending: {} } as Pick<PrdNoticesState, "seen" | "pending">,
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

vi.mock("@/hooks/use-chat", () => ({
  usePostMessage: () => ({ isPending: false, mutate: vi.fn() }),
  useAnswerQuestion: () => ({ isPending: false, mutate: vi.fn() }),
}));

function question(overrides: Partial<Extract<ChatMessage, { kind: "question" }>> = {}) {
  return {
    kind: "question",
    id: "q1",
    runId: "run-1",
    engineRef: "reply-1",
    header: "目标用户",
    question: "这个系统主要面向谁?",
    multiple: false,
    options: ["企业客户", "个人用户"],
    toolCalls: [{ id: "tc-1", name: "ask_user", input: {} }],
    answered: false,
    ...overrides,
  } satisfies Extract<ChatMessage, { kind: "question" }>;
}

function seedChat(messages: ChatMessage[], turnActive = false) {
  seed.chats = {
    chats: { p1: { messages, baRunIds: [], ingestedRunIds: [], seenEventIds: [], turnActive } },
  };
}

describe("CommandArea · 指令区（#19 需求环①）", () => {
  it("对话流：用户气泡右对齐、BA 气泡带「需求分析师」落款、开场引导语常在", () => {
    seedChat([
      { kind: "user", id: "u1", text: "给宠物医院做预约系统" },
      { kind: "ba", id: "b1", text: "初步理解：在线预约。" },
    ]);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("给宠物医院做预约系统");
    expect(html).toContain("初步理解：在线预约。");
    expect(html).toContain("需求分析师");
    expect(html).toContain("把想法告诉需求分析师");
  });

  it("待答问题：问答卡在流内、输入条提示「回答上面的问题」（Enter 即答复锚点）", () => {
    seedChat([
      { kind: "user", id: "u1", text: "做个官网" },
      { kind: "ba", id: "b1", text: "先问一句" },
      question(),
    ]);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("这个系统主要面向谁?");
    expect(html).toContain("回答上面的问题");
  });

  it("轮进行中：打字指示（「正在输入」）；无问题时常规输入条", () => {
    seedChat([{ kind: "user", id: "u1", text: "加个功能" }], true);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("需求分析师正在输入");
    expect(html).toContain("和需求分析师聊聊你的想法");
  });

  it("错误帧呈现中断提示（可重发）；归档禁用输入", () => {
    seedChat([{ kind: "error", id: "e1", text: "模型调用失败" }]);

    expect(renderToStaticMarkup(<CommandArea projectId="p1" />)).toContain(
      "本轮回复中断：模型调用失败",
    );

    const archived = renderToStaticMarkup(
      <CommandArea projectId="p1" lock={lockRowOf({ archived: true })} />,
    );
    expect(archived).toContain("项目已归档，指令区已关闭");
    expect(archived).toContain("disabled");
  });

  it("修正收口「未动系统」通告（#46）：平台侧如实告知原因（区别于 BA 话语与错误）", () => {
    seedChat([
      { kind: "user", id: "u1", text: "把主色调改成绿色" },
      { kind: "ba", id: "b1", text: "已修订 PRD。" },
      { kind: "notice", id: "n1", text: "纯文档性修订，系统现状已满足" },
    ]);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("本轮意见未改动系统：纯文档性修订，系统现状已满足");
  });

  it("PRD 修订未认领：输入条上方出「PRD 有更新 · 去看看」胶囊；认领后不渲染", () => {
    seedChat([{ kind: "ba", id: "b1", text: "已按你的意见修订。" }]);
    seed.notices = { seen: { p1: true }, pending: { p1: true } };

    expect(renderToStaticMarkup(<CommandArea projectId="p1" />)).toContain(
      "PRD 有更新 · 去看看",
    );

    seed.notices = { seen: { p1: true }, pending: {} };
    expect(renderToStaticMarkup(<CommandArea projectId="p1" />)).not.toContain("去看看");
  });

  it("订单锁定（#28）：待报价行——输入禁用、锁定提示与占位都指向「取消订单」", () => {
    seedChat([{ kind: "user", id: "u1", text: "这个系统不错" }]);

    const locked = renderToStaticMarkup(
      <CommandArea
        projectId="p1"
        lock={lockRowOf({ activeOrder: { id: "o1", status: 1, statusName: "待报价" } })}
        confirmOrder={<button type="button">确认下单</button>}
      />,
    );
    expect(locked).toContain("订单处理中——如需继续修改，请取消订单");
    expect(locked).toContain("disabled");
    // 锁定期间「确认下单」与 PRD 胶囊一并退场（订单已存在/迭代已冻结）
    expect(locked).not.toContain("确认下单</button>");
    expect(locked).not.toContain("去看看");
  });

  it("「确认下单」槽（#26）：注入即渲染于输入条上方；归档（disabled）不渲染", () => {
    seedChat([{ kind: "user", id: "u1", text: "这个系统不错" }]);

    const injected = renderToStaticMarkup(
      <CommandArea projectId="p1" confirmOrder={<button type="button">确认下单</button>} />,
    );
    expect(injected).toContain("确认下单");

    const archived = renderToStaticMarkup(
      <CommandArea
        projectId="p1"
        lock={lockRowOf({ archived: true })}
        confirmOrder={<button type="button">确认下单</button>}
      />,
    );
    expect(archived).not.toContain("确认下单");
  });
});
