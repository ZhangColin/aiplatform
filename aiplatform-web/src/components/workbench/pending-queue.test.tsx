import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { Wait } from "@/lib/agent/wait";
import { queryKeys } from "@/lib/api/keys";
import type { ProjectDetail } from "@/lib/main-chain/project";

import { PendingQueue } from "./pending-queue";

// 待处理队列（issue #44，spec 0001 §4.3）：聚合 HITL 等待 + 门拍板；空态
// 「一切自动运行中」。SSR 断言走 query cache 播种（useProjectWaits / useProjectJourney
// 读 react-query），「处理后收为已处理一行」是交互态（settle 成功后 re-fetch 前）
// 不在此 SSR 覆盖。
function render(waits: Wait[] = [], detail?: ProjectDetail) {
  const qc = new QueryClient();
  qc.setQueryData(queryKeys.projects.waits("p1"), waits);
  if (detail) qc.setQueryData(queryKeys.projects.detail("p1"), detail);
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <PendingQueue projectId="p1" />
    </QueryClientProvider>,
  );
}

const questionWait: Wait = {
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
  body: { questions: [{ header: "验证码渠道", options: [{ label: "短信验证码" }] }] },
};

const gateDetail: ProjectDetail = {
  id: "p1",
  name: "项目甲",
  stage: "test",
  stageLabel: "测试",
  stages: [],
  gate: { actor: "DEV", ready: true },
};

describe("PendingQueue", () => {
  it("空态：无等待点 + 无门 → 「一切自动运行中」", () => {
    const html = render([]);
    expect(html).toContain("一切自动运行中");
  });

  it("聚合 HITL 等待：渲染等待点卡", () => {
    const html = render([questionWait]);
    expect(html).toContain("提问");
    expect(html).toContain("验证码渠道");
  });

  it("聚合门拍板：门就绪渲染决策门卡（#58 收口）", () => {
    const html = render([], gateDetail);
    expect(html).toContain("决策门");
  });

  it("门未就绪：不入队、走空态（就绪才挂，与对话流底同口径）", () => {
    const html = render([], { ...gateDetail, gate: { actor: "DEV", ready: false } });
    expect(html).toContain("一切自动运行中");
    expect(html).not.toContain("决策门");
  });

  it("HITL 等待 + 门卡并列聚合", () => {
    const html = render([questionWait], gateDetail);
    expect(html).toContain("验证码渠道");
    expect(html).toContain("决策门");
  });
});
