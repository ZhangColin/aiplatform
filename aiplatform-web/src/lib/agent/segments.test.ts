import { describe, expect, it } from "vitest";

import { segmentPatch, segmentPatchDiff, segmentStep, segmentText, segmentTool } from "./segments";

describe("segmentText（text / reasoning 段）", () => {
  it("opencode part {type, text} → 取 text", () => {
    expect(segmentText({ type: "text", text: "最终文本" })).toBe("最终文本");
    expect(segmentText({ type: "reasoning", text: "思考" })).toBe("思考");
  });

  it("裸字符串 / 缺字段 → 兜底空串", () => {
    expect(segmentText("直接字符串")).toBe("直接字符串");
    expect(segmentText({ type: "text" })).toBe("");
    expect(segmentText(null)).toBe("");
    expect(segmentText(undefined)).toBe("");
  });
});

describe("segmentTool（工具 chip）", () => {
  it("opencode tool part：name=工具名、arg=入参、status 由 state.status 收窄", () => {
    expect(
      segmentTool({ type: "tool", tool: "bash", state: { status: "running", input: { cmd: "pnpm build" } } }),
    ).toEqual({ name: "bash", arg: '{"cmd":"pnpm build"}', status: "running" });

    expect(segmentTool({ type: "tool", tool: "edit", state: { status: "completed", input: "Form.tsx" } })).toEqual({
      name: "edit",
      arg: "Form.tsx",
      status: "done",
    });
  });

  it("pending 归进行中；未知/缺字段归已执行并给空兜底", () => {
    expect(segmentTool({ tool: "read", state: { status: "pending" } }).status).toBe("running");
    expect(segmentTool({ tool: "read", state: { status: "error" } }).status).toBe("done");
    expect(segmentTool({})).toEqual({ name: "", arg: "", status: "done" });
  });
});

describe("segmentPatch（patch 摘要）", () => {
  it("显式 added/removed/summary 直取（prototype 形状）", () => {
    expect(
      segmentPatch({ file: "Form.tsx", added: 148, removed: 12, summary: "新增预约表单" }),
    ).toEqual({ path: "Form.tsx", added: 148, removed: 12, summary: "新增预约表单" });
  });

  it("path/diff/edits 形状：edits 计数退化增删行", () => {
    expect(
      segmentPatch({
        path: "src/a.ts",
        edits: [{ type: "add" }, { type: "add" }, { type: "remove" }],
      }),
    ).toEqual({ path: "src/a.ts", added: 2, removed: 1, summary: "" });
  });

  it("edits 带 lines：头部计数按行数非组数（与行级 diff 一致）", () => {
    expect(
      segmentPatch({
        path: "src/a.ts",
        edits: [
          { type: "add", lines: ["x", "y"] },
          { type: "remove", lines: ["z"] },
        ],
      }),
    ).toEqual({ path: "src/a.ts", added: 2, removed: 1, summary: "" });
  });

  it("统一 diff 字符串：无显式计数时从头/增删行派生", () => {
    expect(segmentPatch({ path: "a.ts", diff: "+x\n+y\n-z\n context" })).toEqual({
      path: "a.ts",
      added: 2,
      removed: 1,
      summary: "",
    });
  });

  it("缺字段全兜底：空 path / 0 / 空 summary", () => {
    expect(segmentPatch({})).toEqual({ path: "", added: 0, removed: 0, summary: "" });
    expect(segmentPatch(null)).toEqual({ path: "", added: 0, removed: 0, summary: "" });
  });
});

describe("segmentPatchDiff（patch 行级 diff）", () => {
  it("统一 diff 字符串：+/− 染色、hunk 头/文件头剥掉、上下文保留", () => {
    const diff = [
      "diff --git a/Form.tsx b/Form.tsx",
      "--- a/Form.tsx",
      "+++ b/Form.tsx",
      "@@ -1,3 +1,4 @@",
      " export function BookingForm() {",
      "-  // TODO: 校验",
      "+  const phoneOk = /^1\\d{10}$/.test(phone)",
      "   return <form onSubmit={submit}>",
    ].join("\n");

    expect(segmentPatchDiff({ diff })).toEqual([
      { kind: "context", text: "export function BookingForm() {" },
      { kind: "remove", text: "  // TODO: 校验" },
      { kind: "add", text: "  const phoneOk = /^1\\d{10}$/.test(phone)" },
      { kind: "context", text: "  return <form onSubmit={submit}>" },
    ]);
  });

  it("行数组（prototype DIFF_LINES 形态 {t, s}）", () => {
    expect(
      segmentPatchDiff({
        lines: [
          { t: " ", s: "a" },
          { t: "-", s: "b" },
          { t: "+", s: "c" },
        ],
      }),
    ).toEqual([
      { kind: "context", text: "a" },
      { kind: "remove", text: "b" },
      { kind: "add", text: "c" },
    ]);
  });

  it("edits 数组：edit.type 决定整组行归属", () => {
    expect(
      segmentPatchDiff({
        edits: [
          { type: "add", lines: ["a1", "a2"] },
          { type: "remove", lines: ["r1"] },
        ],
      }),
    ).toEqual([
      { kind: "add", text: "a1" },
      { kind: "add", text: "a2" },
      { kind: "remove", text: "r1" },
    ]);
  });

  it("缺字段 / 未知形状 → 空行", () => {
    expect(segmentPatchDiff({})).toEqual([]);
    expect(segmentPatchDiff(null)).toEqual([]);
    expect(segmentPatchDiff({ diff: 123 })).toEqual([]);
  });
});

describe("segmentStep（step 边界步骤名）", () => {
  it("title / name / step / label 依序取首个命中", () => {
    expect(segmentStep({ title: "实现表单" })).toBe("实现表单");
    expect(segmentStep({ name: "跑测试" })).toBe("跑测试");
    expect(segmentStep({ step: "提交" })).toBe("提交");
  });

  it("缺字段 / 非对象 → 空串", () => {
    expect(segmentStep({})).toBe("");
    expect(segmentStep(null)).toBe("");
    expect(segmentStep("裸字符串")).toBe("");
  });
});
