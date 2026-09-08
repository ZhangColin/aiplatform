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

import { systemPanelPhase, UPDATING_NOTICE, type SystemPanelPhase } from "./state";

/**
 * 预览渐进 b 档验收（#90 spec ⑤ → #104/#106 修订刷新单元口径：切片收口取代逐修改
 * 刷新）：脚本化生成/更新轨道——事件序列喂桥，每拍从 store 归约呈现档位
 * （systemPanelPhase），断言验收标准的端到端归约：
 *
 * - AC1 生成轨道（多切片）：步骤占位（随解说推进）→ 阶段 0 收口上页面（探活取得
 *   URL）→ 切片逐段收口（run-finish 纪元重挂）——run 起跑后全程无空屏档；
 * - AC2 更新 run：预览保持可正常呈现（有 URL 不退占位）——呈现始终是直挂的
 *   真页面，无遮罩无替换；
 * - AC3 刷新节奏单一：刷新只由切片收口（run-finish 纪元）驱动——无逐修改
 *   双重刷新；
 * - AC4 收口定格：run-finish → 纪元 +1 重挂定格最新态，进行中轻提示退场。
 *
 * URL 属 REST 查询面（状态以查询为准，探活通过才返回），本 seam 以查询事实注入；
 * URL 事件驱动推送（#105 preview-ready 写缓存）归 bridge.test.ts 覆盖。
 */

const GENERATED_AT = "2026-09-01T00:00:00Z";
const PREVIEW_URL = "http://localhost:30080";

/** 项目 p1 的当前呈现档位（coderStatus/parts 取 store 实况，URL/生成事实作查询面注入）。 */
function phaseNow(input: { url?: string; generatedAt?: string | null } = {}): SystemPanelPhase {
  return systemPanelPhase({
    coderStatus: coderStatusOf(useGenerationStore.getState(), "p1"),
    generatedAt: input.generatedAt,
    url: input.url,
    parts: workPartsOf(useWorkMessageStore.getState(), "p1"),
  });
}

/** 项目 p1 的当前预览重挂纪元（selector 读法，与 coderStatus/parts 同轨）。 */
function epochNow(): number {
  return previewEpochOf(useGenerationStore.getState(), "p1");
}

/** 呈现档位是否「有内容的档」（非空屏：idle 引导占位只在 run 起跑前出现）。 */
function hasContent(phase: SystemPanelPhase): boolean {
  if (phase.kind === "hint") return phase.text.length > 0;
  return phase.kind === "page" || phase.kind === "connecting";
}

describe("预览渐进 b 档（#90 → #104/#106 切片收口口径）· 脚本化 run 全程", () => {
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

  it("生成轨道（多切片）：占位随解说推进 → 阶段 0 收口上页面 → 切片收口重挂定格，全程无空屏（AC1/3/4）", () => {
    const seen: SystemPanelPhase[] = [];
    const watch = (phase: SystemPanelPhase) => {
      seen.push(phase);
      return phase;
    };

    const stage0 = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };
    const slice1 = { ...stage0, runId: "run2" };

    // 阶段 0 run 起跑：应用可访问前 = 进行中的步骤提示占位（无信号落「正在初始化」）
    dispatchAgentEvent(queryClient, agentEvent("run-start", { ...stage0, prompt: "做个花店官网", model: "m", agent: "executor" }));
    expect(watch(phaseNow())).toEqual({ kind: "hint", text: "正在初始化" });

    // 步骤占位随工作消息部件推进：解说自述到场即换（「正在创建首页」）
    dispatchAgentEvent(queryClient, agentEvent("part-text", { ...stage0, text: "正在创建首页。" }));
    expect(watch(phaseNow())).toEqual({ kind: "hint", text: "正在创建首页。" });

    // 阶段 0 收口：8081 起服探活通过——REST 探活取得 URL（查询事实）上真页面；
    // run-finish 纪元 +1（刷新单元 = 切片收口，唯一刷新信号——无逐修改双重刷新）
    dispatchAgentEvent(queryClient, agentEvent("run-finish", {
      ...stage0,
      finish: "end",
      closing: { summary: "系统骨架已起服", prdChanged: false, systemChanged: true, files: [], durationMs: 60_000 },
    }));
    expect(epochNow()).toBe(1);
    expect(watch(phaseNow({ url: PREVIEW_URL }))).toEqual({ kind: "page" });

    // 切片 1 run 起跑：页面位不动（有 URL 不退占位——保持可正常呈现），轻提示接棒
    dispatchAgentEvent(queryClient, agentEvent("run-start", { ...slice1, prompt: "完成首页与留言板", model: "m", agent: "executor" }));
    dispatchAgentEvent(queryClient, agentEvent("part-text", { ...slice1, text: "正在实现留言板。" }));
    expect(watch(phaseNow({ url: PREVIEW_URL }))).toEqual({
      kind: "page",
      notice: { failed: false, text: UPDATING_NOTICE },
    });
    // run 中无重挂（刷新只由切片收口驱动）：纪元停在上一收口
    expect(epochNow()).toBe(1);

    // 切片 1 收口：纪元 +1 重挂定格最新态，进行中轻提示退场；工作消息定格留驻（#117 不清空）
    dispatchAgentEvent(queryClient, agentEvent("run-finish", {
      ...slice1,
      finish: "end",
      closing: {
        summary: "完成首页与留言板",
        prdChanged: false,
        systemChanged: true,
        files: [{ path: "src/pages/Home.tsx", added: 80, removed: 0 }],
        durationMs: 120_000,
      },
    }));
    expect(epochNow()).toBe(2);
    expect(watch(phaseNow({ url: PREVIEW_URL, generatedAt: GENERATED_AT }))).toEqual({ kind: "page" });
    expect(workPartsOf(useWorkMessageStore.getState(), "p1")).toHaveLength(1); // 定格留驻（#117 不清空）

    // AC1 无空屏：起跑后每一拍都有内容档（hint 文案恒非空 / page 直挂）
    expect(seen.every(hasContent)).toBe(true);
  });

  it("更新 run：预览保持可正常呈现（不退占位），刷新无档位倒退，收口定格（AC2）", () => {
    // 查询事实：已有生成事实且预览可访问（更新 run 的起手式）
    const facts = { url: PREVIEW_URL, generatedAt: GENERATED_AT };
    const coder = { projectId: "p1", runId: "run2", sessionId: "coder-p1", engine: "agentscope" };

    // 更新 run 起跑：页面位不动（不退步骤占位——保持可正常呈现），轻提示接棒
    dispatchAgentEvent(queryClient, agentEvent("run-start", { ...coder, prompt: "把主色调改成绿色", model: "m", agent: "executor" }));
    expect(phaseNow(facts)).toEqual({
      kind: "page",
      notice: { failed: false, text: UPDATING_NOTICE },
    });

    // run 中页面档保持不倒退（直挂的真页面不被遮罩/替换）；刷新只由收口驱动——
    // run 中纪元不推进
    dispatchAgentEvent(queryClient, agentEvent("part-text", { ...coder, text: "正在调整全站配色。" }));
    expect(epochNow()).toBe(0);
    expect(phaseNow(facts).kind).toBe("page");

    // 收口定格：纪元 +1 定格最新态，轻提示退场
    dispatchAgentEvent(queryClient, agentEvent("run-finish", {
      ...coder,
      finish: "end",
      closing: {
        summary: "主色调已改为绿色",
        prdChanged: false,
        systemChanged: true,
        files: [{ path: "src/styles/theme.css", added: 6, removed: 6 }],
        durationMs: 60_000,
      },
    }));
    expect(epochNow()).toBe(1);
    expect(phaseNow(facts)).toEqual({ kind: "page" });
  });
});
