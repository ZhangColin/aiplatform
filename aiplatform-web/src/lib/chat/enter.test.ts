import { describe, expect, it } from "vitest";

import { isSubmitEnter } from "./enter";

describe("isSubmitEnter · 回车提交判定（#19）", () => {
  it("Enter 提交；Shift+Enter 换行不提交", () => {
    expect(isSubmitEnter({ key: "Enter", shiftKey: false })).toBe(true);
    expect(isSubmitEnter({ key: "Enter", shiftKey: true })).toBe(false);
    expect(isSubmitEnter({ key: "a", shiftKey: false })).toBe(false);
  });

  it("输入法组词期（isComposing）的 Enter 是选字不是提交", () => {
    expect(isSubmitEnter({ key: "Enter", shiftKey: false, nativeEvent: { isComposing: true } })).toBe(false);
    expect(isSubmitEnter({ key: "Enter", shiftKey: false, nativeEvent: { isComposing: false } })).toBe(true);
  });
});
