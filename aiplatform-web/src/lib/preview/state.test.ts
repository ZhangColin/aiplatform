import { describe, expect, it } from "vitest";

import { ApiError } from "@/lib/api/api-error";
import type { GenerationState } from "@/lib/projects/detail";
import type { WorkPart } from "@/lib/store/work-message";

import {
  UPDATING_NOTICE,
  INTERRUPTED_NOTICE,
  isPreviewNotServing,
  previewTrouble,
  workHintOf,
  previewActive,
  resolvePreviewAddress,
  systemPanelPhase,
} from "./state";

/** 探活未就绪的后端错误（WSP_012 → 数字业务码 1012，HTTP 503）。 */
function notServingError() {
  return new ApiError({ status: 503, code: 1012, message: "预览应用尚未就绪" });
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

describe("previewActive · 门禁解除（#45；#222 投影口径）", () => {
  it("四态投影非「从未生成」即启动（生成中/中断/已生成）", () => {
    expect(previewActive("generating", undefined)).toBe(true);
    // 中断也启动：阶段 0 收口后应用可能已在跑——有成果就该探得到
    expect(previewActive("interrupted", undefined)).toBe(true);
    expect(previewActive("generated", undefined)).toBe(true);
  });

  it("从未生成（无投影/明确 never）= 不启动（idle 引导占位）", () => {
    expect(previewActive(undefined, undefined)).toBe(false);
    expect(previewActive("never", undefined)).toBe(false);
  });

  it("更新轨会话信号在场即启动", () => {
    expect(previewActive("generated", "running")).toBe(true);
    expect(previewActive(undefined, "finished")).toBe(true);
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

describe("systemPanelPhase · 四态投影档位（#222：REST 投影派生，与 SSE 会话态无关）", () => {
  /** 断言生成面输入（projection + parts，无会话信号——刷新/回访后的纯投影面）。 */
  function phaseOf(generationState: GenerationState | undefined, extra: {
    url?: string;
    error?: unknown;
    parts?: WorkPart[];
  } = {}) {
    return systemPanelPhase({
      generationState,
      url: extra.url,
      error: extra.error,
      parts: extra.parts ?? [],
    });
  }

  it("从未生成：引导占位（idle）", () => {
    expect(phaseOf("never")).toEqual({ kind: "idle" });
    expect(phaseOf(undefined)).toEqual({ kind: "idle" });
  });

  // ---------- 第一档：无应用，生成中占位随工作消息部件推进 ----------

  it("生成中且无应用：无信号落「正在初始化」", () => {
    expect(phaseOf("generating")).toEqual({ kind: "hint", text: "正在初始化" });
  });

  it("生成中且无应用：解说自述推进占位文案", () => {
    expect(phaseOf("generating", { parts: [seg.text("t1", "正在创建首页")] })).toEqual({
      kind: "hint",
      text: "正在创建首页",
    });
  });

  it("更新在途（已生成 + 会话 running）且无应用：落「正在更新系统」", () => {
    expect(
      systemPanelPhase({ generationState: "generated", coderStatus: "running", parts: [] }),
    ).toEqual({ kind: "hint", text: "正在更新系统" });
  });

  it("idle 档点「继续生成」的乐观登记窗口（投影未刷成生成中 + running）：落「正在初始化」不说「更新」", () => {
    expect(
      systemPanelPhase({ generationState: "never", coderStatus: "running", parts: [] }),
    ).toEqual({ kind: "hint", text: "正在初始化" });
    expect(systemPanelPhase({ coderStatus: "running", parts: [] })).toEqual({
      kind: "hint",
      text: "正在初始化",
    });
  });

  // ---------- 生成中断（#222 单出口「继续生成」） ----------

  it("生成中断（投影）：中断提示 + 继续生成入口——刷新/回访后档位仍正确", () => {
    expect(phaseOf("interrupted")).toEqual({
      kind: "failed",
      text: INTERRUPTED_NOTICE,
      recovery: "resume",
    });
    expect(INTERRUPTED_NOTICE).toBe("生成中断了，已完成的进度都保留");
  });

  it("会话内 run 失败先行呈现（投影重拉收敛前）：未生成口径给继续生成、已生成给继续更新", () => {
    expect(
      systemPanelPhase({ generationState: "generating", coderStatus: "error", parts: [] }),
    ).toEqual({ kind: "failed", text: INTERRUPTED_NOTICE, recovery: "resume" });
    expect(
      systemPanelPhase({ generationState: "generated", coderStatus: "error", parts: [] }),
    ).toEqual({ kind: "failed", text: "更新遇到了问题", recovery: "restart-update" });
  });

  it("正常态无任何手动触发：生成中/已生成均不带恢复入口", () => {
    expect(phaseOf("generating")).toEqual({ kind: "hint", text: "正在初始化" });
    expect(phaseOf("generated", { url: "http://localhost:42659" })).toEqual({
      kind: "page",
      notice: undefined,
    });
  });

  // ---------- 第二档：应用可访问（有 URL 即探活通过），页面 + 一套轻提示 ----------

  it("页面 + 生成中（投影）：统一「更新中」轻提示——刷新后无会话信号也如实呈现", () => {
    expect(phaseOf("generating", { url: "http://localhost:42659" })).toEqual({
      kind: "page",
      notice: { failed: false, text: UPDATING_NOTICE },
    });
    // 会话信号同款（更新在途）
    expect(
      systemPanelPhase({
        generationState: "generated",
        coderStatus: "running",
        url: "http://localhost:42659",
        parts: [],
      }),
    ).toEqual({ kind: "page", notice: { failed: false, text: UPDATING_NOTICE } });
    expect(UPDATING_NOTICE).toBe("正在更新系统，完成后自动刷新");
  });

  it("页面 + 生成中断：细条带「继续生成」入口（部分切片已收口、应用在跑——页面保留）", () => {
    expect(phaseOf("interrupted", { url: "http://localhost:42659" })).toEqual({
      kind: "page",
      notice: { failed: true, text: INTERRUPTED_NOTICE, recovery: "resume" },
    });
  });

  it("页面 + 更新失败：更新失败口径 + 继续更新入口", () => {
    expect(
      systemPanelPhase({
        generationState: "generated",
        coderStatus: "error",
        url: "http://localhost:42659",
        parts: [],
      }),
    ).toEqual({
      kind: "page",
      notice: { failed: true, text: "更新遇到了问题", recovery: "restart-update" },
    });
  });

  it("页面 + 无进行中信号：无轻提示", () => {
    expect(phaseOf("generated", { url: "http://localhost:42659" })).toEqual({
      kind: "page",
      notice: undefined,
    });
  });

  // ---------- 跨会话与接通 ----------

  it("跨会话就绪：已生成 + URL 直接显示系统现状（无占位过渡）", () => {
    expect(phaseOf("generated", { url: "http://localhost:42659" }).kind).toBe("page");
  });

  it("已生成但 URL 未到：接通中（WSP_012 未就绪同接通中，非故障）", () => {
    expect(phaseOf("generated", { error: notServingError() })).toEqual({
      kind: "connecting",
      trouble: false,
    });
    expect(phaseOf("generated")).toEqual({ kind: "connecting", trouble: false });
  });

  it("已生成但预览真故障（非 WSP_012）：trouble 口径", () => {
    expect(
      phaseOf("generated", {
        error: new ApiError({ status: 500, code: 1002, message: "环境后端操作失败" }),
      }),
    ).toEqual({ kind: "connecting", trouble: true });
  });

  it("会话内已收口而投影未刷新：接通中平滑过渡，不闪回引导占位", () => {
    expect(
      systemPanelPhase({ generationState: "never", coderStatus: "finished", parts: [] }),
    ).toEqual({ kind: "connecting", trouble: false });
  });
});

describe("isPreviewNotServing · 探活未就绪判定", () => {
  it("WSP_012（数字业务码 1012）= 未就绪（视同待期继续轮询）", () => {
    expect(isPreviewNotServing(notServingError())).toBe(true);
  });

  it("WSP_013（数字业务码 1013，#170 唤醒待期）= 系统启动中（视同待期，非 trouble）", () => {
    expect(
      isPreviewNotServing(
        new ApiError({ status: 503, code: 1013, message: "系统启动中" }),
      ),
    ).toBe(true);
    // 启动中归 connecting（系统启动中），不进 trouble 打不开口径
    expect(
      systemPanelPhase({
        generationState: "generated",
        error: new ApiError({ status: 503, code: 1013, message: "系统启动中" }),
        parts: [],
      }),
    ).toEqual({ kind: "connecting", trouble: false });
  });

  // 回归（预览误报「暂时打不开」，#169）：信封 code 曾装 httpStatus（数字 503）、
  // 业务码不上线，字符串比对死分支把待期误判真故障。判定只认数字业务码——
  // httpStatus 形态（code=503）不算未就绪，避免语义混回传输层。
  it("信封退回 httpStatus 装码（code=503）不判未就绪——判定认业务码不认状态", () => {
    expect(
      isPreviewNotServing(new ApiError({ status: 503, code: 503, message: "预览应用尚未就绪" })),
    ).toBe(false);
  });

  it("其他 ApiError 与网络错误不是未就绪", () => {
    expect(
      isPreviewNotServing(new ApiError({ status: 500, code: 1002, message: "x" })),
    ).toBe(false);
    expect(isPreviewNotServing(new Error("network"))).toBe(false);
    expect(isPreviewNotServing(undefined)).toBe(false);
  });
});

describe("previewTrouble · 真故障判定（#80）", () => {
  it("无错与未就绪（WSP_012）不是真故障；其他错误才是", () => {
    expect(previewTrouble(undefined)).toBe(false);
    expect(previewTrouble(notServingError())).toBe(false);
    expect(previewTrouble(new ApiError({ status: 500, code: 1002, message: "x" }))).toBe(true);
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

describe("用户可见文案遵循词条 Avoid（「生成」不出现开发/构建；「迭代」不出现修正/重新修改）", () => {
  it("平台自有占位与提示话术全部合规", () => {
    const cases: Parameters<typeof systemPanelPhase>[0][] = [
      { generationState: undefined, parts: [] },
      { generationState: "never", parts: [] },
      { generationState: "generating", parts: [] },
      { generationState: "generated", coderStatus: "running", parts: [] },
      { generationState: "generating", coderStatus: "error", parts: [] },
      { generationState: "generated", coderStatus: "error", parts: [] },
      { generationState: "generating", url: "http://localhost:42659", parts: [] },
      { generationState: "interrupted", parts: [] },
      { generationState: "interrupted", url: "http://x", parts: [] },
      { generationState: "generated", coderStatus: "error", url: "http://x", parts: [] },
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
        expect(text).not.toContain("修正");
        expect(text).not.toContain("重新修改");
      }
    }
  });
});
