import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { dispatchAgentEvent, dispatchNotificationEvent } from "@/lib/sse/bridge";
import type { SseEvent } from "@/lib/sse/connection";
import { useChatStore } from "@/lib/store/chat";
import {
  coderStatusOf,
  PREVIEW_REFRESH_MIN_INTERVAL_MS,
  previewEpochOf,
  useGenerationStore,
} from "@/lib/store/generation";
import { useWorkMessageStore, workPartsOf } from "@/lib/store/work-message";

import { systemPanelPhase, UPDATING_NOTICE, type SystemPanelPhase } from "./state";

/**
 * 预览渐进 b 档验收（#90，spec ⑤）：脚本化 run 全程——事件序列喂桥，每拍
 * 从 store 归约呈现档位（systemPanelPhase），断言四条验收标准的端到端归约：
 *
 * - AC1 生成 run：步骤占位（随解说推进）→ 首跑可用（REST 探活得 URL 上页面）→
 *   逐步显现（preview-updated 驱动纪元重挂），run 起跑后全程无空屏档；
 * - AC2 更新 run：预览保持可正常呈现（有 URL 不退占位）——本 seam 可归约的
 *   最强断言 = 刷新全程页面档不倒退（呈现始终是直挂的真页面，无遮罩无替换；
 *   「偶发中间态报错如实呈现」由 iframe 直挂机制承载，组件层 system-panel
 *   测试覆盖渲染面）；
 * - AC3 刷新节奏统一：preview-updated 走既有纪元节流（窗内合并不闪烁），
 *   收口与逐修改信号共一套纪元机制（无双重刷新）；
 * - AC4 收口定格：run-finish → 纪元 +1 重挂定格最新态，进行中轻提示退场。
 *
 * 事件序列与服务端脚本化用例同源（StepBoundaryPreviewRefreshTest：part-step
 * step≥2 且平台侧探活通过才发 preview-updated——探活过 ⟺ 应用可访问，故序列里
 * 通知与「REST 探活取得 URL」同拍后到达）。URL 属 REST 查询面（状态以查询为
 * 准，探活通过才返回），本 seam 以查询事实注入，不在事件流内。
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

describe("预览渐进 b 档（#90）· 脚本化 run 全程", () => {
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
    vi.restoreAllMocks();
  });

  /** 智能体事件工厂：id 仅作去重锚（中性前缀，run 身份由 payload.runId 携带）。 */
  function agentEvent(type: string, payload: Record<string, unknown>): SseEvent {
    eventSeq += 1;
    return {
      id: `e${eventSeq}`,
      data: JSON.stringify({ type, payload, ts: "2026-09-05T06:00:00Z" }),
    };
  }

  function previewUpdated(): SseEvent {
    eventSeq += 1;
    return {
      id: `n${eventSeq}`,
      data: JSON.stringify({ type: "preview-updated", payload: { projectId: "p1" }, ts: "" }),
    };
  }

  it("生成 run：步骤占位随解说推进 → 探活通过上页面 → 逐修改显现 → 收口定格，全程无空屏（AC1/3/4）", () => {
    const now = vi.spyOn(Date, "now");
    const base = 10_000;
    now.mockReturnValue(base);
    const seen: SystemPanelPhase[] = [];
    const watch = (phase: SystemPanelPhase) => {
      seen.push(phase);
      return phase;
    };

    const coder = { projectId: "p1", runId: "run1", sessionId: "coder-p1", engine: "agentscope" };

    // 起跑：应用可访问前 = 进行中的步骤提示占位（无信号落「正在初始化」）
    dispatchAgentEvent(queryClient, agentEvent("run-start", { ...coder, prompt: "做个花店官网", model: "m", agent: "executor" }));
    expect(watch(phaseNow())).toEqual({ kind: "hint", text: "正在初始化" });

    // 步骤占位随工作消息部件推进：解说自述到场即换（「正在创建首页」）；
    // 起跑边界（step=1）与动作行不换提示（自述压动作——提示不随逐文件动作跳变）
    dispatchAgentEvent(queryClient, agentEvent("part-step", { ...coder, step: 1 }));
    dispatchAgentEvent(queryClient, agentEvent("part-text", { ...coder, text: "正在创建首页。" }));
    expect(watch(phaseNow())).toEqual({ kind: "hint", text: "正在创建首页。" });
    dispatchAgentEvent(
      queryClient,
      agentEvent("part-action", { ...coder, toolCallId: "tc-1", toolName: "write_file", state: "running", label: "编写【首页】" }),
    );
    expect(watch(phaseNow())).toEqual({ kind: "hint", text: "正在创建首页。" });

    // 首跑可用：step=2 边界平台侧探活通过（⟺ 应用可访问）——REST 探活取得
    // URL（查询事实）上真页面；同拍 preview-updated 到达计首针纪元
    dispatchAgentEvent(queryClient, agentEvent("part-step", { ...coder, step: 2 }));
    dispatchNotificationEvent(queryClient, previewUpdated());
    expect(watch(phaseNow({ url: PREVIEW_URL }))).toEqual({
      kind: "page",
      notice: { failed: false, text: UPDATING_NOTICE },
    });
    expect(epochNow()).toBe(1);

    // 逐步显现：后续步骤边界的刷新通知——节流窗内合并不闪烁（纪元不重复计、
    // 页面档保持不闪断），出窗后再计；呈现始终是直挂的真页面（中间态如实呈现）
    dispatchAgentEvent(queryClient, agentEvent("part-step", { ...coder, step: 3 }));
    now.mockReturnValue(base + PREVIEW_REFRESH_MIN_INTERVAL_MS - 1000);
    dispatchNotificationEvent(queryClient, previewUpdated());
    expect(epochNow()).toBe(1);
    expect(watch(phaseNow({ url: PREVIEW_URL })).kind).toBe("page");

    now.mockReturnValue(base + PREVIEW_REFRESH_MIN_INTERVAL_MS);
    dispatchNotificationEvent(queryClient, previewUpdated());
    expect(epochNow()).toBe(2);
    expect(watch(phaseNow({ url: PREVIEW_URL })).kind).toBe("page");

    // 收口定格：run-finish（携 closing）→ 纪元 +1 重挂定格最新态（与逐修改
    // 同一纪元机制，无双重刷新），进行中轻提示退场；过程部件随收尾卡凝聚清空
    dispatchAgentEvent(
      queryClient,
      agentEvent("run-finish", {
        ...coder,
        finish: "end",
        closing: {
          summary: "花店官网初版已生成",
          prdChanged: false,
          systemChanged: true,
          files: [{ path: "src/pages/Home.tsx", added: 80, removed: 0 }],
          durationMs: 120_000,
        },
      }),
    );
    expect(epochNow()).toBe(3);
    expect(watch(phaseNow({ url: PREVIEW_URL, generatedAt: GENERATED_AT }))).toEqual({ kind: "page" });
    expect(workPartsOf(useWorkMessageStore.getState(), "p1")).toHaveLength(0);

    // AC1 无空屏：起跑后每一拍都有内容档（hint 文案恒非空 / page 直挂）
    expect(seen.every(hasContent)).toBe(true);
  });

  it("更新 run：预览保持可正常呈现（不退占位），刷新无档位倒退，收口定格（AC2）", () => {
    const now = vi.spyOn(Date, "now");
    now.mockReturnValue(50_000);
    // 查询事实：已有生成事实且预览可访问（更新 run 的起手式）
    const facts = { url: PREVIEW_URL, generatedAt: GENERATED_AT };
    const coder = { projectId: "p1", runId: "run2", sessionId: "coder-p1", engine: "agentscope" };

    // 更新 run 起跑：页面位不动（不退步骤占位——保持可正常呈现），轻提示接棒
    dispatchAgentEvent(queryClient, agentEvent("run-start", { ...coder, prompt: "把主色调改成绿色", model: "m", agent: "executor" }));
    expect(phaseNow(facts)).toEqual({
      kind: "page",
      notice: { failed: false, text: UPDATING_NOTICE },
    });

    // 逐修改刷新：页面档保持不倒退（直挂的真页面不被遮罩/替换），纪元推进
    dispatchAgentEvent(queryClient, agentEvent("part-text", { ...coder, text: "正在调整全站配色。" }));
    dispatchAgentEvent(queryClient, agentEvent("part-step", { ...coder, step: 2 }));
    dispatchNotificationEvent(queryClient, previewUpdated());
    expect(epochNow()).toBe(1);
    expect(phaseNow(facts).kind).toBe("page");

    // 收口定格：纪元 +1 定格最新态，轻提示退场
    dispatchAgentEvent(
      queryClient,
      agentEvent("run-finish", {
        ...coder,
        finish: "end",
        closing: {
          summary: "主色调已改为绿色",
          prdChanged: false,
          systemChanged: true,
          files: [{ path: "src/styles/theme.css", added: 6, removed: 6 }],
          durationMs: 60_000,
        },
      }),
    );
    expect(epochNow()).toBe(2);
    expect(phaseNow(facts)).toEqual({ kind: "page" });
  });
});
