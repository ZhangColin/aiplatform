import { describe, expect, it } from "vitest";

import { ApiError } from "@/lib/api/api-error";
import type { WorkPart } from "@/lib/store/work-message";

import {
  UPDATING_NOTICE,
  isPreviewNotServing,
  previewTrouble,
  workHintOf,
  previewActive,
  resolvePreviewAddress,
  systemPanelPhase,
} from "./state";

/** 探活未就绪的后端错误（WSP_012，503）。 */
function notServingError() {
  return new ApiError({ status: 503, code: "WSP_012", message: "预览应用尚未就绪" });
}

const seg = {
  text: (id: string, text: string): WorkPart => ({ kind: "text", id, text }),
  action: (id: string, action: string): WorkPart => ({
    kind: "action",
    id,
    toolCallId: id,
    toolName: "write_file",
    state: "completed",
    label: action,
  }),
};

describe("previewActive · 门禁解除（#45）", () => {
  it("run 开始（乐观登记 running）即启动——不等收口纪元", () => {
    expect(previewActive("running", null)).toBe(true);
  });

  it("收口/终态同样在机制内（有 URL 即上页面）", () => {
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

describe("workHintOf · 占位步骤提示信号（解说自述优先、动作对象兜底）", () => {
  it("无信号 = undefined（调用侧落初始文案）", () => {
    expect(workHintOf([])).toBeUndefined();
  });

  it("取最新自述段（多段取末段）", () => {
    const segments = [
      seg.text("t1", "正在初始化项目"),
      seg.text("t2", "正在创建首页"),
    ];
    expect(workHintOf(segments)).toBe("正在创建首页");
  });

  it("无自述时取最新动作摘要兜底", () => {
    const segments = [
      seg.action("a1", "编写【app.js】"),
      seg.action("a2", "编写【index.html】"),
    ];
    expect(workHintOf(segments)).toBe("编写【index.html】");
  });

  it("自述优先于更晚的动作（提示停在解说口径，不随文件动作跳变）", () => {
    const segments = [
      seg.text("t1", "正在创建首页"),
      seg.action("a1", "编写【index.html】"),
      seg.action("a2", "编写【style.css】"),
    ];
    expect(workHintOf(segments)).toBe("正在创建首页");
  });
});

describe("systemPanelPhase · 空态两档 + 页面档（#45）", () => {
  it("idle：未见 run 未生成 = 引导占位", () => {
    expect(systemPanelPhase({ coderStatus: undefined, generatedAt: null, parts: [] })).toEqual({
      kind: "idle",
    });
  });

  // ---------- 第一档：无应用，占位随工作消息部件推进 ----------

  it("running 且无应用：无信号落「正在初始化」", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: null,
      parts: [],
    });
    expect(phase).toEqual({ kind: "hint", text: "正在初始化" });
  });

  it("running 且无应用：解说自述推进占位文案", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: null,
      parts: [seg.text("t1", "正在创建首页")],
    });
    expect(phase).toEqual({ kind: "hint", text: "正在创建首页" });
  });

  it("running 且无应用（修正轮，系统曾在）：无信号落「正在更新系统」", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: "2026-09-01T08:00:00Z",
      parts: [],
    });
    expect(phase).toEqual({ kind: "hint", text: "正在更新系统" });
  });

  it("超限终态且从未生成：问题提示 + 重新发起", () => {
    const phase = systemPanelPhase({
      coderStatus: "error",
      generatedAt: null,
      parts: [],
    });
    expect(phase).toEqual({ kind: "failed", text: "生成遇到了问题", recovery: "restart" });
  });

  it("超限终态且已生成（修正失败、应用探不到）：修正口径 + 重新修改入口，无重新发起", () => {
    const phase = systemPanelPhase({
      coderStatus: "error",
      generatedAt: "2026-09-01T08:00:00Z",
      parts: [],
    });
    expect(phase).toEqual({ kind: "failed", text: "修正遇到了问题", recovery: "refix" });
  });

  it("正常态无任何手动触发：run 中/收口后均不带恢复入口", () => {
    // 正常流程全自动——恢复入口只在超限终态出现（#48）
    expect(systemPanelPhase({ coderStatus: "running", generatedAt: null, parts: [] }))
      .toEqual({ kind: "hint", text: "正在初始化" });
    expect(
      systemPanelPhase({ coderStatus: "running", generatedAt: "2026-09-01T08:00:00Z", parts: [] }),
    ).toEqual({ kind: "hint", text: "正在更新系统" });
    expect(
      systemPanelPhase({
        coderStatus: "finished",
        generatedAt: "2026-09-01T08:00:00Z",
        url: "http://localhost:42659",
        parts: [],
      }),
    ).toEqual({ kind: "page", notice: undefined });
  });

  // ---------- 第二档：应用可访问（有 URL 即探活通过），页面 + 一套轻提示 ----------

  it("页面 + running：统一「更新中」轻提示（生成长出与修正同一套，不两套并存）", () => {
    const phase = systemPanelPhase({
      coderStatus: "running",
      generatedAt: null,
      url: "http://localhost:42659",
      parts: [],
    });
    expect(phase).toEqual({ kind: "page", notice: { failed: false, text: UPDATING_NOTICE } });
    // 合并后的唯一话术（旧修正专用文案不再另立一套）
    expect(UPDATING_NOTICE).toBe("正在更新系统，完成后自动刷新");
  });

  it("页面 + 超限终态：失败轻提示；从未生成带重新发起、修正轮带重新修改入口", () => {
    expect(
      systemPanelPhase({
        coderStatus: "error",
        generatedAt: null,
        url: "http://localhost:42659",
        parts: [],
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
        parts: [],
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
      parts: [],
    });
    expect(phase).toEqual({ kind: "page", notice: undefined });
  });

  // ---------- 跨会话与接通 ----------

  it("跨会话就绪：generatedAt + URL 直接显示系统现状（无占位过渡）", () => {
    const phase = systemPanelPhase({
      coderStatus: undefined,
      generatedAt: "2026-09-01T08:00:00Z",
      url: "http://localhost:42659",
      parts: [],
    });
    expect(phase.kind).toBe("page");
  });

  it("已生成但 URL 未到：接通中（WSP_012 未就绪同接通中，非故障）", () => {
    expect(
      systemPanelPhase({
        coderStatus: undefined,
        generatedAt: "2026-09-01T08:00:00Z",
        error: notServingError(),
        parts: [],
      }),
    ).toEqual({ kind: "connecting", trouble: false });
    expect(
      systemPanelPhase({
        coderStatus: undefined,
        generatedAt: "2026-09-01T08:00:00Z",
        parts: [],
      }),
    ).toEqual({ kind: "connecting", trouble: false });
  });

  it("已生成但预览真故障（非 WSP_012）：trouble 口径", () => {
    const phase = systemPanelPhase({
      coderStatus: undefined,
      generatedAt: "2026-09-01T08:00:00Z",
      error: new ApiError({ status: 500, code: "WSP_002", message: "环境后端操作失败" }),
      parts: [],
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

describe("previewTrouble · 真故障判定（#80）", () => {
  it("无错与未就绪（WSP_012）不是真故障；其他错误才是", () => {
    expect(previewTrouble(undefined)).toBe(false);
    expect(previewTrouble(notServingError())).toBe(false);
    expect(previewTrouble(new ApiError({ status: 500, code: "WSP_002", message: "x" }))).toBe(true);
    expect(previewTrouble(new Error("network"))).toBe(true);
  });
});

describe("resolvePreviewAddress · 地址栏 goto 解析（#125）", () => {
  const base = "http://localhost:42659";

  it("路径拼接：绝对路径归到应用 origin 根", () => {
    expect(resolvePreviewAddress(base, "/login")).toBe("http://localhost:42659/login");
    expect(resolvePreviewAddress(base, "/")).toBe("http://localhost:42659/");
  });

  it("路径拼接：无前导斜杠的相对路径同样归到 origin 根，前后空白剔除", () => {
    expect(resolvePreviewAddress(base, "login")).toBe("http://localhost:42659/login");
    expect(resolvePreviewAddress(base, "  /admin  ")).toBe("http://localhost:42659/admin");
  });

  it("同源 URL：接受（保留原路径）", () => {
    expect(resolvePreviewAddress(base, "http://localhost:42659/foo/bar")).toBe(
      "http://localhost:42659/foo/bar",
    );
    expect(resolvePreviewAddress(base, "http://localhost:42659")).toBe("http://localhost:42659/");
  });

  it("跨源 URL：拒绝（返回 undefined，不跳出沙箱预览）", () => {
    expect(resolvePreviewAddress(base, "https://evil.com")).toBeUndefined();
    // 同主机异 scheme（http↔https）也是跨源
    expect(resolvePreviewAddress(base, "https://localhost:42659/foo")).toBeUndefined();
    // 协议相对（//host）会逃逸 origin——同样拒绝
    expect(resolvePreviewAddress(base, "//evil.com")).toBeUndefined();
  });

  it("空输入 / base 无有效层级 origin：拒绝（无可导航 origin）", () => {
    expect(resolvePreviewAddress(base, "")).toBeUndefined();
    expect(resolvePreviewAddress(base, "   ")).toBeUndefined();
    // about:blank 是不透明 origin（串 "null"），无 origin 可拼
    expect(resolvePreviewAddress("about:blank", "/login")).toBeUndefined();
  });
});

describe("用户可见文案遵循「生成」词条 Avoid（不出现开发/构建）", () => {
  it("平台自有占位与提示话术全部合规", () => {
    const cases: Parameters<typeof systemPanelPhase>[0][] = [
      { coderStatus: undefined, generatedAt: null, parts: [] },
      { coderStatus: "running", generatedAt: null, parts: [] },
      { coderStatus: "running", generatedAt: "2026-09-01T08:00:00Z", parts: [] },
      { coderStatus: "error", generatedAt: null, parts: [] },
      { coderStatus: "error", generatedAt: "2026-09-01T08:00:00Z", parts: [] },
      { coderStatus: "running", generatedAt: null, url: "http://localhost:42659", parts: [] },
      { coderStatus: "running", generatedAt: "2026-09-01T08:00:00Z", url: "http://x", parts: [] },
      { coderStatus: "error", generatedAt: null, url: "http://x", parts: [] },
      { coderStatus: "error", generatedAt: "2026-09-01T08:00:00Z", url: "http://x", parts: [] },
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
