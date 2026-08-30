import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { Wait } from "@/lib/agent/wait";

import { WaitCard } from "./wait-card";

// HITL 卡（issue #45，spec 0001 §5）：问答卡 / 审批卡按 kind 分派，settle 走
// useSettleWait（react-query mutation，故需 QueryClientProvider）。断言只看外部
// 可见行为：问答卡多题渲染、审批卡工具 + 逃生口、空 body 兜底、深链高亮 ring、
// 需求端变体裁剪（#52：问答体在 / 审批体与转任务不在）。
function render(wait: Wait, highlight = false, variant?: "dev" | "advisor") {
  return renderToStaticMarkup(
    <QueryClientProvider client={new QueryClient()}>
      <WaitCard projectId="p1" wait={wait} highlight={highlight} variant={variant} />
    </QueryClientProvider>,
  );
}

const baseWait: Wait = {
  waitId: "w1",
  kind: 1,
  kindName: "问答",
  status: 1,
  statusName: "待处理",
  summary: "确认两件事",
  runId: "r1",
  raisedAt: "2026-08-22T02:15:33Z",
  settledAt: "",
  settleOutcome: 0,
  settleOutcomeName: "",
  body: undefined,
};

describe("WaitCard · 问答卡", () => {
  it("渲染问题 + 选项 + 提交按钮（多题/单选/自定义）", () => {
    const html = render({
      ...baseWait,
      body: {
        from: "项目顾问",
        questions: [
          {
            header: "验证码渠道",
            question: "手机验证用哪种？",
            multiple: false,
            custom: true,
            options: [{ label: "短信验证码", description: "成本略高" }, { label: "暂不验证" }],
          },
        ],
      },
    });
    expect(html).toContain("提问");
    expect(html).toContain("验证码渠道");
    expect(html).toContain("手机验证用哪种？");
    expect(html).toContain("短信验证码");
    expect(html).toContain("暂不验证");
    // 选项 description 可见（非仅 tooltip，spec 0001 §5）
    expect(html).toContain("成本略高");
    // 初始态未作答 → 提交钮禁用并提示「每题选一个」（SSR 无交互态）
    expect(html).toContain("每题选一个");
    // deferred 转任务入口常驻卡底
    expect(html).toContain("转任务");
  });

  it("空 questions：兜底文案不崩", () => {
    const html = render({ ...baseWait, body: { questions: [] } });
    expect(html).toContain("等待点无可答内容");
  });
});

describe("WaitCard · 审批卡", () => {
  it("渲染工具 + 入参 + 过期 + 允许/拒绝 + 终止任务逃生口", () => {
    const html = render({
      ...baseWait,
      kind: 2,
      kindName: "权限",
      body: { tool: "bash", args: "pnpm build", reason: "部署预览", expiresInMin: 30 },
    });
    expect(html).toContain("审批");
    expect(html).toContain("bash");
    expect(html).toContain("pnpm build");
    expect(html).toContain("30 分钟内未处理自动过期");
    expect(html).toContain("允许");
    expect(html).toContain("拒绝");
    expect(html).toContain("终止任务");
    expect(html).toContain("转任务");
  });
});

describe("WaitCard · 需求端变体（advisor，issue #52）", () => {
  it("问答体在：多题/单选/多选/自定义/必答提示与开发平台一致", () => {
    const html = render(
      {
        ...baseWait,
        body: {
          from: "项目顾问",
          questions: [
            {
              header: "验证码渠道",
              question: "手机验证用哪种？",
              multiple: false,
              custom: true,
              options: [{ label: "短信验证码", description: "成本略高" }, { label: "暂不验证" }],
            },
            {
              header: "上线范围",
              question: "先上哪些端？",
              multiple: true,
              custom: false,
              options: [{ label: "手机 H5" }, { label: "微信小程序" }],
            },
          ],
        },
      },
      false,
      "advisor",
    );
    expect(html).toContain("验证码渠道");
    expect(html).toContain("手机验证用哪种？");
    expect(html).toContain("短信验证码");
    expect(html).toContain("成本略高");
    // 多题（第二题）与多选题同卡渲染
    expect(html).toContain("上线范围");
    expect(html).toContain("微信小程序");
    expect(html).toContain("每题选一个");
  });

  it("裁剪：转任务入口与 runId 技术标识不出现；空题兜底走用户话术", () => {
    const html = render({ ...baseWait, body: { questions: [] } }, false, "advisor");
    expect(html).not.toContain("转任务");
    // runId（font-mono 技术标识）不上需求端（spec 0002 §5）
    expect(html).not.toContain("font-mono");
    // 空题兜底 = 用户话术，非「等待点/引擎载荷」dev 术语
    expect(html).toContain("这条提问的内容没能加载出来");
    expect(html).not.toContain("引擎载荷");
  });

  it("审批体不出现在需求端：允许/拒绝/终止任务被裁，兜底为用户话术", () => {
    const html = render(
      {
        ...baseWait,
        kind: 2,
        kindName: "权限",
        body: { tool: "bash", args: "pnpm build", reason: "部署预览", expiresInMin: 30 },
      },
      false,
      "advisor",
    );
    expect(html).not.toContain("允许");
    expect(html).not.toContain("拒绝");
    expect(html).not.toContain("终止任务");
    expect(html).not.toContain("转任务");
    // 防御兜底：权限 body 收窄不出问答 → 用户话术（挂载层本就过滤权限等待点）
    expect(html).toContain("这条提问的内容没能加载出来");
    expect(html).not.toContain("引擎载荷");
  });
});

describe("WaitCard · 深链高亮", () => {
  it("highlight 命中：加 ring 高亮类", () => {
    const html = render({ ...baseWait, body: { questions: [] } }, true);
    expect(html).toContain("ring-primary");
  });
});
