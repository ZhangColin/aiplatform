import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it } from "vitest";

import { queryKeys } from "@/lib/api/keys";
import type { ProjectDetail } from "@/lib/main-chain/project";
import { useAgentStreamsStore } from "@/lib/store/agent-streams";

import { AgentArea } from "./agent-area";

// Agent 区形态由场景配置（spec 0001 §4 / spec 0002 §4）：需求端顾问单对话、
// 开发平台三模式 tab。对话模式已由 #40 接真（消息流 + 下任务输入，读 streams
// store / 用 react-query mutation），故静态渲染需 QueryClientProvider + 空 store。
function render(variant: "advisor" | "dev", detail?: ProjectDetail) {
  const qc = new QueryClient();
  if (detail) qc.setQueryData(queryKeys.projects.detail("p1"), detail);
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <AgentArea variant={variant} projectId="p1" />
    </QueryClientProvider>,
  );
}

describe("AgentArea", () => {
  beforeEach(() => {
    useAgentStreamsStore.setState({ runs: {}, order: [] });
  });

  it("advisor 顾问单对话模式：只显示「顾问对话」，不出现直播 / 待处理", () => {
    const html = render("advisor");
    expect(html).toContain("顾问对话");
    expect(html).not.toContain("直播");
    expect(html).not.toContain("待处理");
  });

  it("advisor 顾问对话已接真：补充需求输入在，dev 下任务 / 角色卡不在", () => {
    const html = render("advisor");
    expect(html).toContain("补充需求");
    expect(html).not.toContain("给智能体下任务");
    expect(html).not.toContain("阶段默认");
  });

  it("dev 三模式 tab 骨架：对话 / 直播 / 待处理均就位", () => {
    const html = render("dev");
    expect(html).toContain("对话");
    expect(html).toContain("直播");
    expect(html).toContain("待处理");
  });

  it("dev 对话模式已接真：空 store 渲染下任务输入占位与空态引导", () => {
    const html = render("dev");
    expect(html).toContain("给智能体下任务…");
    expect(html).toContain("阶段默认");
  });
});

describe("AgentArea · dev 门卡就绪才挂（issue #58，同顾问对话口径）", () => {
  beforeEach(() => {
    useAgentStreamsStore.setState({ runs: {}, order: [] });
  });

  function devGateDetail(ready?: boolean): ProjectDetail {
    return {
      id: "p1",
      name: "项目甲",
      stage: "test",
      stageLabel: "测试",
      stages: [],
      gate: { actor: "DEV", ready },
    };
  }

  it("门就绪：对话模式流底挂决策门卡，待处理计数含门", () => {
    const html = render("dev", devGateDetail(true));
    expect(html).toContain("决策门");
    expect(html).toContain("等你拍板");
    // 计数口径：无等待点 + 门就绪 = 1
    expect(html).toContain("待处理");
    expect(html).toMatch(/待处理[^<]*<[^>]+>\s*1</);
  });

  it("门未就绪（ready === false）：不挂门卡、计数不含门", () => {
    const html = render("dev", devGateDetail(false));
    expect(html).not.toContain("决策门");
    expect(html).not.toContain("等你拍板");
    expect(html).not.toMatch(/待处理[^<]*<[^>]+>\s*1</);
  });

  it("ready 字段缺失：按未就绪处理（契约字段可空）", () => {
    const html = render("dev", devGateDetail(undefined));
    expect(html).not.toContain("决策门");
  });
});
