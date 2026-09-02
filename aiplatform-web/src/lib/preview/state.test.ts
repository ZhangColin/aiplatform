import { describe, expect, it } from "vitest";

import { ApiError } from "@/lib/api/api-error";
import type { LiveSegment } from "@/lib/store/live";

import {
  FALLBACK_RETRY_MESSAGE,
  UPDATING_NOTICE,
  isPreviewNotServing,
  liveHintOf,
  previewActive,
  systemPanelPhase,
} from "./state";

/** 探活未就绪的后端错误（WSP_012，503）。 */
function notServingError() {
  return new ApiError({ status: 503, code: "WSP_012", message: "预览应用尚未就绪" });
}

const seg = {
  text: (id: string, text: string): LiveSegment => ({ kind: "text", id, text }),
  action: (id: string, action: string): LiveSegment => ({ kind: "action", id, action }),
  step: (id: string, step: number): LiveSegment => ({ kind: "step", id, step }),
};

describe("previewActive · 门禁解除（#45）", () => {
  it("run 开始（乐观登记 running）即启动——不等收口纪元", () => {
    expect(previewActive("running", null)).toBe(true);
  });

  it("重试/收口/终态同样在机制内（有 URL 即上页面）", () => {
    expect(previewActive("retrying", null)).toBe(true);
    expect(previewActive("finished", null)).toBe(true);
    expect(previewActive("error", null)).toBe(true);
  });

  it("未见 run 且未生成过 = 不启动（idle 引导占位）", () => {
    expect(previewActive(undefined, null)).toBe(false);
    expect(previewActive(undefined, undefined)).toBe(false);
  });

  it("跨会话：REST 事实 generatedAt 单独即可启动", () => {
    expect(previewActive(undefined, "2026-09-01T08:00:00Z")).toBe(true);
  });
});

describe("liveHintOf · 占位步骤提示信号（自述优先、动作摘要兜底）", () => {
  it("无信号 = undefined（调用侧落初始文案）", () => {
    expect(liveHintOf([])).toBeUndefined();
  });

  it("取最新自述段（多段取末段）", () => {
    const segments = [
      seg.text("t1", "正在初始化项目"),
      seg.text("t2", "正在创建首页"),
    ];
    expect(liveHintOf(segments)).toBe("正在创建首页");
  });

  it("无自述时取最新动作摘要兜底", () => {
    const segments = [
      seg.action("a1", "正在编写【app.js】"),
      seg.action("a2", "正在编写【index.html】"),
    ];
    expect(liveHintOf(segments)).toBe("正在编写【index.html】");
  });

  it("自述优先于更晚的动作（提示停在解说口径，不随文件动作跳变）", () => {
    const segments = [
      seg.text("t1", "正在创建首页"),
      seg.action("a1", "正在编写【index.html】"),
      seg.action("a2", "正在编写【style.css】"),
    ];
    expect(liveHintOf(segments)).toBe("正在创建首页");
  });

  it("步骤分隔段不参与（非用户语言）", () => {
    const segments = [seg.text("t1", "正在创建首页"), seg.step("s1", 2)];
    expect(liveHintOf(segments)).toBe("正在创建首页");
    expect(liveHintOf([seg.step("s1", 1)])).toBeUndefined();
  });
});

describe("systemPanelPhase · 空态两档 + 页面档（#45）", () => {
  it("idle：未见 run 未生成 = 引导占位", () => {
    expect(systemPanelPhase({ coderStatus: undefined, generatedAt: null, liveSegments: [] })).toEqual({
      kind: "idle",
    });
  });

  // ---------- 第一档：无应用，占位随直播事件推进 ----------

  it("running 且无应用：无信号落「正在初始化」", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: null,
      liveSegments: [],
    });
    expect(phase).toEqual({ kind: "hint", text: "正在初始化" });
  });

  it("running 且无应用：直播自述推进占位文案", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: null,
      liveSegments: [seg.text("t1", "正在创建首页")],
    });
    expect(phase).toEqual({ kind: "hint", text: "正在创建首页" });
  });

  it("running 且无应用（修正轮，系统曾在）：无信号落「正在更新系统」", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: "2026-09-01T08:00:00Z",
      liveSegments: [],
    });
    expect(phase).toEqual({ kind: "hint", text: "正在更新系统" });
  });

  it("retrying 且无应用：播重试话术（帧内正本，缺省回落本地字面量）", () => {
    expect(
      systemPanelPhase({ coderStatus: "retrying", generatedAt: null, liveSegments: [] }),
    ).toEqual({ kind: "hint", text: FALLBACK_RETRY_MESSAGE });
    expect(
      systemPanelPhase({
        coderStatus: "retrying",
        generatedAt: null,
        liveSegments: [],
        retryMessage: "服务波动，正在恢复",
      }),
    ).toEqual({ kind: "hint", text: "服务波动，正在恢复" });
  });

  it("超限终态且从未生成：问题提示 + 重新发起", () => {
    const phase = systemPanelPhase({
      coderStatus: "error",
      generatedAt: null,
      liveSegments: [],
    });
    expect(phase).toEqual({ kind: "failed", text: "生成遇到了问题", recovery: "restart" });
  });

  it("超限终态且已生成（修正失败、应用探不到）：修正口径 + 重新修改入口，无重新发起", () => {
    const phase = systemPanelPhase({
      coderStatus: "error",
      generatedAt: "2026-09-01T08:00:00Z",
      liveSegments: [],
    });
    expect(phase).toEqual({ kind: "failed", text: "修正遇到了问题", recovery: "refix" });
  });

  it("正常态无任何手动触发：run 中/重试中/收口后均不带恢复入口", () => {
    // 正常流程全自动——恢复入口只在超限终态出现（#48）
    expect(systemPanelPhase({ coderStatus: "running", generatedAt: null, liveSegments: [] }))
      .toEqual({ kind: "hint", text: "正在初始化" });
    expect(
      systemPanelPhase({ coderStatus: "running", generatedAt: "2026-09-01T08:00:00Z", liveSegments: [] }),
    ).toEqual({ kind: "hint", text: "正在更新系统" });
    expect(
      systemPanelPhase({ coderStatus: "retrying", generatedAt: "2026-09-01T08:00:00Z", liveSegments: [] }),
    ).toEqual({ kind: "hint", text: FALLBACK_RETRY_MESSAGE });
    expect(
      systemPanelPhase({
        coderStatus: "finished",
        generatedAt: "2026-09-01T08:00:00Z",
        url: "http://localhost:42659",
        liveSegments: [],
      }),
    ).toEqual({ kind: "page", notice: undefined });
  });

  // ---------- 第二档：应用可访问（有 URL 即探活通过），页面 + 一套轻提示 ----------

  it("页面 + running：统一「更新中」轻提示（生成长出与修正同一套，不两套并存）", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: null,
      url: "http://localhost:42659",
      liveSegments: [],
    });
    expect(phase).toEqual({ kind: "page", notice: { failed: false, text: UPDATING_NOTICE } });
    // 合并后的唯一话术（旧修正专用文案不再另立一套）
    expect(UPDATING_NOTICE).toBe("正在更新系统，完成后自动刷新");
  });

  it("页面 + retrying：轻提示播重试话术，页面不退占位（重试不闪断）", () => {
    const phase = systemPanelPhase({
      coderStatus: "retrying",
      generatedAt: null,
      url: "http://localhost:42659",
      liveSegments: [],
      retryMessage: "服务波动，正在恢复",
    });
    expect(phase).toEqual({
      kind: "page",
      notice: { failed: false, text: "服务波动，正在恢复" },
    });
  });

  it("页面 + 超限终态：失败轻提示；从未生成带重新发起、修正轮带重新修改入口", () => {
    expect(
      systemPanelPhase({
        coderStatus: "error",
        generatedAt: null,
        url: "http://localhost:42659",
        liveSegments: [],
      }),
    ).toEqual({
      kind: "page",
      notice: { failed: true, text: "生成遇到了问题", recovery: "restart" },
    });
    expect(
      systemPanelPhase({
        coderStatus: "error",
        generatedAt: "2026-09-01T08:00:00Z",
        url: "http://localhost:42659",
        liveSegments: [],
      }),
    ).toEqual({
      kind: "page",
      notice: { failed: true, text: "修正遇到了问题", recovery: "refix" },
    });
  });

  it("页面 + 无进行中 run：无轻提示", () => {
    const phase = systemPanelPhase({
      coderStatus: "finished",
      generatedAt: "2026-09-01T08:00:00Z",
      url: "http://localhost:42659",
      liveSegments: [],
    });
    expect(phase).toEqual({ kind: "page", notice: undefined });
  });

  // ---------- 跨会话与接通 ----------

  it("跨会话就绪：generatedAt + URL 直接显示系统现状（无占位过渡）", () => {
    const phase = systemPanelPhase({
      coderStatus: undefined,
      generatedAt: "2026-09-01T08:00:00Z",
      url: "http://localhost:42659",
      liveSegments: [],
    });
    expect(phase.kind).toBe("page");
  });

  it("已生成但 URL 未到：接通中（WSP_012 未就绪同接通中，非故障）", () => {
    expect(
      systemPanelPhase({
        coderStatus: undefined,
        generatedAt: "2026-09-01T08:00:00Z",
        error: notServingError(),
        liveSegments: [],
      }),
    ).toEqual({ kind: "connecting", trouble: false });
    expect(
      systemPanelPhase({
        coderStatus: undefined,
        generatedAt: "2026-09-01T08:00:00Z",
        liveSegments: [],
      }),
    ).toEqual({ kind: "connecting", trouble: false });
  });

  it("已生成但预览真故障（非 WSP_012）：trouble 口径", () => {
    const phase = systemPanelPhase({
      coderStatus: undefined,
      generatedAt: "2026-09-01T08:00:00Z",
      error: new ApiError({ status: 500, code: "WSP_002", message: "环境后端操作失败" }),
      liveSegments: [],
    });
    expect(phase).toEqual({ kind: "connecting", trouble: true });
  });
});

describe("isPreviewNotServing · 探活未就绪判定", () => {
  it("WSP_012 = 未就绪（视同待期继续轮询）", () => {
    expect(isPreviewNotServing(notServingError())).toBe(true);
  });

  it("其他 ApiError 与网络错误不是未就绪", () => {
    expect(
      isPreviewNotServing(new ApiError({ status: 500, code: "WSP_002", message: "x" })),
    ).toBe(false);
    expect(isPreviewNotServing(new Error("network"))).toBe(false);
    expect(isPreviewNotServing(undefined)).toBe(false);
  });
});

describe("用户可见文案遵循「生成」词条 Avoid（不出现开发/构建）", () => {
  it("平台自有占位与提示话术全部合规", () => {
    const cases: Parameters<typeof systemPanelPhase>[0][] = [
      { coderStatus: undefined, generatedAt: null, liveSegments: [] },
      { coderStatus: "running", generatedAt: null, liveSegments: [] },
      { coderStatus: "running", generatedAt: "2026-09-01T08:00:00Z", liveSegments: [] },
      { coderStatus: "retrying", generatedAt: null, liveSegments: [] },
      { coderStatus: "retrying", generatedAt: "2026-09-01T08:00:00Z", liveSegments: [] },
      { coderStatus: "error", generatedAt: null, liveSegments: [] },
      { coderStatus: "error", generatedAt: "2026-09-01T08:00:00Z", liveSegments: [] },
      { coderStatus: "running", generatedAt: null, url: "http://localhost:42659", liveSegments: [] },
      { coderStatus: "running", generatedAt: "2026-09-01T08:00:00Z", url: "http://x", liveSegments: [] },
      { coderStatus: "retrying", generatedAt: null, url: "http://x", liveSegments: [] },
      { coderStatus: "error", generatedAt: null, url: "http://x", liveSegments: [] },
      { coderStatus: "error", generatedAt: "2026-09-01T08:00:00Z", url: "http://x", liveSegments: [] },
    ];
    for (const input of cases) {
      const phase = systemPanelPhase(input);
      const texts =
        phase.kind === "hint" || phase.kind === "failed"
          ? [phase.text]
          : phase.kind === "page" && phase.notice
            ? [phase.notice.text]
            : [];
      for (const text of texts) {
        expect(text).not.toContain("开发");
        expect(text).not.toContain("构建");
      }
    }
  });
});
