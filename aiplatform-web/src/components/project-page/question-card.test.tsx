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
  it("「第 N 问」序号（#538/#549）：带 ordinal 渲染徽标，未带不渲染", () => {
    const withOrdinal = renderToStaticMarkup(
      <QuestionCard
        question={card()}
        ordinal={3}
        interactive
        selection={[]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );
    expect(withOrdinal).toContain('data-slot="question-ordinal"');
    expect(withOrdinal).toContain("第 3 问");

    const withoutOrdinal = renderToStaticMarkup(
      <QuestionCard question={card()} interactive selection={[]} onSelectionChange={noopSelection} onAnswer={noop} />,
    );
    expect(withoutOrdinal).not.toContain('data-slot="question-ordinal"');
  });

  it("「第 N 问」序号已答终态仍呈现（回看访谈可见进度）", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ answered: true })}
        ordinal={2}
        interactive={false}
        selection={[]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );
    expect(html).toContain('data-slot="question-ordinal"');
    expect(html).toContain("第 2 问");
    expect(html).toContain("已回答");
  });

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

  it("推荐答案（#459）单选：「推荐」chip＋勾选提交态（非点即答），预选项 pressed", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ recommended: "企业客户" })}
        interactive
        selection={["企业客户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(html).toContain("推荐");
    expect(html).toContain('data-slot="recommended-badge"');
    expect(html).toContain('data-slot="toggle-group"'); // 带推荐的单选＝勾选＋提交
    expect(html).toContain('aria-pressed="true"'); // 预选项 pressed（父层初始勾选）
    expect(html).toContain('aria-pressed="false"');
    expect(html).toContain("提交");
  });

  it("推荐答案（#459）多选：推荐项带「推荐」chip，勾选提交照旧", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ multiple: true, recommended: "个人用户" })}
        interactive
        selection={["个人用户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(html).toContain('data-slot="recommended-badge"');
    expect(html).toContain("可多选");
    expect(html).toContain('aria-pressed="true"');
    expect(html).toContain("提交");
  });

  it("勾选态呈现（2026-11-26 用户反馈）：多选未勾选——任何选项不渲染勾", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ multiple: true })}
        interactive
        selection={[]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(html).not.toContain('data-slot="option-check"');
  });

  it("勾选态呈现：勾只出现在被勾选项上（多选勾一项恰一个勾）", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ multiple: true })}
        interactive
        selection={["企业客户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(html.match(/data-slot="option-check"/g)?.length).toBe(1);
  });

  it("勾选态呈现：带推荐的单选——勾随选中项唯一且可移动（预选项有勾、改选后勾跟走）", () => {
    const preselected = renderToStaticMarkup(
      <QuestionCard
        question={card({ recommended: "企业客户" })}
        interactive
        selection={["企业客户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );
    expect(preselected.match(/data-slot="option-check"/g)?.length).toBe(1);

    const switched = renderToStaticMarkup(
      <QuestionCard
        question={card({ recommended: "企业客户" })}
        interactive
        selection={["个人用户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );
    expect(switched.match(/data-slot="option-check"/g)?.length).toBe(1);
  });

  it("勾选态呈现：点即答单选（无推荐）无勾、已答回看按作答显示勾", () => {
    const instant = renderToStaticMarkup(
      <QuestionCard question={card()} interactive selection={[]} onSelectionChange={noopSelection} onAnswer={noop} />,
    );
    expect(instant).not.toContain('data-slot="option-check"');

    const answered = renderToStaticMarkup(
      <QuestionCard
        question={card({ multiple: true, answered: true })}
        interactive={false}
        selection={["企业客户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );
    expect(answered.match(/data-slot="option-check"/g)?.length).toBe(1);
  });

  it("推荐答案（#459）已答：只读终态——「已回答」、无提交钮、推荐标记仍呈现", () => {
    const html = renderToStaticMarkup(
      <QuestionCard
        question={card({ recommended: "企业客户", answered: true })}
        interactive={false}
        selection={["企业客户"]}
        onSelectionChange={noopSelection}
        onAnswer={noop}
      />,
    );

    expect(html).toContain("已回答");
    expect(html).toContain('data-slot="recommended-badge"');
    expect(html).not.toContain(">提交<");
    expect(html).toContain("disabled"); // ToggleGroup 整组禁用
  });
});
