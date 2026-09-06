import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ChatState, ChatMessage, ProjectChat } from "@/lib/store/chat";
import type { PrdNoticesState } from "@/lib/store/prd-notices";

import { CommandArea } from "./command-area";
import { lockRowOf } from "@/lib/orders/lock";

// 直读种子状态渲染（zustand v5 server snapshot 限制同 project-page-shell.test）；
// store 本体行为由 chat.test 覆盖。发送口 mock 掉——路由判定归纯逻辑测试。
const seed = vi.hoisted(() => ({
  chats: { chats: {} } as Pick<ChatState, "chats">,
  notices: { seen: {}, pending: {} } as Pick<PrdNoticesState, "seen" | "pending">,
  works: {} as Record<string, unknown>,
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
    usePrdNoticesStore: <T,>(selector: (state: Pick<PrdNoticesState, "seen" | "pending">) => T) =>
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

function seedChat(
  messages: ChatMessage[],
  turnActive = false,
  overrides: Partial<ProjectChat> = {},
) {
  seed.works = {}; // 工作消息种子独立于对话史（仅个别用例摆），每次重置
  seed.chats = {
    chats: {
      p1: {
        messages,
        chatRunIds: [],
        ingestedRunIds: [],
        seenEventIds: [],
        turnActive,
        ...overrides,
      },
    },
  };
}

describe("CommandArea · 对话区（#19 需求环① + #47 三分类，#86 单会话无角色）", () => {
  it("对话流：用户气泡右对齐、智能体气泡无署名（只有一个「它」）、平台引导带「平台」落款、开场引导语常在", () => {
    seedChat([
      { kind: "user", id: "u1", text: "给宠物医院做预约系统" },
      { kind: "agent", id: "b1", text: "初步理解：在线预约。" },
      { kind: "user", id: "u2", text: "我后台的地址是什么？" },
      { kind: "agent", id: "a1", text: "访问地址是 http://localhost:32168/" },
      { kind: "user", id: "u3", text: "你好呀" },
      { kind: "agent", id: "g1", text: "我在这里帮您把系统做出来。", label: "平台" },
    ]);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("给宠物医院做预约系统");
    expect(html).toContain("初步理解：在线预约。");
    // #86 无角色标签：智能体话语无署名；guide-reply 自带「平台」（平台自己说话）
    expect(html).not.toContain("智能体");
    expect(html).toContain("平台");
    // 常驻文案初版（#79）：缺省访谈期——告知阶段与下一步
    expect(html).toContain("访谈中");
  });

  it("待答问题：问答卡在流内、输入条提示「回答上面的问题」（Enter 即答复锚点）", () => {
    seedChat([
      { kind: "user", id: "u1", text: "做个官网" },
      { kind: "agent", id: "b1", text: "先问一句" },
      question(),
    ]);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("这个系统主要面向谁?");
    expect(html).toContain("回答上面的问题");
  });

  it("受理动作卡（#87）：受理中在意见下方呈现「正在处理」；落定（收口推导）转「意见已受理」", () => {
    seedChat([
      { kind: "user", id: "u1", text: "把系统的主色调改成绿色" },
      { kind: "acceptance", id: "run-1:1", runId: "run-1", settled: false },
      { kind: "agent", id: "b1", text: "我来处理这个需求" },
    ]);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("已收到你的意见，正在处理");
    expect(html.indexOf("把系统的主色调改成绿色")).toBeLessThan(
      html.indexOf("已收到你的意见，正在处理"),
    ); // 意见在卡上（卡承接这条意见）

    seedChat([
      { kind: "user", id: "u1", text: "把系统的主色调改成绿色" },
      { kind: "acceptance", id: "run-1:1", runId: "run-1", settled: true },
    ]);

    expect(renderToStaticMarkup(<CommandArea projectId="p1" />)).toContain("意见已受理");
  });

  it("轮进行中：打字指示无角色前缀；无问题时常规输入条", () => {
    seedChat([{ kind: "user", id: "u1", text: "加个功能" }], true);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("正在输入");
    expect(html).toContain("和平台聊聊你的想法");
  });

  it("错误事件呈现中断提示（可重发）；归档禁用输入", () => {
    seedChat([{ kind: "error", id: "e1", text: "模型调用失败" }]);

    expect(renderToStaticMarkup(<CommandArea projectId="p1" />)).toContain(
      "本轮回复中断：模型调用失败",
    );

    const archived = renderToStaticMarkup(
      <CommandArea projectId="p1" lock={lockRowOf({ archived: true })} />,
    );
    expect(archived).toContain("项目已归档，对话区已关闭");
    expect(archived).toContain("disabled");
  });

  it("编码 run 进行中：对话流末尾出工作消息（#81 生长中——解说 + 步骤分组 + 动作卡）", () => {
    seedChat([
      { kind: "user", id: "u1", text: "把主色调改成绿色" },
      { kind: "agent", id: "b1", text: "已接住意见，开始处理。" },
    ]);
    seed.works = {
      p1: {
        runId: "run-1",
        startedAt: 1_000,
        frozen: false,
        parts: [
          { kind: "step", id: "run-1:2", step: 1 },
          { kind: "text", id: "run-1:3", text: "正在调整全局配色。" },
          {
            kind: "action",
            id: "run-1:4",
            toolCallId: "tc-1",
            toolName: "edit_file",
            state: "running",
            label: "修改【全局样式】",
            startedAt: 1_000,
          },
        ],
      },
    };

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);

    expect(html).toContain("正在做");
    expect(html).toContain("第 1 步");
    expect(html).toContain("正在调整全局配色。");
    expect(html).toContain("修改【全局样式】");
    expect(html).toContain("进行中");
  });

  it("PRD 修订未认领：输入条上方出「PRD 有更新 · 去看看」胶囊；认领后不渲染", () => {
    seedChat([{ kind: "agent", id: "b1", text: "已按你的意见修订。" }]);
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
      />,
    );
    expect(locked).toContain("订单处理中——如需继续修改，请取消订单");
    expect(locked).toContain("disabled");
    // 锁定期间 PRD 胶囊退场（订单已存在/迭代已冻结）
    expect(locked).not.toContain("去看看");
  });

});

describe("CommandArea · 常驻文案与共享发送框（#79）", () => {
  it("阶段两态：缺省访谈期 / stage=iterate 切迭代期文案", () => {
    seedChat([{ kind: "agent", id: "b1", text: "已按你的意见修订。" }]);

    expect(renderToStaticMarkup(<CommandArea projectId="p1" />)).toContain("访谈中");

    const iterated = renderToStaticMarkup(<CommandArea projectId="p1" stage="iterate" />);
    expect(iterated).toContain("迭代中");
    expect(iterated).not.toContain("访谈中");
  });

  it("发送框 = 共享 Composer（立体卡片）——首页/项目页同一组件", () => {
    seedChat([{ kind: "agent", id: "b1", text: "开场" }]);

    const html = renderToStaticMarkup(<CommandArea projectId="p1" />);
    // Composer 卡片形态（ring 圆角卡片 + 圆形发送键 + 类型下拉）
    expect(html).toContain("rounded-2xl");
    expect(html).toContain('aria-label="发送"');
    expect(html).toContain('aria-label="做系统"');
    // 对话流暂无附件管道：入口隐去（不邀请会被丢弃的操作）
    expect(html).not.toContain('aria-label="附件"');
  });
});
