import { beforeEach, describe, expect, it } from "vitest";

import {
  liveSegmentsOf,
  useLiveStore,
  type LiveSegment,
} from "@/lib/store/live";

// 直播面 store（#23 生成环②）：live-* 帧按 run 落段、新 run 重开、连续同文动作行
// 去重、总量软上限。桥为唯一写入方。

function segments(...items: LiveSegment[]): LiveSegment[] {
  return items;
}

const text = (id: string, body: string): LiveSegment => ({ kind: "text", id, text: body });
const action = (id: string, body: string): LiveSegment => ({ kind: "action", id, action: body });
const step = (id: string, n: number): LiveSegment => ({ kind: "step", id, step: n });

describe("live store · 直播面（#23）", () => {
  beforeEach(() => {
    useLiveStore.setState({ lives: {} });
  });

  it("直播段按到达序累积，text/action/step 各归其形", () => {
    const store = useLiveStore.getState();
    store.noteLiveSegment("p1", "run-1", step("e1", 1));
    store.noteLiveSegment("p1", "run-1", text("e2", "正在准备演示数据。"));
    store.noteLiveSegment("p1", "run-1", action("e3", "正在编写【订单管理】"));

    expect(liveSegmentsOf(useLiveStore.getState(), "p1")).toEqual(
      segments(step("e1", 1), text("e2", "正在准备演示数据。"), action("e3", "正在编写【订单管理】")),
    );
  });

  it("换 run 即重开（重试下一尝试/新一轮生成）：旧段不残留", () => {
    const store = useLiveStore.getState();
    store.noteLiveSegment("p1", "run-1", text("e1", "上一次尝试的解说。"));
    store.noteLiveSegment("p1", "run-2", text("e2", "重试起跑。"));

    expect(liveSegmentsOf(useLiveStore.getState(), "p1")).toEqual(
      segments(text("e2", "重试起跑。")),
    );
  });

  it("连续同文动作行去重（重复编写同一文件的噪音折叠）", () => {
    const store = useLiveStore.getState();
    store.noteLiveSegment("p1", "run-1", action("e1", "正在编写【订单管理】"));
    store.noteLiveSegment("p1", "run-1", action("e2", "正在编写【订单管理】"));
    store.noteLiveSegment("p1", "run-1", action("e3", "正在运行命令"));

    expect(liveSegmentsOf(useLiveStore.getState(), "p1")).toEqual(
      segments(action("e1", "正在编写【订单管理】"), action("e3", "正在运行命令")),
    );
  });

  it("总量软上限：超限丢最旧（重放缓冲 ~1000 帧，直播内存有界）", () => {
    const store = useLiveStore.getState();
    for (let i = 0; i < 320; i++) {
      store.noteLiveSegment("p1", "run-1", text(`e${i}`, `第${i}段。`));
    }

    const live = liveSegmentsOf(useLiveStore.getState(), "p1");
    expect(live).toHaveLength(300);
    expect(live[0]).toEqual(text("e20", "第20段。"));
    expect(live.at(-1)).toEqual(text("e319", "第319段。"));
  });

  it("项目互不串扰；未见直播的项目读空", () => {
    useLiveStore.getState().noteLiveSegment("p1", "run-1", text("e1", "一段。"));

    expect(liveSegmentsOf(useLiveStore.getState(), "p2")).toEqual([]);
    expect(liveSegmentsOf(useLiveStore.getState(), "p1")).toHaveLength(1);
  });
});
