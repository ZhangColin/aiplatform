import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { Wait } from "@/lib/agent/wait";
import { queryKeys } from "@/lib/api/keys";
import type { ProjectDetail } from "@/lib/main-chain/project";
import type { AgentRun, AgentStreamsState } from "@/lib/store/agent-streams";

import type { ChatFocus } from "./chat-mode";
import { AdvisorChat } from "./advisor-chat";

// 顾问对话问答（issue #52，spec 0002 §4 访谈循环）：PENDING 问答等待点在流内
// wait 分段处渲染成顾问消息 + 选项 chip（optionOnly，题干走分段、审批体/转任务
// 裁掉）；作答成功 → waits 失效重查、chip 随 PENDING 消失、问题消息留存（交互态
// SSR 不覆盖，以「无 PENDING → 问题消息在、chip 不在」锁消解口径）。
//
// 播种：waits 走 query cache（react-query SSR 可读）；run 走 mock——zustand v5
// 在 react-dom/server 下 server snapshot 读 `getInitialState()`（create 时快照，
// `react.mjs` useCallback 闭包捕获内部 api），`setState` 播种对静态渲染不可见
// （既有 SSR 测试因此只播种 query cache）。mock 单个 hook 直读种子状态，store
// 本体行为由 agent-streams.test 覆盖。
/** mock 读口状态形状：latestProjectRun 只读 runs/order（与真实签名一致）。 */
type SeedState = Pick<AgentStreamsState, "runs" | "order">;
const seed = vi.hoisted(() => ({ state: { runs: {}, order: [] } as SeedState }));

vi.mock("@/lib/store/agent-streams", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/agent-streams")>();
  return {
    ...actual,
    useAgentStreamsStore: <T,>(selector: (state: SeedState) => T): T => selector(seed.state),
  };
});

/** document-updated 提示胶囊（issue #54）：同样 SSR 读初始态，mock notices 读口。 */
type NoticesSeedState = {
  notices: Record<string, { documentUpdate?: boolean }>;
  ackDocumentUpdate: () => void;
};
const noticesSeed = vi.hoisted(() => ({
  state: { notices: {}, ackDocumentUpdate: () => {} } as NoticesSeedState,
}));

vi.mock("@/lib/store/project-notices", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/project-notices")>();
  return {
    ...actual,
    useProjectNoticesStore: <T,>(selector: (state: NoticesSeedState) => T): T =>
      selector(noticesSeed.state),
  };
});

function render(waits: Wait[], focus?: ChatFocus, detail?: ProjectDetail) {
  const qc = new QueryClient();
  qc.setQueryData(queryKeys.projects.waits("p1"), waits);
  if (detail) qc.setQueryData(queryKeys.projects.detail("p1"), detail);
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <AdvisorChat projectId="p1" focus={focus} />
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
};

/** 带 wait 分段的 run：分段前后各留一段文本，用于断言卡挂「分段位」而非流底。 */
const runWithWaitSegment: AgentRun = {
  runId: "r1",
  projectId: "p1",
  status: "waiting",
  segments: [
    { kind: "text", id: "s1", data: { text: "顾问先梳理了目标" } },
    { kind: "wait", id: "s2", waitId: "w1", waitKind: "QUESTION", summary: "确认两件事" },
    { kind: "text", id: "s3", data: { text: "后续独立段落" } },
  ],
};

describe("AdvisorChat · 问答卡挂载（issue #52）", () => {
  beforeEach(() => {
    seed.state = { runs: { r1: runWithWaitSegment }, order: ["r1"] };
    noticesSeed.state = { notices: {}, ackDocumentUpdate: () => {} };
  });

  it("wait 分段处内联问答：问题渲染成顾问消息、选项 chip 紧随其后（分段位非流底）", () => {
    const html = render([questionWait]);
    // 题干从流的 wait 分段渲染成顾问消息（去 dev 胶囊），选项 chip 可点、必答提示在
    expect(html).toContain("确认两件事");
    expect(html).toContain("短信验证码");
    expect(html).toContain("每题选一个");
    expect(html).not.toContain("等待你的处理");
    // 分段位：问题消息 → chip → wait 分段之后的独立段落（若挂流底则 chip 会在其后）
    const questionIdx = html.indexOf("确认两件事");
    const chipIdx = html.indexOf("短信验证码");
    const afterIdx = html.indexOf("后续独立段落");
    expect(questionIdx).toBeGreaterThan(-1);
    expect(chipIdx).toBeGreaterThan(questionIdx);
    expect(chipIdx).toBeLessThan(afterIdx);
  });

  it("需求端变体裁剪：转任务 / 审批动作不出现", () => {
    const html = render([questionWait]);
    expect(html).not.toContain("转任务");
    expect(html).not.toContain("允许");
    expect(html).not.toContain("拒绝");
    expect(html).not.toContain("终止任务");
  });

  it("作答后消解口径：无 PENDING 问答 → 问题消息留存、选项 chip 消失", () => {
    const html = render([]);
    expect(html).toContain("确认两件事");
    expect(html).not.toContain("短信验证码");
    expect(html).not.toContain("提交回答");
  });

  it("跨会话兜底：PENDING 问答无对应分段（刷新后流不在）→ 流底挂卡", () => {
    seed.state = {
      runs: { r1: { ...runWithWaitSegment, segments: runWithWaitSegment.segments.slice(0, 1) } },
      order: ["r1"],
    };
    const html = render([questionWait]);
    expect(html).not.toContain("等待你的处理");
    expect(html).toContain("验证码渠道");
  });

  it("深链高亮：focus 命中 waitId → 卡加 ring", () => {
    const html = render([questionWait], { kind: "wait", waitId: "w1" });
    expect(html).toContain("ring-primary");
  });
});

describe("AdvisorChat · PRD 更新提示胶囊（issue #54）", () => {
  beforeEach(() => {
    seed.state = { runs: {}, order: [] };
    noticesSeed.state = { notices: {}, ackDocumentUpdate: () => {} };
  });

  it("documentUpdate 置位 → 输入条上方出现提示胶囊", () => {
    noticesSeed.state = { notices: { p1: { documentUpdate: true } }, ackDocumentUpdate: () => {} };
    const html = render([]);
    expect(html).toContain("PRD 有更新");
    expect(html).toContain("文档");
  });

  it("未置位 → 不渲染胶囊", () => {
    const html = render([]);
    expect(html).not.toContain("PRD 有更新");
  });

  it("别的项目的置位不串台：按 projectId 键控", () => {
    noticesSeed.state = {
      notices: { p2: { documentUpdate: true } },
      ackDocumentUpdate: () => {},
    };
    const html = render([]);
    expect(html).not.toContain("PRD 有更新");
  });
});

describe("AdvisorChat · 门卡就绪才挂（issue #58）", () => {
  beforeEach(() => {
    seed.state = { runs: {}, order: [] };
    noticesSeed.state = { notices: {}, ackDocumentUpdate: () => {} };
  });

  /** 单段主链详情：PRD 段带 BA 门 → 旅程第一步 current、门名映射「确认 PRD」齐备。 */
  function gateDetail(ready?: boolean): ProjectDetail {
    return {
      id: "p1",
      name: "官网 demo",
      stage: "PRD",
      stageLabel: "需求",
      stages: [{ name: "PRD", label: "需求", gateActor: "BA" }],
      gate: { actor: "BA", ready },
    };
  }

  it("门就绪：流底挂门卡（可操作态由 GateCard 组件测试锁定，此处锁挂卡口径）", () => {
    const html = render([], undefined, gateDetail(true));
    expect(html).toContain("需要你拍板");
    expect(html).toContain("PRD 已整理好，等你确认");
    expect(html).toContain("确认无误，开始做原型");
    expect(html).not.toContain("未就绪");
  });

  it("门未就绪（ready === false）：流底不挂门卡，访谈期只剩消息流", () => {
    const html = render([], undefined, gateDetail(false));
    expect(html).not.toContain("需要你拍板");
    expect(html).not.toContain("PRD 已整理好");
    expect(html).not.toContain("确认无误");
  });

  it("ready 字段缺失：按未就绪处理，不挂门卡（契约字段可空）", () => {
    const html = render([], undefined, gateDetail(undefined));
    expect(html).not.toContain("需要你拍板");
  });

  it("门名映射缺失（非用户拍板门）：即使就绪也不挂门卡", () => {
    const html = render(
      [],
      undefined,
      // gateActor 缺 → 旅程步无 gateLabel → userGateCopy 返回 null（开发侧门折叠）
      { ...gateDetail(true), stages: [{ name: "DEV", label: "开发" }] },
    );
    expect(html).not.toContain("需要你拍板");
  });

  it("深链 gate 聚焦不回归：门就绪才可深链，落地有卡（data-focus-gate）", () => {
    const html = render([], { kind: "gate" }, gateDetail(true));
    expect(html).toContain('data-focus-gate="true"');
    expect(html).toContain("PRD 已整理好，等你确认");
  });
});
