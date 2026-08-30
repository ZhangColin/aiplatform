import { describe, expect, it } from "vitest";

import {
  buildAnswerCommand,
  buildDeferredCommand,
  buildPermissionCommand,
  isQuestionWait,
  narrowPermissionBody,
  narrowQuestionBody,
  normalizeWait,
  waitKindLabel,
  WAIT_KIND,
  type ProjectWaitResponse,
} from "./wait";

// swagger 把 wait.body 生成成松散的 index signature（引擎载荷原样）；测试里喂 unknown
// 形状，构造时整体断言为 ProjectWaitResponse 绕过该生成型。
const rawWait = (overrides: Record<string, unknown>): ProjectWaitResponse =>
  overrides as ProjectWaitResponse;

describe("normalizeWait", () => {
  it("透传完整字段 + body 原样保留", () => {
    const body = { from: "项目顾问", questions: [] };
    expect(
      normalizeWait(
        rawWait({
          waitId: "w1",
          kind: 1,
          kindName: "问答",
          status: 1,
          statusName: "待处理",
          summary: "确认需求",
          runId: "r1",
          raisedAt: "2026-08-22T02:15:33Z",
          settledAt: "",
          settleOutcome: 0,
          settleOutcomeName: "",
          body,
        }),
      ),
    ).toEqual({
      waitId: "w1",
      kind: 1,
      kindName: "问答",
      status: 1,
      statusName: "待处理",
      summary: "确认需求",
      runId: "r1",
      raisedAt: "2026-08-22T02:15:33Z",
      settledAt: "",
      settleOutcome: 0,
      settleOutcomeName: "",
      body,
    });
  });

  it("字段全缺：归一为中性空值不炸", () => {
    expect(normalizeWait({})).toEqual({
      waitId: "",
      kind: 0,
      kindName: "",
      status: 0,
      statusName: "",
      summary: "",
      runId: "",
      raisedAt: "",
      settledAt: "",
      settleOutcome: 0,
      settleOutcomeName: "",
      body: undefined,
    });
  });
});

describe("isQuestionWait", () => {
  it("kind=1 判问答", () => {
    expect(isQuestionWait(normalizeWait({ kind: WAIT_KIND.QUESTION }))).toBe(true);
  });

  it("kind=2 判非问答（权限）", () => {
    expect(isQuestionWait(normalizeWait({ kind: WAIT_KIND.PERMISSION }))).toBe(false);
  });

  it("kind 未知：body 有 questions 兜底判问答", () => {
    expect(isQuestionWait(normalizeWait(rawWait({ kind: 0, body: { questions: [] } })))).toBe(true);
    expect(isQuestionWait(normalizeWait(rawWait({ kind: 0, body: {} })))).toBe(false);
  });
});

describe("narrowQuestionBody", () => {
  it("收窄 demo pendingQuestions 形状（多选/自定义/选项说明）", () => {
    expect(
      narrowQuestionBody({
        from: "项目顾问",
        questions: [
          {
            header: "验证码渠道",
            question: "手机验证用哪种？",
            multiple: false,
            custom: true,
            options: [{ label: "短信验证码", description: "成本略高" }, { label: "暂不验证" }],
          },
        ],
      }),
    ).toEqual({
      from: "项目顾问",
      questions: [
        {
          header: "验证码渠道",
          question: "手机验证用哪种？",
          multiple: false,
          custom: true,
          options: [
            { label: "短信验证码", description: "成本略高" },
            { label: "暂不验证", description: "" },
          ],
        },
      ],
    });
  });

  it("选项全缺的题丢弃；无 label 的选项丢弃", () => {
    expect(
      narrowQuestionBody({
        questions: [
          { header: "无选项题", options: [] },
          { header: "有效题", options: [{ label: "A" }, { label: "", description: "空" }] },
        ],
      }),
    ).toEqual({
      from: "",
      questions: [{ header: "有效题", question: "", multiple: false, custom: false, options: [{ label: "A", description: "" }] }],
    });
  });

  it("custom-only 题（无选项但可自定义）保留", () => {
    expect(
      narrowQuestionBody({
        questions: [{ header: "补充说明", question: "还有什么想说的", custom: true, options: [] }],
      }),
    ).toEqual({
      from: "",
      questions: [
        { header: "补充说明", question: "还有什么想说的", multiple: false, custom: true, options: [] },
      ],
    });
  });

  it("非对象 body：回退空题集", () => {
    expect(narrowQuestionBody(undefined)).toEqual({ from: "", questions: [] });
    expect(narrowQuestionBody(null)).toEqual({ from: "", questions: [] });
    expect(narrowQuestionBody("x")).toEqual({ from: "", questions: [] });
  });
});

describe("narrowPermissionBody", () => {
  it("收窄 demo APPROVAL 形状", () => {
    expect(
      narrowPermissionBody({ tool: "bash", args: "pnpm build", reason: "部署预览", expiresInMin: 30 }),
    ).toEqual({ tool: "bash", args: "pnpm build", reason: "部署预览", expiresInMin: 30 });
  });

  it("入参 args 缺失回退 pre（spec 0001 §5「入参 pre」）", () => {
    expect(narrowPermissionBody({ tool: "read", pre: "docs/1.md" })).toEqual({
      tool: "read",
      args: "docs/1.md",
      reason: "",
      expiresInMin: 30,
    });
  });

  it("expiresInMin 缺省 30；非对象 body 全中性", () => {
    expect(narrowPermissionBody({ tool: "bash" })).toEqual({
      tool: "bash",
      args: "",
      reason: "",
      expiresInMin: 30,
    });
    expect(narrowPermissionBody(undefined)).toEqual({ tool: "", args: "", reason: "", expiresInMin: 30 });
  });
});

describe("settle 三型载荷构造", () => {
  it("answer：answers 二维 trim + 空串归一（空 custom 不产生空标签）", () => {
    expect(buildAnswerCommand([["短信验证码"], ["品种", "  "], []])).toEqual({
      type: "answer",
      answers: [["短信验证码"], ["品种"], []],
    });
  });

  it("permission：approve 布尔透传", () => {
    expect(buildPermissionCommand(true)).toEqual({ type: "permission", approve: true });
    expect(buildPermissionCommand(false)).toEqual({ type: "permission", approve: false });
  });

  it("deferred：content 空串省略 + assigneeAccountId string→int64", () => {
    expect(buildDeferredCommand(" 回归测试 ", "", "101")).toEqual({
      type: "deferred",
      task: { title: "回归测试", assigneeAccountId: 101 },
    });
    expect(buildDeferredCommand("回归", " 跑一遍 ", "101")).toEqual({
      type: "deferred",
      task: { title: "回归", content: "跑一遍", assigneeAccountId: 101 },
    });
  });
});

describe("waitKindLabel", () => {
  it("1=提问 2=审批 未知=等待", () => {
    expect(waitKindLabel(1)).toBe("提问");
    expect(waitKindLabel(2)).toBe("审批");
    expect(waitKindLabel(0)).toBe("等待");
    expect(waitKindLabel(99)).toBe("等待");
  });
});
