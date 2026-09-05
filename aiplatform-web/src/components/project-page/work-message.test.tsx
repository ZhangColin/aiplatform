import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

import { WorkMessage, formatDuration, formatElapsed } from "./work-message";

function work(overrides: Partial<WorkSnapshot> = {}): WorkSnapshot {
  return {
    runId: "run-1",
    startedAt: 1_000,
    frozen: false,
    parts: [],
    ...overrides,
  };
}

function action(overrides: Partial<Extract<WorkPart, { kind: "action" }>> = {}) {
  return {
    kind: "action",
    id: "run-1:4",
    toolCallId: "tc-1",
    toolName: "write_file",
    state: "completed",
    label: "编写【订单管理】",
    startedAt: 1_000,
    endedAt: 6_000,
    ...overrides,
  } satisfies Extract<WorkPart, { kind: "action" }>;
}

function permission(overrides: Partial<Extract<WorkPart, { kind: "permission" }>> = {}) {
  return {
    kind: "permission",
    id: "run-1:5",
    engineRef: "reply-9",
    summary: "rm -rf /workspace/data",
    state: "pending",
    at: 2_000,
    ...overrides,
  } satisfies Extract<WorkPart, { kind: "permission" }>;
}

/** 确认卡走 useMutation（作答动作）——SSR 渲染包 QueryClientProvider。 */
function renderWithClient(workSnapshot: WorkSnapshot) {
  const client = new QueryClient({ defaultOptions: { mutations: { gcTime: 0 } } });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <WorkMessage work={workSnapshot} projectId="p1" />
    </QueryClientProvider>,
  );
}

describe("WorkMessage · 生长中的工作消息（#81：部件结构与状态呈现）", () => {
  it("run 开始即出现：空部件的生长中消息出「正在做」头部与打字点，无部件行", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work()} projectId="p1" />);

    expect(html).toContain("正在做");
    expect(html).toContain("正在干活…");
  });

  it("部件序列呈现：步骤分组头「第 N 步」+ 解说段 + 动作卡对象短语", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "step", id: "run-1:2", step: 1 },
            { kind: "text", id: "run-1:3", text: "正在编写订单管理页面。" },
            action({ state: "running", endedAt: undefined }),
          ],
        })}
        projectId="p1"
      />,
    );

    expect(html).toContain("第 1 步");
    expect(html).toContain("正在编写订单管理页面。");
    expect(html).toContain("编写【订单管理】");
    expect(html).toContain("进行中");
  });

  it("动作卡三态：进行中转圈（无时长）、完成打勾带时长、失败「没做成」", () => {
    const running = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "started", endedAt: undefined })] })} projectId="p1" />,
    );
    expect(running).toContain("进行中");
    expect(running).not.toContain("秒");

    const done = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1" })] })} projectId="p1" />,
    );
    expect(done).toContain("5 秒");

    const failed = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "failed" })] })} projectId="p1" />,
    );
    expect(failed).toContain("没做成");
    expect(failed).toContain("5 秒"); // 失败也带时长（试了多久如实可读）
  });

  it("收口定格：头部与打字点退场、部件留驻（动作时长定格不跳动）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          frozenAt: 60_000,
          parts: [
            { kind: "text", id: "run-1:2", text: "订单管理完成" },
            action({ id: "a1", toolCallId: "t1", state: "running", endedAt: undefined }),
          ],
        })}
        projectId="p1"
      />,
    );

    expect(html).not.toContain("正在做");
    expect(html).not.toContain("正在干活…");
    expect(html).toContain("订单管理完成");
    // 未终态动作在定格时刻冻结时长（60s - 1s = 59 秒），不显示「进行中」
    expect(html).not.toContain("进行中");
    expect(html).toContain("59 秒");
  });

  it("定格且无部件（起跑即死）：不渲染空壳", () => {
    expect(renderToStaticMarkup(<WorkMessage work={work({ frozen: true })} projectId="p1" />)).toBe("");
  });
});

describe("WorkMessage · 确认卡（#83 权限确认：长在工作消息流，与问答卡分形态）", () => {
  it("待答：警示色调确认卡——命令摘要（等宽）+ 拒绝/批准两个动作", () => {
    const html = renderWithClient(work({ parts: [permission()] }));

    expect(html).toContain("需要您的确认");
    expect(html).toContain("rm -rf /workspace/data");
    expect(html).toContain("拒绝");
    expect(html).toContain("批准");
  });

  it("已批准 / 已拒绝：转徽标定格（按钮退场——作答一次即续跑）", () => {
    const approved = renderWithClient(work({ parts: [permission({ state: "approved" })] }));
    expect(approved).toContain("已批准");
    expect(approved).not.toContain(">批准</");

    const denied = renderWithClient(work({ parts: [permission({ state: "denied" })] }));
    expect(denied).toContain("已拒绝");
    expect(denied).not.toContain(">批准</");
  });

  it("run 收口截断的待答卡：按钮退场、如实呈现「未作答」（过期卡作答被服务端 409 指路刷新）", () => {
    const html = renderWithClient(
      work({ frozen: true, frozenAt: 60_000, parts: [permission()] }),
    );

    expect(html).toContain("未作答");
    expect(html).not.toContain(">批准</");
  });
});

describe("时长格式（用户语言，整秒）", () => {
  it("动作时长：<60 秒「N 秒」，跨分「M 分 SS 秒」", () => {
    expect(formatDuration(4_300)).toBe("4 秒");
    expect(formatDuration(63_000)).toBe("1 分 03 秒");
    expect(formatDuration(-5_000)).toBe("0 秒"); // 时钟回拨防御
  });

  it("头部总时长：等宽 m:ss", () => {
    expect(formatElapsed(65_000)).toBe("1:05");
    expect(formatElapsed(0)).toBe("0:00");
  });
});
