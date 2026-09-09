import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { CoderRunStatus } from "@/lib/store/generation";
import { useGenerationStore } from "@/lib/store/generation";
import type { WorkPart } from "@/lib/store/work-message";

import { SystemPanel, previewFrameKey } from "./system-panel";

// 系统模式主区域（#45 渐进预览第一片 + #48 修正超限终态恢复出口）：门禁解除——
// run 开始即取预览地址；空态两档——无应用随工作消息部件推进步骤提示（#81 自
// 解说自述优先、动作兜底），有应用保留页面 + 「更新中」轻提示一套；
// 跨会话/重试不闪断；超限终态
// 给人工兜底入口——从未生成「重新发起」、修正轮「重新修改」，正常态全无。
// 预览地址读口 mock 掉（每用例摆 url 有无与 error）。
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

vi.mock("@/hooks/use-generate", () => ({
  useGenerate: () => ({ isPending: false, mutate: vi.fn() }),
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
  generatedAt,
  coderStatus,
  epoch = 0,
  parts = [],
  url,
  error,
}: {
  generatedAt?: string | null;
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
        generatedAt={generatedAt}
        coderStatus={coderStatus}
        onGenerated={() => {}}
      />
    </QueryClientProvider>,
  );
}

describe("SystemPanel · 系统模式主区域（#45 门禁解除 + 空态两档）", () => {
  beforeEach(() => {
    useGenerationStore.setState({ generations: {} });
    for (const key of Object.keys(seededWorks)) delete seededWorks[key];
    previewResult = { isPending: false, isError: false };
  });

  it("未开始（idle）：空白浏览器窗 + 引导占位，无 iframe，门禁未开", () => {
    const html = renderPanel({});

    expect(html).toContain("你的系统");
    expect(html).toContain("系统生成后，这里会出现可以操作的你的系统");
    expect(html).not.toContain("<iframe");
    expect(html).not.toContain("正在接通系统");
  });

  // ---------- 第一档：无应用，占位随工作消息部件推进 ----------

  it("生成中且无应用：初始「正在初始化」，无 iframe、无文件列表", () => {
    const html = renderPanel({ coderStatus: "running" });

    expect(html).toContain("正在初始化");
    expect(html).not.toContain("<iframe");
    // 门禁解除：run 一开始就在接通（地址栏口径），预览机制已启动
    expect(html).toContain("正在接通系统…");
  });

  it("生成中且无应用：解说自述推进占位（最新自述优先于更晚的动作行）", () => {
    const html = renderPanel({
      coderStatus: "running",
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
      coderStatus: "running",
      parts: [seg.action("a1", "编写【index.html】")],
    });

    expect(html).toContain("编写【index.html】");
  });

  it("超限终态且未生成：问题提示 + 重新发起入口（人工兜底）", () => {
    const html = renderPanel({ coderStatus: "error" });

    expect(html).toContain("生成遇到了问题");
    expect(html).toContain("重新发起");
    expect(html).not.toContain("<iframe");
  });

  // ---------- 第二档：应用可访问（探活通过出 URL），页面 + 一套轻提示 ----------

  it("应用可访问且 run 中：真页面 + 统一「更新中」轻提示（生长期同修正期一套）", () => {
    const html = renderPanel({
      coderStatus: "running",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).toContain("正在更新系统");
    expect(html).toContain("完成后自动刷新");
    // 合并为一套：旧修正专用话术不再并存
    expect(html).not.toContain("正在按您的意见修改系统");
  });

  it("超限终态且已生成（修正失败、应用探不到）：修正口径 + 重新修改入口（人工兜底）", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "error",
    });

    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("重新修改");
    // 修正轮不给「重新发起」（系统已生成，重做的事是修正不是重做系统）
    expect(html).not.toContain("重新发起");
    expect(html).not.toContain("<iframe");
  });

  it("应用可访问且超限终态：轻提示转失败 + 重新修改入口，页面仍可见", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "error",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("重新修改");
    expect(html).not.toContain("重新发起");
  });

  it("正常态无任何手动触发：run 中 / 收口后既无重新发起也无重新修改", () => {
    // 正常流程全自动（#48：恢复入口只在超限终态出现）
    const updating = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "running",
      url: "http://localhost:42659",
    });
    expect(updating).toContain("正在更新系统");
    expect(updating).not.toContain("重新发起");
    expect(updating).not.toContain("重新修改");

    const finished = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "finished",
      url: "http://localhost:42659",
    });
    expect(finished).not.toContain("重新发起");
    expect(finished).not.toContain("重新修改");
  });

  it("run 收口后：轻提示消失，预览照常", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "finished",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).not.toContain("正在更新系统");
  });

  it("跨会话回来（generatedAt 事实 + 应用在）：直接显示系统现状", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      url: "http://localhost:42659",
    });

    expect(html).toContain("<iframe");
    expect(html).toContain('src="http://localhost:42659"');
    expect(html).not.toContain("正在初始化");
  });

  it("已生成但 URL 未到：接通中等待，非故障不打扰", () => {
    const html = renderPanel({ generatedAt: "2026-08-31T08:00:00Z" });

    expect(html).toContain("正在接通系统…");
    expect(html).not.toContain("预览暂时打不开");
    expect(html).not.toContain("<iframe");
  });

  it("已生成但预览真故障（非未就绪）：打不开口径，稍后自动重试", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
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
    const html = renderPanel({ coderStatus: "running", url: "http://localhost:42659" });

    for (const label of ["刷新预览", "桌面预览", "手机预览", "在新窗口打开预览"]) {
      expect(html).toContain(`aria-label="${label}"`);
    }
    // 地址框出真地址（诚实口径，不演装饰域名）：输入框 value + iframe src 两处
    expect(html.match(/http:\/\/localhost:42659/g)).toHaveLength(2);
  });

  it("地址框可聚焦编辑（#125）：页面在时为可编辑 text input，回显真地址", () => {
    const html = renderPanel({ coderStatus: "running", url: "http://localhost:42659" });

    const inputTag = html.match(/<input[^>]*aria-label="预览地址"[^>]*>/)![0];
    expect(inputTag).toContain('type="text"');
    expect(inputTag).toContain('value="http://localhost:42659"');
    expect(inputTag).not.toContain("readonly");
    expect(inputTag).not.toContain('disabled=""');
  });

  it("无页面时刷新与新窗口不可点、地址框不可编辑、工具条不出场（可点击的仅真实现的能力）", () => {
    const html = renderPanel({ coderStatus: "running" });

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
    const html = renderPanel({ coderStatus: "running", url: "http://localhost:42659" });
    expect(html).toContain("light-lock");
  });

  it("页面在时工具条出场：三能力与改字皆置灰待启用（#127 网关注入前占位）", () => {
    const html = renderPanel({ coderStatus: "running", url: "http://localhost:42659" });

    // 三能力（点选/圈选/评论）置灰不可点——圈注脚本未注入（归网关 #122），诚实置灰
    for (const label of ["选择组件", "画笔圈选", "评论"]) {
      const tag = html.match(new RegExp(`<button[^>]*aria-label="${label}（待启用）"[^>]*>`))![0];
      expect(tag).toContain("disabled");
    }
    const textTag = html.match(new RegExp(`<button[^>]*aria-label="直接改文字（待启用）"[^>]*>`))![0];
    expect(textTag).toContain("disabled");
    // 形态位占位标签仍在（非标注态入口）
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
    const html = renderPanel({ coderStatus: "running", url: "http://localhost:42659" });

    expect(html).toContain("正在更新系统，完成后自动刷新");
    // 画在浏览器条内联：提示在地址胶囊之前（旧悬浮遮罩在地址之后的内容区）
    expect(html.indexOf("正在更新系统，完成后自动刷新")).toBeLessThan(
      html.indexOf("http://localhost:42659"),
    );
    // 非悬浮：提示元素不是 absolute 定位（旧：absolute inset-x-0 top-0 遮罩）
    expect(wrapperOpenTag(html, "正在更新系统，完成后自动刷新")).not.toContain("absolute");
  });

  it("页面可见且超限终态：失败提示为非悬浮顶部占位细条（占自己高度、下推预览）", () => {
    const html = renderPanel({
      generatedAt: "2026-08-31T08:00:00Z",
      coderStatus: "error",
      url: "http://localhost:42659",
    });

    expect(html).toContain("修正遇到了问题");
    expect(html).toContain("重新修改");
    // 非悬浮：失败细条不是 absolute 定位（旧：absolute 遮罩叠在预览上）
    expect(wrapperOpenTag(html, "修正遇到了问题")).not.toContain("absolute");
  });

  it("页面可见且从未生成：失败细条带「重新发起」入口（非「重新修改」）", () => {
    const html = renderPanel({
      generatedAt: null,
      coderStatus: "error",
      url: "http://localhost:42659",
    });

    expect(html).toContain("生成遇到了问题");
    expect(html).toContain("重新发起");
    expect(html).not.toContain("重新修改");
    expect(wrapperOpenTag(html, "生成遇到了问题")).not.toContain("absolute");
  });
});
