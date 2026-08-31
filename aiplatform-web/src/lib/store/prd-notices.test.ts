import { beforeEach, describe, expect, it } from "vitest";

import { usePrdNoticesStore } from "./prd-notices";

// PRD 更新提示 store（#20 修订回路）：document-updated(PRD) 的载荷展示例外
// （ADR 0003 修订：REST 重查拿不到「这次写入是不是修订」的语义）。
// 首次产出只登记 seen（成果区长出本身即信号）；此后写入置 pending（胶囊 +
// 「已更新」标记的显隐源）；「去看看」认领清 pending。按项目键控。
describe("prd-notices store", () => {
  beforeEach(() => {
    usePrdNoticesStore.setState({ seen: {}, pending: {} });
  });

  it("首次写入：登记 seen、不出胶囊（长成果区本身即信号）", () => {
    const store = usePrdNoticesStore.getState();

    store.notePrdWritten("p1");

    expect(usePrdNoticesStore.getState().seen.p1).toBe(true);
    expect(usePrdNoticesStore.getState().pending.p1).toBeUndefined();
  });

  it("已见后的写入 = 修订：置 pending（到达时点）；再修订刷新时点", () => {
    const store = usePrdNoticesStore.getState();
    store.notePrdWritten("p1", 1_000);
    store.notePrdWritten("p1", 2_000);

    expect(usePrdNoticesStore.getState().pending.p1).toBe(2_000);
  });

  it("「去看看」认领：清 pending、seen 保留（后续修订仍出胶囊）", () => {
    const store = usePrdNoticesStore.getState();
    store.notePrdWritten("p1", 1_000);
    store.notePrdWritten("p1", 2_000);

    store.acknowledge("p1");

    expect(usePrdNoticesStore.getState().pending.p1).toBeUndefined();
    expect(usePrdNoticesStore.getState().seen.p1).toBe(true);

    store.notePrdWritten("p1", 3_000);
    expect(usePrdNoticesStore.getState().pending.p1).toBe(3_000);
  });

  it("挂载兜底登记（页面加载已产出项目）：等同首次，不打胶囊", () => {
    usePrdNoticesStore.getState().markSeen("p2");

    expect(usePrdNoticesStore.getState().seen.p2).toBe(true);
    expect(usePrdNoticesStore.getState().pending.p2).toBeUndefined();
  });

  it("按项目键控：p1 的认领不惊动 p2 的修订", () => {
    const store = usePrdNoticesStore.getState();
    store.notePrdWritten("p1", 1_000);
    store.notePrdWritten("p2", 1_000);
    store.notePrdWritten("p2", 2_000);
    usePrdNoticesStore.getState().acknowledge("p1");

    expect(usePrdNoticesStore.getState().pending.p2).toBe(2_000);
  });
});
