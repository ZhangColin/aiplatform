import { beforeEach, describe, expect, it } from "vitest";

import { useGenerationStore } from "./generation";

/**
 * 生成面 store「重试中 vs 终态」判定（#56 story 12 收严）：终态只有一个写入口
 * noteCoderFailed（run-failed 轨道终态收口帧）——error 帧是逐次尝试的过程事实，
 * 在 store 无写入口（编译期即不可误判），重试间隔内「重新修改」出口零闪现。
 * 先例：状态条 store 纯逻辑测试（lib/chat/dispatch-stage.test.ts）。
 */

/** 帧面走一遍的助手：登记 → 起跑（最小可判定前置）。 */
function givenCoderRunStarted(projectId: string, runId: string) {
  const store = useGenerationStore.getState();
  store.noteCoderRun(projectId, runId);
  store.noteCoderRunStart(projectId);
}

beforeEach(() => {
  useGenerationStore.setState({ generations: {} });
});

describe("generation store · 重试中 vs 终态判定（#56）", () => {
  it("重试链全程无终态：running → retrying（记帧内话术）→ 重试起跑回 running", () => {
    givenCoderRunStarted("p1", "run1");
    const store = useGenerationStore.getState();

    store.noteCoderRetrying("p1", "遇到问题，正在重试");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("retrying");
    expect(useGenerationStore.getState().generations["p1"]?.retryMessage).toBe("遇到问题，正在重试");

    // 下一尝试（新 runId）起跑：状态回进行中——同一场生成在途
    store.noteCoderRun("p1", "run2");
    store.noteCoderRunStart("p1");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
  });

  it("终态只经 noteCoderFailed（run-failed 帧）：状态 → error", () => {
    givenCoderRunStarted("p1", "run1");
    useGenerationStore.getState().noteCoderFailed("p1");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("error");
  });

  it("终态后恢复出口重派起跑：新 run 登记即回 running（重派链路状态复位）", () => {
    givenCoderRunStarted("p1", "run1");
    const store = useGenerationStore.getState();
    store.noteCoderFailed("p1");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("error");

    store.noteCoderRun("p1", "run-restart");
    store.noteCoderRunStart("p1");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
  });

  it("收口：run-finish 置 finished + 预览纪元 +1，重放（同事件 id）不重复计", () => {
    givenCoderRunStarted("p1", "run1");
    const store = useGenerationStore.getState();

    store.noteCoderFinish("p1", "run1:9");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("finished");
    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);

    store.noteCoderFinish("p1", "run1:9");
    expect(useGenerationStore.getState().generations["p1"]?.previewEpoch).toBe(1);
  });
});
