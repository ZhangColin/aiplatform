import { beforeEach, describe, expect, it } from "vitest";

import { useProjectNoticesStore } from "./project-notices";

function reset() {
  useProjectNoticesStore.setState({ notices: {}, order: [] });
}

describe("project-notices store · 载荷展示白名单（桥唯一事件写入方）", () => {
  beforeEach(reset);

  it("setRejection 写入驳回理由，同项目后到覆盖", () => {
    const store = useProjectNoticesStore.getState();
    store.setRejection("p1", { stageLabel: "原型", reason: "配色太深" });
    store.setRejection("p1", { stageLabel: "原型", reason: "导航改顶部" });

    expect(useProjectNoticesStore.getState().notices["p1"].rejection).toEqual({
      stageLabel: "原型",
      reason: "导航改顶部",
    });
  });

  it("clearRejection 只清驳回位（再次拍板通过 / 用户关闭横幅），不动预览标记", () => {
    const store = useProjectNoticesStore.getState();
    store.setRejection("p1", { stageLabel: "验收", reason: "首页图片没换" });
    store.markPreviewUpdate("p1");
    store.clearRejection("p1");

    const notice = useProjectNoticesStore.getState().notices["p1"];
    expect(notice.rejection).toBeUndefined();
    expect(notice.previewUpdate).toBe(true);
  });

  it("clearRejection 对不存在的项目为无操作", () => {
    expect(() => useProjectNoticesStore.getState().clearRejection("nope")).not.toThrow();
  });

  it("markPreviewUpdate 置「有更新」位；ackPreviewUpdate 确认后清除", () => {
    const store = useProjectNoticesStore.getState();
    store.markPreviewUpdate("p1");
    expect(useProjectNoticesStore.getState().notices["p1"].previewUpdate).toBe(true);

    useProjectNoticesStore.getState().ackPreviewUpdate("p1");
    // 空通知连键撤掉，store 不留空壳
    expect(useProjectNoticesStore.getState().notices["p1"]?.previewUpdate).toBeUndefined();
  });

  it("markDocumentUpdate 置「PRD 有更新」位；ackDocumentUpdate 确认后连键撤掉（#54）", () => {
    const store = useProjectNoticesStore.getState();
    store.markDocumentUpdate("p1");
    expect(useProjectNoticesStore.getState().notices["p1"].documentUpdate).toBe(true);

    useProjectNoticesStore.getState().ackDocumentUpdate("p1");
    expect(useProjectNoticesStore.getState().notices["p1"]).toBeUndefined();
  });

  it("markDocumentUpdate 只动文档位，不动预览/驳回位", () => {
    const store = useProjectNoticesStore.getState();
    store.setRejection("p1", { stageLabel: "验收", reason: "首页图片没换" });
    store.markPreviewUpdate("p1");
    store.markDocumentUpdate("p1");

    const notice = useProjectNoticesStore.getState().notices["p1"];
    expect(notice.rejection?.reason).toBe("首页图片没换");
    expect(notice.previewUpdate).toBe(true);
    expect(notice.documentUpdate).toBe(true);
  });

  it("驱逐：项目数超软上限时最旧的整条让位（内存有界）", () => {
    const store = useProjectNoticesStore.getState();
    for (let i = 0; i < 21; i++) {
      store.setRejection(`p${i}`, { stageLabel: "s", reason: `r${i}` });
    }
    const state = useProjectNoticesStore.getState();
    expect(Object.keys(state.notices)).toHaveLength(20);
    expect(state.notices["p0"]).toBeUndefined();
    expect(state.notices["p20"].rejection).toEqual({ stageLabel: "s", reason: "r20" });
  });
});
