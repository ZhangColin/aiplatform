import { describe, expect, it } from "vitest";

import {
  composeAnswer,
  parseQuestion,
  toAnswerToolCalls,
  toggleSelection,
  type WaitRaisedPayload,
} from "./qa";

function questionData(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    type: "question",
    toolCalls: [{ id: "tc-1", name: "ask_user", input: { question: "面向谁?" } }],
    questions: [
      {
        header: "目标用户",
        question: "这个系统主要面向谁?",
        multiple: false,
        custom: true,
        options: [{ label: "企业客户" }, { label: "个人用户" }],
        ...overrides,
      },
    ],
  };
}

function waitRaised(data: unknown, kind = "QUESTION"): WaitRaisedPayload {
  return { runId: "run-9", kind, engineRef: "reply-7", data };
}

describe("parseQuestion · wait-raised → 问答卡（#19）", () => {
  it("QUESTION 帧解析出问答卡全要素（qid=engineRef、toolCalls 回传面随卡）", () => {
    const q = parseQuestion("run-9:12", waitRaised(questionData()));

    expect(q).toMatchObject({
      id: "run-9:12",
      runId: "run-9",
      engineRef: "reply-7",
      header: "目标用户",
      question: "这个系统主要面向谁?",
      multiple: false,
      options: ["企业客户", "个人用户"],
    });
    expect(q?.toolCalls).toEqual([{ id: "tc-1", name: "ask_user", input: { question: "面向谁?" } }]);
  });

  it("multiple 投影（多选问答卡）；无 options 纯开放题照成卡", () => {
    expect(parseQuestion("e1", waitRaised(questionData({ multiple: true })))?.multiple).toBe(true);

    const open = parseQuestion("e2", waitRaised({ type: "question", toolCalls: [], questions: [{ question: "还有什么要补充?" }] }));
    expect(open).toMatchObject({ options: [], header: "提问", question: "还有什么要补充?" });
  });

  it("PERMISSION 挂起 / 无 engineRef / data 残缺 → 不成卡（指令区不呈现交互卡）", () => {
    expect(parseQuestion("e1", waitRaised(questionData(), "PERMISSION"))).toBeNull();
    expect(parseQuestion("e2", { runId: "run-9", kind: "QUESTION", data: questionData() })).toBeNull();
    expect(parseQuestion("e3", waitRaised({ type: "question", toolCalls: [] }))).toBeNull();
    expect(parseQuestion("e4", waitRaised(null))).toBeNull();
  });
});

describe("composeAnswer · 三种作答形态的答复拼装", () => {
  it("自由输入独立成答；勾选拼接；可与已勾选合并（勾选在前、输入在后以「；」连接）", () => {
    expect(composeAnswer([], "海外企业客户")).toBe("海外企业客户");
    expect(composeAnswer(["预约", "会员"], "")).toBe("预约；会员");
    expect(composeAnswer(["预约"], "还想加个提醒")).toBe("预约；还想加个提醒");
  });

  it("全空为空串（调用方禁用提交）；空白输入修剪后丢弃", () => {
    expect(composeAnswer([], "")).toBe("");
    expect(composeAnswer([], "   ")).toBe("");
    expect(composeAnswer(["预约"], "  ")).toBe("预约");
  });
});

describe("toggleSelection · 多选勾选切换", () => {
  it("勾上再取消；互不影响其他勾选", () => {
    expect(toggleSelection([], "预约")).toEqual(["预约"]);
    expect(toggleSelection(["预约", "会员"], "预约")).toEqual(["会员"]);
  });
});

describe("toAnswerToolCalls · 回传面收窄", () => {
  it("挂起帧元素原样收窄为作答命令形状（非字符串字段弃守）", () => {
    expect(
      toAnswerToolCalls([
        { id: "tc-1", name: "ask_user", input: { question: "面向谁?" } },
        { id: 7, name: null, input: "坏形状" },
      ]),
    ).toEqual([
      { id: "tc-1", name: "ask_user", input: { question: "面向谁?" } },
      { id: undefined, name: undefined, input: undefined },
    ]);
  });
});
