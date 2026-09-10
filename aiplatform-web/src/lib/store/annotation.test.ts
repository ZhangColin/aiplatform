import { beforeEach, describe, expect, it } from "vitest";

import { useAnnotationStore } from "./annotation";

/**
 * 圈注条目 store（#97 圈注 B 档，#135 撤评语改）：按项目累积「发送前」的圈注条目
 * （预览回传锚 → 发送框附件区，chip 为序号 + 类型 + 摘要），发送前可删（remove）、
 * 发送即清（clear）。
 */
describe("圈注条目 store", () => {
  beforeEach(() => {
    useAnnotationStore.setState({ annotations: {} });
  });

  const draft = {
    kind: "select" as const,
    anchor: { selector: "button.a", text: "甲" },
    note: "",
  };

  it("add：预览回传锚入附件区，带本地 id、按项目键控", () => {
    useAnnotationStore.getState().add("p1", draft);
    useAnnotationStore.getState().add("p2", { ...draft, kind: "circle" });

    const p1 = useAnnotationStore.getState().annotations.p1;
    expect(p1).toHaveLength(1);
    expect(p1[0]).toMatchObject(draft);
    expect(p1[0].id).toMatch(/^an\d+$/);
    expect(useAnnotationStore.getState().annotations.p2).toHaveLength(1);
  });

  it("remove：发送前删除一条（按 id）", () => {
    useAnnotationStore.getState().add("p1", draft);
    const id = useAnnotationStore.getState().annotations.p1[0].id;

    useAnnotationStore.getState().remove("p1", id);

    expect(useAnnotationStore.getState().annotations.p1).toHaveLength(0);
  });

  it("clear：发送即清（附件随消息发出不滞留）", () => {
    useAnnotationStore.getState().add("p1", draft);
    useAnnotationStore.getState().add("p1", { ...draft, kind: "comment" });

    useAnnotationStore.getState().clear("p1");

    expect(useAnnotationStore.getState().annotations.p1).toEqual([]);
  });
});
