import { describe, expect, it } from "vitest";

import { useGenerationStore } from "./generation";

/**
 * 生成面 store「静默重试 vs 终态」判定（#56 → #82/#84 收紧）：终态只有一个写入口
 * noteCoderFailed（run-failed 轨道终态收口事件）——run 失败为唯一失败终态，重试
 * 全程静默（中间失败不出事件，store 无 error 中间态写入口——编译期即不可误判），
 * 重试间隔内「重新修改」出口零闪现。
 */

/** 事件面走一遍的助手：run-start 登记即起跑（最小可判定前置）。 */
function givenCoderRunStarted(projectId: string, runId: string) {
  useGenerationStore.getState().noteCoderRun(projectId, runId);
}

describe("generation store · 静默重试与终态判定（#56/#84）", () => {
  it("run-start 登记即 running：重试静默进行，无中间态（新尝试 runId 起跑仍 running）", () => {
    givenCoderRunStarted("p1", "run1");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");

    // 下一尝试（新 runId）静默起跑：状态保持进行中——同一场生成在途
    useGenerationStore.getState().noteCoderRun("p1", "run2");
    expect(useGenerationStore.getState().generations["p1"]?.coderStatus).toBe("running");
  });

  it("终态只经 noteCoderFailed（run-failed 事件）：状态 → error", () => {
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
