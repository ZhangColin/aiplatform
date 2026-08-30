import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { AgentRun, AgentStreamSegment } from "@/lib/store/agent-streams";

import {
  ErrorBlock,
  FinishBlock,
  KnowledgeHitsBlock,
  LivePanel,
  PassthroughBlock,
  PatchBlock,
  ReasoningBlock,
  RoleBlock,
  StageTimeline,
  StepBoundary,
  TaskStartBlock,
  TextBlock,
  ToolBlock,
  WaitBlock,
  WaitSettledBlock,
} from "./live-panel";

// 直播模式纯读 streams store（桥唯一写入方）。分段渲染块拿 segment 作 props，故
// SSR 断言 = 直接 renderToStaticMarkup 各块（无需 DOM / QueryClientProvider，本组件
// 不触 react-query）。zustand 在 SSR 下走 getInitialState（空表），LivePanel 全量读
// store 的路径改用 StageTimeline（run 走 props）验证。

describe("分段渲染块（renderToStaticMarkup 断言，spec 0001 §4.2）", () => {
  it("TextBlock：流式段落渲染文本（无气泡字幕式）", () => {
    const html = renderToStaticMarkup(
      <TextBlock segment={{ kind: "text", id: "t1", data: { text: "我先看一下页面结构" } }} />,
    );
    expect(html).toContain("我先看一下页面结构");
  });

  it("ReasoningBlock：思考折叠默认收起，露出「思考过程」触发钮", () => {
    const html = renderToStaticMarkup(
      <ReasoningBlock segment={{ kind: "reasoning", id: "r1", data: { text: "纪要字段要确认" } }} />,
    );
    expect(html).toContain("思考过程");
  });

  it("ToolBlock：icon 映射 + 名称 + 入参截断 + spinner→✓", () => {
    const running = renderToStaticMarkup(
      <ToolBlock
        segment={{ kind: "tool", id: "t1", data: { tool: "bash", state: { status: "running", input: { cmd: "pnpm build" } } } }}
      />,
    );
    expect(running).toContain("bash");
    expect(running).toContain("执行中");

    const done = renderToStaticMarkup(
      <ToolBlock segment={{ kind: "tool", id: "t2", data: { tool: "edit", state: { status: "completed", input: "Form.tsx" } } }} />,
    );
    expect(done).toContain("edit");
    expect(done).toContain("Form.tsx");
    expect(done).not.toContain("执行中");
  });

  it("PatchBlock：头部计数 + 行级 +/- 染色 + 摘要行", () => {
    const html = renderToStaticMarkup(
      <PatchBlock
        segment={{
          kind: "patch",
          id: "p1",
          data: {
            path: "src/Form.tsx",
            added: 148,
            removed: 12,
            summary: "新增预约表单",
            diff: "-// TODO: 校验\n+const ok = /^1\\d{10}$/.test(phone)",
          },
        }}
      />,
    );
    expect(html).toContain("src/Form.tsx");
    expect(html).toContain("+148");
    expect(html).toContain("−12");
    expect(html).toContain("新增预约表单");
    expect(html).toContain("// TODO: 校验");
    expect(html).toContain("const ok");
  });

  it("StepBoundary：开始/完成两态 + 步骤名", () => {
    const start = renderToStaticMarkup(
      <StepBoundary segment={{ kind: "step", id: "s1", phase: "start", data: { title: "实现表单" } }} />,
    );
    expect(start).toContain("开始 · 实现表单");

    const finish = renderToStaticMarkup(
      <StepBoundary segment={{ kind: "step", id: "s2", phase: "finish", data: {} }} />,
    );
    expect(finish).toContain("完成 · 步骤");
  });

  it("RoleBlock：角色卡分配一行", () => {
    const html = renderToStaticMarkup(
      <RoleBlock segment={{ kind: "role", id: "r1", role: "dev", roleLabel: "开发工程师", stage: "开发", engine: "opencode" }} />,
    );
    expect(html).toContain("开发工程师");
    expect(html).toContain("开发");
    expect(html).toContain("opencode");
  });

  it("KnowledgeHitsBlock：双卡网格横幅保留现状", () => {
    const html = renderToStaticMarkup(
      <KnowledgeHitsBlock
        segment={{
          kind: "knowledge",
          id: "k1",
          items: [{ kind: "PRD", projectName: "上单", title: "纪要", snippet: "……" }],
        }}
      />,
    );
    expect(html).toContain("知识命中");
    expect(html).toContain("纪要");
    expect(html).toContain("上单");
  });

  it("Wait / WaitSettled / Error / Finish：各自独立状态块", () => {
    expect(
      renderToStaticMarkup(
        <WaitBlock segment={{ kind: "wait", id: "w1", waitId: "x", waitKind: "QUESTION", summary: "验证码渠道？" }} />,
      ),
    ).toContain("等待你的处理 · 验证码渠道？");

    expect(
      renderToStaticMarkup(
        <WaitSettledBlock segment={{ kind: "wait-settled", id: "w2", waitId: "x", outcome: "answered" }} />,
      ),
    ).toContain("等待点已处理 · answered");

    expect(
      renderToStaticMarkup(<ErrorBlock segment={{ kind: "error", id: "e1", message: "构建失败" }} />),
    ).toContain("构建失败");

    expect(
      renderToStaticMarkup(<FinishBlock segment={{ kind: "finish", id: "f1", finish: "completed" }} />),
    ).toContain("任务完成");
  });

  it("PassthroughBlock：未知透传类型兜底呈现 type + 载荷预览", () => {
    const html = renderToStaticMarkup(
      <PassthroughBlock segment={{ kind: "passthrough", id: "u1", type: "custom-event", data: { foo: "bar" } }} />,
    );
    expect(html).toContain("引擎事件 · custom-event");
    expect(html).toContain("bar");
  });

  it("TaskStartBlock：任务开头声明本次运行要干的事", () => {
    const html = renderToStaticMarkup(<TaskStartBlock prompt="实现预约表单页" />);
    expect(html).toContain("任务开始");
    expect(html).toContain("实现预约表单页");
  });
});

describe("LivePanel（全量舞台时间线，issue #41）", () => {
  it("空 store：空态引导", () => {
    const html = renderToStaticMarkup(<LivePanel projectId="p1" />);
    expect(html).toContain("直播");
    expect(html).toContain("智能体开始干活后");
  });

  it("StageTimeline：渲染全部平台事件 + 引擎透传分段，非仅知识命中", () => {
    const segments: AgentStreamSegment[] = [
      { kind: "role", id: "s1", role: "dev", roleLabel: "开发工程师", stage: "开发", engine: "opencode" },
      { kind: "text", id: "s2", data: { text: "我先看一下页面结构" } },
      { kind: "reasoning", id: "s3", data: { text: "纪要字段要确认" } },
      { kind: "tool", id: "s4", data: { tool: "bash", state: { status: "running", input: { cmd: "pnpm build" } } } },
      { kind: "patch", id: "s5", data: { path: "Form.tsx", added: 148, removed: 12, summary: "新增表单", diff: "+const ok = true" } },
      { kind: "step", id: "s6", phase: "start", data: { title: "实现表单" } },
      { kind: "knowledge", id: "s7", items: [{ kind: "PRD", projectName: "上单", title: "纪要", snippet: "……" }] },
      { kind: "wait", id: "s8", waitId: "w1", waitKind: "QUESTION", summary: "验证码渠道？" },
      { kind: "wait-settled", id: "s9", waitId: "w1", outcome: "answered" },
      { kind: "error", id: "s10", message: "构建失败" },
      { kind: "finish", id: "s11", finish: "completed" },
      { kind: "passthrough", id: "s12", type: "custom-event", data: { foo: "bar" } },
    ];

    const run: AgentRun = {
      runId: "r1",
      projectId: "p1",
      prompt: "实现表单",
      status: "running",
      segments,
    };

    const html = renderToStaticMarkup(<StageTimeline run={run} />);

    expect(html).toContain("任务开始"); // task-start prompt
    expect(html).toContain("实现表单");
    expect(html).toContain("开发工程师"); // role
    expect(html).toContain("我先看一下页面结构"); // text
    expect(html).toContain("思考过程"); // reasoning
    expect(html).toContain("bash"); // tool
    expect(html).toContain("Form.tsx"); // patch
    expect(html).toContain("开始 · 实现表单"); // step
    expect(html).toContain("知识命中"); // knowledge
    expect(html).toContain("等待你的处理"); // wait
    expect(html).toContain("等待点已处理"); // wait-settled
    expect(html).toContain("构建失败"); // error
    expect(html).toContain("任务完成"); // finish
    expect(html).toContain("custom-event"); // passthrough 兜底
  });
});
