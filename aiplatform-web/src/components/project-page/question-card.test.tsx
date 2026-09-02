import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ChatMessage } from "@/lib/store/chat";

import { QuestionCard } from "./question-card";

type QuestionCardMessage = Extract<ChatMessage, { kind: "question" }>;

function card(overrides: Partial<QuestionCardMessage> = {}): QuestionCardMessage {
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
  };
}

const noop = () => {};
const noopSelection = () => {};

describe("QuestionCard · CC 式问答卡（#19）", () => {
  it("单选（multiple=false）：选项 chip 即按钮（点即答）、无提交钮", () => {
    const html = renderToStaticMarkup(
      <QuestionCard question={card()} interactive selection={[]} onSelectionChange={noopSelection} onAnswer={noop} />,
    );

    expect(html).toContain("这个系统主要面向谁?");
    expect(html).toContain("目标用户");
    expect(html).toContain("企业客户");
    expect(html).not.toContain("提交"); // 单选无提交钮
    expect(html).not.toContain("已回答");
  });

  it("多选（multiple=true）：ui ToggleGroup 勾选 chip + 提交钮，无勾选禁用", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ multiple: true })}
        interactive
        selection={[]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(html).toContain("可多选");
    expect(html).toContain('data-slot="toggle-group"');
    expect(html).toContain("企业客户");
    expect(html).toContain("提交");
    expect(html).toContain("disabled"); // 无勾选不可提交
    // 选项面换行（真实布局由浏览器级验证收口；此处防类名退化回 nowrap 平铺出屏）
    expect(html).toMatch(/data-slot="toggle-group"[^>]*flex-wrap/);
  });

  it("多选已勾选：勾选项按 pressed 态呈现", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ multiple: true })}
        interactive
        selection={["企业客户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain('aria-pressed="false"');
  });

  it("已答（或被取代）：只读终态——「已回答」标记、chip 面禁用、无提交钮", () => {
    const single = renderToStaticMarkup(
      <QuestionCard question={card({ answered: true })} interactive={false} selection={[]} onSelectionChange={noopSelection} onAnswer={noop} />,
    );
    const multi = renderToStaticMarkup(
      <QuestionCard
        question={card({ multiple: true, answered: true })}
        interactive={false}
        selection={[]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(single).toContain("已回答");
    expect(single).not.toContain("<button"); // 单选已答 chip 退纯文本
    expect(multi).toContain("已回答");
    expect(multi).toContain("disabled"); // ToggleGroup 整组禁用
    expect(multi).not.toContain(">提交<"); // 已答无提交钮
  });
});
