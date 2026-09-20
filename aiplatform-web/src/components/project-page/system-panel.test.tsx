import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { GenerationState } from "@/lib/projects/detail";
import { useGenerationStore } from "@/lib/store/generation";
import type { CoderRunStatus } from "@/lib/store/generation";
import type { WorkPart } from "@/lib/store/work-message";

import { SystemPanel, previewFrameKey } from "./system-panel";

// 系统模式主区域（#45 渐进预览第一片 + #48 修正超限终态恢复出口；#222 档位改吃
// 四态投影）：非「从未生成」即取预览地址；空态两档——无应用随工作消息部件推进
// 步骤提示（#81 自解说自述优先、动作兜底），有应用保留页面 + 「更新中」轻提示
// 一套；跨会话/重试不闪断；恢复出口单出口——生成中断（含刷新后，投影派生）与
// 从未生成（idle 档）给「继续生成」、修正轮失败给「重新修改」；无推倒重来按钮，
// 正常态全无。预览地址读口 mock 掉（每用例摆 url 有无与 error）。
let previewResult: {
  data?: { url: string };
  error?: unknown;
  isPending: boolean;
  isError: boolean;
} = { isPending: false, isError: false };

vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: (_projectId: string, active: boolean) =>
    active
      ? previewResult
      : { data: undefined, error: undefined, isPending: false, isError: false },
}));

vi.mock("@/hooks/use-resume-generation", () => ({
  useResumeGeneration: () => ({ isPending: false, mutate: vi.fn() }),
}));

vi.mock("@/hooks/use-restart-fix", () => ({
  useRestartFix: () => ({ isPending: false, mutate: vi.fn() }),
}));

// 工作消息部件读口换直摆对象（zustand SSR 快照冻在建店时刻，setState 后渲染读
// 不到——同 previewFrameKey 测试注释的约束；workPartsOf 留真实现走真实推导）
const seededWorks: Record<string, { runId: string; parts: WorkPart[] }> = {};
vi.mock("@/lib/store/work-message", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/work-message")>();
  return {
    ...actual,
    useWorkMessageStore: (selector: (state: { works: typeof seededWorks }) => unknown) =>
      selector({ works: seededWorks }),
  };
});

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

function renderPanel({
  generationState,
  coderStatus,
  epoch = 0,
  parts = [],
  url,
  error,
}: {
  generationState?: GenerationState;
  coderStatus?: CoderRunStatus;
  epoch?: number;
  parts?: WorkPart[];
  url?: string;
  error?: unknown;
}) {
  useGenerationStore.setState({
    generations: { p1: { coderRunIds: [], coderStatus, previewEpoch: epoch, seenFinishEventIds: [] } },
  });
  if (parts.length) {
    seededWorks.p1 = { runId: "run-1", parts };
  } else {
    delete seededWorks.p1;
  }
  previewResult = { data: url ? { url } : undefined, error, isPending: false, isError: error != null };
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <SystemPanel
        projectId="p1"
        generationState={generationState}
        coderStatus={coderStatus}
        onGenerated={() => {}}
      />
    </QueryClientProvider>,
  );
}

describe("SystemPanel · 系统模式主区域（#45 门禁解除 + #222 四态投影档位）", () => {
  beforeEach(() => {
    useGenerationStore.setState({ generations: {} });
    for (const key of Object.keys(seededWorks)) delete seededWorks[key];
    previewResult = { isPending: false, isError: false };
  });

  it("从未生成（idle）：空白浏览器窗 + 引导占位 +「继续生成」出口（存量/未起跑恢复），无 iframe", () => {
    const html = renderPanel({ generationState: "never" });

    expect(html).toContain("你的系统");
    expect(html).toContain("系统生成后，这里会出现可以操作的你的系统");
    expect(html).toContain("继续生成");
    expect(html).not.toContain("<iframe");
    expect(html).not.toContain("正在接通系统");
  });

  // ---------- 第一档：无应用，占位随工作消息部件推进 ----------

  it("生成中且无应用：初始「正在初始化」，无 iframe、无文件列表", () => {
    const html = renderPanel({ generationState: "generating" });

    expect(html).toContain("正在初始化");
    expect(html).not.toContain("<iframe");
    // 门禁解除：投影非从未生成即在接通（地址栏口径），预览机制已启动
    expect(html).toContain("正在接通系统…");
  });

  it("生成中且无应用：解说自述推进占位（最新自述优先于更晚的动作行）", () => {
    const html = renderPanel({
      generationState: "generating",
      parts: [
        seg.text("t1", "正在创建首页"),
        seg.action("a1", "编写【index.html】"),
      ],
    });

    expect(html).toContain("正在创建首页");
    expect(html).not.toContain("编写【index.html】");
  });

  it("生成中且无应用：无自述时动作对象兜底", () => {
    const html = renderPanel({
      generationState: "generating",
      parts: [seg.action("a1", "编写【index.html】")],
    });

    expect(html).toContain("编写【index.html】");
  });

  // ---------- 生成中断（#222 单出口「继续生成」，刷新后仍在——投影派生） ----------

  it("生成中断（投影，无会话信号——刷新/回访面）：中断提示 + 继续生成入口，无旧词残留", () => {
    const html = renderPanel({ generationState: "interrupted" });

    expect(html).toContain("生成中断了");
    expect(html).toContain("已完成的进度都保留");
    expect(html).toContain("继续生成");
    expect(html).not.toContain("重新发起");
    expect(html).not.toContain("推倒重来");
    expect(html).not.toContain("<iframe");
  });

  it("会话内 run 失败（error 信号，投影未刷新）：同中断口径先行呈现", () => {
    const html = renderPanel({ generationState: "generating", coderStatus: "error" });

    expect(html).toContain("生成中断了");
    expect(html).toContain("继续生成");
  });

  // ---------- 第二档：应用可访问（探活通过出 URL），页面 + 一套轻提示 ----------

  it("应用可访问且生成中（投影）：真页面 + 统一「更新中」轻提示（刷新后无会话信号也如实呈现）", () => {
    const html = renderPanel({
      generationState: "generating",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).toContain("正在更新系统");
    expect(html).toContain("完成后自动刷新");
    expect(html).not.toContain("正在按您的意见修改系统");
  });

  it("应用可访问且生成中断：细条带「继续生成」，页面保留（部分切片已收口）", () => {
    const html = renderPanel({
      generationState: "interrupted",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain("生成中断了");
    expect(html).toContain("继续生成");
    expect(html).not.toContain("重新修改");
  });

  it("修正轮失败（已生成 + error）：修正口径 + 重新修改入口（更新轨现状不动），无继续生成", () => {
    const html = renderPanel({
      generationState: "generated",
      coderStatus: "error",
    });

    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("重新修改");
    expect(html).not.toContain("继续生成");
    expect(html).not.toContain("<iframe");
  });

  it("应用可访问且修正失败：轻提示转失败 + 重新修改入口，页面仍可见", () => {
    const html = renderPanel({
      generationState: "generated",
      coderStatus: "error",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("重新修改");
    expect(html).not.toContain("继续生成");
  });

  it("正常态无任何手动触发：生成中 / 已生成均无恢复入口", () => {
    // 正常流程全自动（#48/#222：恢复入口只在终态/中断档出现）
    const updating = renderPanel({
      generationState: "generated",
      coderStatus: "running",
      url: "http://localhost:42659",
    });
    expect(updating).toContain("正在更新系统");
    expect(updating).not.toContain("继续生成");
    expect(updating).not.toContain("重新修改");

    const finished = renderPanel({
      generationState: "generated",
      url: "http://localhost:42659",
    });
    expect(finished).not.toContain("继续生成");
    expect(finished).not.toContain("重新修改");
  });

  it("run 收口后：轻提示消失，预览照常", () => {
    const html = renderPanel({
      generationState: "generated",
      coderStatus: "finished",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).not.toContain("正在更新系统");
  });

  it("跨会话回来（已生成投影 + 应用在）：直接显示系统现状", () => {
    const html = renderPanel({
      generationState: "generated",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).not.toContain("正在初始化");
  });

  it("已生成但 URL 未到：接通中等待，非故障不打扰", () => {
    const html = renderPanel({ generationState: "generated" });

    expect(html).toContain("正在接通系统…");
    expect(html).not.toContain("预览暂时打不开");
    expect(html).not.toContain("<iframe");
  });

  it("已生成但预览真故障（非未就绪）：打不开口径，稍后自动重试", () => {
    const html = renderPanel({
      generationState: "generated",
      error: new Error("network down"),
    });

    expect(html).toContain("预览暂时打不开，稍后会自动重试");
  });

  it("预览重挂 key：run 收口纪元变化即换 key（同 URL 也强制重建 iframe）", () => {
    // epoch → key 的推导是纯函数（SSR 读不到 setState 后的 store 快照，纪元流
    // 归 bridge.test 断言）；同纪元同 key、新收口新 key
    expect(previewFrameKey("http://localhost:42659", 0)).toBe(
      previewFrameKey("http://localhost:42659", 0),
    );
    expect(previewFrameKey("http://localhost:42659", 1)).not.toBe(
      previewFrameKey("http://localhost:42659", 0),
    );
    expect(previewFrameKey("http://localhost:42659", 2)).not.toBe(
      previewFrameKey("http://localhost:42659", 1),
    );
  });

  // ---------- #80 浏览器条 / 浅色锁定 / 工具条形态位 ----------

  it("浏览器条三件就位（页面在时）：手动刷新、桌面/手机切换、新窗口打开；地址框出真地址", () => {
    const html = renderPanel({
      generationState: "generating",
      url: "http://localhost:42659",
    });

    for (const label of ["刷新预览", "桌面预览", "手机预览", "在新窗口打开预览"]) {
      expect(html).toContain(`aria-label="${label}"`);
    }
    // 地址框出真地址（诚实口径，不演装饰域名）：输入框 value + iframe src 两处
    expect(html.match(/http:\/\/localhost:42659/g)).toHaveLength(2);
  });

  it("地址框可聚焦编辑（#125）：页面在时为可编辑 text input，回显真地址", () => {
    const html = renderPanel({
      generationState: "generating",
      url: "http://localhost:42659",
    });

    const inputTag = html.match(/<input[^>]*aria-label="预览地址"[^>]*>/)![0];
    expect(inputTag).toContain('type="text"');
    expect(inputTag).toContain('value="http://localhost:42659"');
    expect(inputTag).not.toContain("readonly");
    expect(inputTag).not.toContain('disabled=""');
  });

  it("无页面时刷新与新窗口不可点、地址框不可编辑、工具条不出场（可点击的仅真实现的能力）", () => {
    const html = renderPanel({ generationState: "generating" });

    for (const label of ["刷新预览", "在新窗口打开预览"]) {
      const tag = html.match(new RegExp(`<button[^>]*aria-label="${label}"[^>]*>`))![0];
      expect(tag).toContain("disabled");
    }
    // 无应用 origin 可解析 → 地址框禁用（不可聚焦编辑）
    const inputTag = html.match(/<input[^>]*aria-label="预览地址"[^>]*>/)![0];
    expect(inputTag).toContain('disabled=""');
    expect(html).not.toContain("待启用");
  });

  it("舞台浅色锁定：内容区挂 light-lock（用户产物不随平台 Light/Dark 翻转）", () => {
    const html = renderPanel({
      generationState: "generating",
      url: "http://localhost:42659",
    });
    expect(html).toContain("light-lock");
  });

  it("页面在时工具条出场：两键启用（选择/圈选），改字/评论置灰键不在场（词条备案的未来增强）", () => {
    const html = renderPanel({
      generationState: "generating",
      url: "http://localhost:42659",
    });

    // 选择/圈选可点进标注态；改字/评论形态位已撤——能力留词条备案，键不在场
    for (const label of ["选择", "圈选"]) {
      const tag = html.match(new RegExp(`<button[^>]*aria-label="${label}"[^>]*>`))![0];
      expect(tag).not.toContain("disabled");
    }
    expect(html).not.toContain("改字（待启用）");
    expect(html).not.toContain("评论（待启用）");
    // 「画笔圈选」旧 label 消亡（词条入 Avoid）
    expect(html).not.toContain("画笔圈选");
    // 正常预览态出「圈一下」提示（非常驻标注态入口）
    expect(html).toContain("圈一下");
  });
});

/** 取包裹某段文案最近的一个 div/span 开标签（断言提示画在非悬浮元素里，#124）。 */
function wrapperOpenTag(html: string, text: string): string {
  const before = html.slice(0, html.indexOf(text));
  const tags = before.match(/<(div|span)\b[^>]*>/g) ?? [];
  return tags[tags.length - 1] ?? "";
}

describe("SystemPanel · #124 更新提示移出遮挡", () => {
  it("run 中页面可见：「更新中」内联在浏览器条，不再悬浮叠预览", () => {
    const html = renderPanel({
      generationState: "generating",
      url: "http://localhost:42659",
    });

    expect(html).toContain("正在更新系统，完成后自动刷新");
    // 画在浏览器条内联：提示在地址胶囊之前（旧悬浮遮罩在地址之后的内容区）
    expect(html.indexOf("正在更新系统，完成后自动刷新")).toBeLessThan(
      html.indexOf("http://localhost:42659"),
    );
    // 非悬浮：提示元素不是 absolute 定位（旧：absolute inset-x-0 top-0 遮罩）
    expect(wrapperOpenTag(html, "正在更新系统，完成后自动刷新")).not.toContain("absolute");
  });

  it("页面可见且生成中断：失败提示为非悬浮顶部占位细条（占自己高度、下推预览）", () => {
    const html = renderPanel({
      generationState: "interrupted",
      url: "http://localhost:42659",
    });

    expect(html).toContain("生成中断了");
    expect(html).toContain("继续生成");
    // 非悬浮：失败细条不是 absolute 定位（旧：absolute 遮罩叠在预览上）
    expect(wrapperOpenTag(html, "生成中断了")).not.toContain("absolute");
  });
});
