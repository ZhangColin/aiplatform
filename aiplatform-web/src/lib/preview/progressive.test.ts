import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { dispatchAgentEvent } from "@/lib/sse/bridge";
import type { SseEvent } from "@/lib/sse/connection";
import { useChatStore } from "@/lib/store/chat";
import {
  coderStatusOf,
  previewEpochOf,
  useGenerationStore,
} from "@/lib/store/generation";
import { useWorkMessageStore, workPartsOf } from "@/lib/store/work-message";
import type { GenerationState } from "@/lib/projects/detail";

import { systemPanelPhase, UPDATING_NOTICE, type SystemPanelPhase } from "./state";

/**
 * 预览渐进 b 档验收（#90 spec ⑤ → #104/#106 修订刷新单元口径：run 收口取代逐修改
 * 刷新；#222 档位输入改生成态投影；#608 呈现面清场：占位为静态文案、不再随过程
 * 部件推进）：脚本化生成/更新 run——事件序列喂桥，每拍从 store 归约呈现档位
 * （systemPanelPhase），断言验收标准的端到端归约：
 *
 * - AC1 生成 run：静态占位 → 收口上页面（探活取得 URL）——run 起跑后全程无空屏档；
 * - AC2 更新 run：预览保持可正常呈现（有 URL 不退占位）——呈现始终是直挂的
 *   真页面，无遮罩无替换；
 * - AC3 刷新节奏单一：刷新只由 run 收口（run-finish 纪元）驱动——无逐修改双重刷新；
 * - AC4 收口定格：run-finish → 纪元 +1 重挂定格最新态，进行中轻提示退场。
 *
 * URL 与生成态投影属 REST 查询面（状态以查询为准，探活通过才返回），本 seam 以查询
 * 事实注入；URL 事件驱动推送（#105 preview-ready 写缓存）归 bridge.test.ts 覆盖。
 */

const PREVIEW_URL = "http://localhost:30080";

/** 项目 p1 的当前呈现档位（coderStatus 取 store 实况，URL/投影作查询面注入）。 */
function phaseNow(input: { url?: string; generationState?: GenerationState } = {}): SystemPanelPhase {
  return systemPanelPhase({
    generationState: input.generationState,
    coderStatus: coderStatusOf(useGenerationStore.getState(), "p1"),
    url: input.url,
  });
}

/** 项目 p1 的当前预览重挂纪元（selector 读法，与 coderStatus 同轨）。 */
function epochNow(): number {
  return previewEpochOf(useGenerationStore.getState(), "p1");
}

/** 呈现档位是否「有内容的档」（非空屏：idle 引导占位只在 run 起跑前出现）。 */
function hasContent(phase: SystemPanelPhase): boolean {
  if (phase.kind === "hint") return phase.text.length > 0;
  return phase.kind === "page" || phase.kind === "connecting";
}

describe("预览渐进 b 档（#90 → #104/#106 run 收口口径）· 脚本化 run 全程", () => {
  let queryClient: QueryClient;
  let eventSeq: number;

  beforeEach(() => {
    queryClient = new QueryClient();
    eventSeq = 0;
    useChatStore.setState({ chats: {} });
    useGenerationStore.setState({ generations: {} });
    useWorkMessageStore.setState({ works: {} });
  });

  afterEach(() => {
    queryClient.clear();
  });

  /** 智能体事件工厂：id 仅作去重锚（中性前缀，run 身份由 payload.runId 携带）。 */
  function agentEvent(type: string, payload: Record<string, unknown>): SseEvent {
    eventSeq += 1;
    return {
      id: `e${eventSeq}`,
      data: JSON.stringify({ type, payload, ts: "2026-09-05T06:00:00Z" }),
    };
  }

  it("生成 run：静态占位 → 收口上页面 → 纪元重挂定格，全程无空屏（AC1/3/4）", () => {
    const seen: SystemPanelPhase[] = [];
    const watch = (phase: SystemPanelPhase) => {
      seen.push(phase);
      return phase;
    };

    const run = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };

    // run 起跑：应用可访问前 = 静态占位（#608：不随过程部件推进）
    dispatchAgentEvent(queryClient, agentEvent("run-start", { ...run, prompt: "做个花店官网", model: "m", agent: "executor" }));
    expect(watch(phaseNow({ generationState: "generating" }))).toEqual({ kind: "hint", text: "正在初始化" });

    // 过程部件生长不影响占位文案
    dispatchAgentEvent(queryClient, agentEvent("part-action", {
      ...run, toolCallId: "tc-1", toolName: "write_file", state: "running", label: "编写【首页】",
    }));
    expect(watch(phaseNow({ generationState: "generating" }))).toEqual({ kind: "hint", text: "正在初始化" });

    // 收口：8081 起服探活通过——REST 探活取得 URL（查询事实）上真页面；
    // run-finish 纪元 +1（刷新单元 = run 收口，唯一刷新信号）
    dispatchAgentEvent(queryClient, agentEvent("run-finish", {
      ...run,
      finish: "end",
      closing: {
        narrative: "系统骨架已起服",
        criteria: { buildPassed: true, previewPassed: true },
        files: [],
        durationMs: 60_000,
      },
    }));
    expect(epochNow()).toBe(1);
    expect(watch(phaseNow({ url: PREVIEW_URL, generationState: "generated" }))).toEqual({ kind: "page" });
    expect(workPartsOf(useWorkMessageStore.getState(), "p1")).toHaveLength(1); // 定格留驻（#117 不清空）

    // AC1 无空屏：起跑后每一拍都有内容档（hint 文案恒非空 / page 直挂）
    expect(seen.every(hasContent)).toBe(true);
  });

  it("更新 run：预览保持可正常呈现（不退占位），收口定格（AC2）", () => {
    // 查询事实：已生成（投影）且预览可访问（更新 run 的起手式）
    const facts = { url: PREVIEW_URL, generationState: "generated" as const };
    const run = { projectId: "p1", runId: "run2", sessionId: "coder-p1", engine: "agentscope" };

    // 更新 run 起跑：页面位不动（不退占位——保持可正常呈现），轻提示接棒
    dispatchAgentEvent(queryClient, agentEvent("run-start", { ...run, prompt: "把主色调改成绿色", model: "m", agent: "executor" }));
    expect(phaseNow(facts)).toEqual({
      kind: "page",
      notice: { failed: false, text: UPDATING_NOTICE },
    });

    // run 中页面档保持不倒退（直挂的真页面不被遮罩/替换）；刷新只由收口驱动——
    // run 中纪元不推进
    dispatchAgentEvent(queryClient, agentEvent("part-action", {
      ...run, toolCallId: "tc-9", toolName: "edit_file", state: "completed", label: "修改【主题】",
    }));
    expect(epochNow()).toBe(0);
    expect(phaseNow(facts).kind).toBe("page");

    // 收口定格：纪元 +1 定格最新态，轻提示退场
    dispatchAgentEvent(queryClient, agentEvent("run-finish", {
      ...run,
      finish: "end",
      closing: {
        narrative: "主色调已改为绿色",
        criteria: { buildPassed: true, previewPassed: true },
        files: [{ path: "src/styles/theme.css", added: 6, removed: 6 }],
        durationMs: 60_000,
      },
    }));
    expect(epochNow()).toBe(1);
    expect(phaseNow(facts)).toEqual({ kind: "page" });
  });
});
