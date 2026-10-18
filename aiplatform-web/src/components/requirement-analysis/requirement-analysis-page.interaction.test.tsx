// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";
import type { ChatState } from "@/lib/store/chat";
import type { PrdNoticesState } from "@/lib/store/prd-notices";

import { RequirementAnalysisPage } from "./requirement-analysis-page";

/**
 * 需求分析页 P02/P03 装配（#346，spec #320 ⑥，interaction seam）：闲聊期对话
 * 占全宽／首产 PRD 双栏滑出（左 5＝收敛卡＋时间流＋澄清输入，右 7＝PRD 文档面）；
 * 粘性 header＝代号胶囊＋项目名＋版本徽标＋「对话调优」「导出 (PDF/Word)」＋主
 * CTA（确认门两段式接通 #332 机制／已确认换「前往 Demo 体验」）。子件各自有
 * 专测，本 seam 锁装配与状态分野。CommandArea/PrdPanel 等数据口 mock 同各自专测。
 */
const seed = vi.hoisted(() => ({
  detail: undefined as ProjectDetail | undefined,
  prd: undefined as {
    content: string;
    updatedAt: string;
    readiness?: { blocking: { text: string }[]; minor: { text: string }[]; dimensions: { dimension: string; status: string }[] };
    draftChangeSummary?: string | null;
  } | null | undefined,
  prdVersions: [] as { versionNo: number }[],
  chats: { chats: {} } as Pick<ChatState, "chats">,
  notices: { seen: {}, pending: {} } as Pick<PrdNoticesState, "seen" | "pending">,
  works: {} as Record<string, unknown>,
  confirmMutate: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({ data: seed.detail, isPending: false, isError: false, error: null, refetch: vi.fn() }),
}));

vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({ data: seed.prd, isPending: false }),
  usePrdVersions: () => ({ data: seed.prdVersions }),
}));

vi.mock("@/hooks/use-confirm-prd", () => ({
  useConfirmPrd: () => ({ isPending: false, mutate: seed.confirmMutate }),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: seed.push }),
}));

vi.mock("@/lib/sse/agent-event-channel", () => ({ useAgentEventChannel: () => ({}) }));
vi.mock("@/lib/sse/provider", () => ({
  useSseStatus: () => "connected",
  useSseFallbackPolling: () => false,
}));

vi.mock("@/lib/store/chat", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/chat")>();
  return {
    ...actual,
    useChatStore: <T,>(selector: (state: Pick<ChatState, "chats">) => T): T => selector(seed.chats),
  };
});

vi.mock("@/lib/store/prd-notices", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/prd-notices")>();
  return {
    ...actual,
    usePrdNoticesStore: Object.assign(
      <T,>(
        selector: (
          state: Pick<PrdNoticesState, "seen" | "pending" | "markSeen">,
        ) => T,
      ): T => selector({ ...seed.notices, markSeen: vi.fn() }),
      { getState: () => ({ acknowledge: vi.fn(), markSeen: vi.fn() }) },
    ),
  };
});

vi.mock("@/lib/store/work-message", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/store/work-message")>();
  return {
    ...actual,
    useWorkMessageStore: <T,>(selector: (state: { works: typeof seed.works }) => T): T =>
      selector({ works: seed.works }),
  };
});

vi.mock("@/hooks/use-conversation", () => ({ useConversation: () => ({}) }));
vi.mock("@/hooks/use-upload-material", () => ({ useUploadMaterial: () => vi.fn() }));
vi.mock("@/hooks/use-chat", () => ({
  usePostMessage: () => ({ isPending: false, mutate: vi.fn() }),
  useAnswerQuestion: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-version", () => ({
  useRollbackVersion: () => ({ mutate: vi.fn(), isPending: false }),
  useVersionViewSession: () => ({
    startView: { mutate: vi.fn(), isPending: false, isError: false, data: undefined, reset: vi.fn() },
    start: vi.fn(),
    finish: vi.fn(),
  }),
}));

/** 标准播种：闲聊期（无 PRD）项目。 */
function seedChatOnlyProject() {
  seed.detail = { id: "p1", name: "宠物医院预约管理系统" } as ProjectDetail;
  seed.prd = null;
  seed.prdVersions = [];
  seed.chats = { chats: {} };
  seed.notices = { seen: {}, pending: {} };
  seed.works = {};
}

/** 标准播种：PRD 已产出（草稿未确认）。 */
function seedPrdProducedProject() {
  seed.detail = { id: "p1", name: "宠物医院预约管理系统", prdProducedAt: "2026-10-18T01:00:00Z" } as ProjectDetail;
  seed.prd = {
    content: "## 需求背景\n背景正文。\n## 验收与待确认\n暂无。",
    updatedAt: "2026-10-18T01:00:00Z",
    readiness: {
      blocking: [],
      minor: [],
      dimensions: [
        { dimension: "目标与角色", status: "ready" },
        { dimension: "主流程", status: "ready" },
        { dimension: "功能与边界", status: "ready" },
        { dimension: "数据与约束", status: "minor" },
        { dimension: "验收", status: "blocking" },
      ],
    },
  };
  seed.prdVersions = [];
  seed.chats = { chats: {} };
  seed.notices = { seen: {}, pending: {} };
  seed.works = {};
}

beforeEach(() => {
  seedChatOnlyProject();
  seed.confirmMutate.mockReset();
  seed.push.mockReset();
});
afterEach(cleanup);

describe("需求分析页 · 闲聊期（无 PRD）", () => {
  it("对话占全宽：时间流/澄清输入在、PRD 文档面与收敛卡不渲染", () => {
    render(<RequirementAnalysisPage projectId="p1" />);

    expect(screen.getByText("逐步澄清与收敛流水线")).toBeTruthy(); // 收敛卡在（0/5 全灰兜底）
    expect(document.querySelector('[data-frame="timeline-card"]')).toBeTruthy();
    expect(screen.queryByText("结构化需求规格说明书 (PRD)")).toBeNull();
    // 无 PRD：不出徽标、不出确认门 CTA、导出禁用
    expect(screen.queryByText(/确认此版 PRD 并生成 Demo/)).toBeNull();
    expect(screen.getByRole("button", { name: /导出/ }).hasAttribute("disabled")).toBe(true);
  });

  it("粘性 header：代号胶囊（真实项目标识＋STAGE-01）＋项目名", () => {
    render(<RequirementAnalysisPage projectId="p1" />);

    expect(screen.getByText("STAGE-01")).toBeTruthy();
    expect(screen.getByText("宠物医院预约管理系统")).toBeTruthy();
  });
});

describe("需求分析页 · 首产 PRD 双栏滑出", () => {
  it("PRD 产出：右栏文档面滑出（5:7 双栏），收敛卡消费真实维度态（3/5 就绪）", () => {
    seedPrdProducedProject();
    render(<RequirementAnalysisPage projectId="p1" />);

    // 右栏文档面三新件在位
    expect(screen.getByText("结构化需求规格说明书 (PRD)")).toBeTruthy();
    expect(screen.getByText("SPEC_PARSER: ACTIVE")).toBeTruthy();
    // 锚点导航照分节
    expect(screen.getByText("1. 需求背景")).toBeTruthy();
    // 收敛卡 n/5 徽章＝真实 ready 计数
    expect(screen.getByText("3/5 维度就绪")).toBeTruthy();
    // 滑出＝右栏带滑入动效标记（成果区滑入同款 keyframe）
    const panel = screen.getByText("结构化需求规格说明书 (PRD)").closest("section");
    expect(panel?.parentElement?.className).toContain("outputs-slide-in");
  });

  it("草稿待确认：徽标＋主 CTA 出确认门；两段式摘要层接通门机制（确认才真 POST）", () => {
    seedPrdProducedProject();
    render(<RequirementAnalysisPage projectId="p1" />);

    expect(screen.getByText("PRD 草稿待确认")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /确认此版 PRD 并生成 Demo/ }));
    // 第一段＝摘要层
    expect(screen.getByText(/已梳理 3\/5 个维度/)).toBeTruthy();
    expect(seed.confirmMutate).not.toHaveBeenCalled();
    // 第二段＝层内确认
    fireEvent.click(screen.getByRole("button", { name: "继续" }));
    fireEvent.click(screen.getByRole("button", { name: /确认并生成 Demo/ }));
    expect(seed.confirmMutate).toHaveBeenCalledTimes(1);
  });

  it("已确认无停驻草稿：徽标 vN 已确认＋主 CTA 换「前往 Demo 体验」（链 Demo 阶段页）", () => {
    seedPrdProducedProject();
    seed.prdVersions = [{ versionNo: 2 }];
    render(<RequirementAnalysisPage projectId="p1" />);

    expect(screen.getByText("PRD v2 已确认")).toBeTruthy();
    expect(screen.queryByText(/确认此版 PRD 并生成 Demo/)).toBeNull();
    const link = screen.getByRole("link", { name: /前往 Demo 体验/ });
    expect(link.getAttribute("href")).toBe("/projects/p1/demo");
  });

  it("范围级新草稿停驻（#334）：徽标换「新草稿待确认」＋确认门回场＋时间流出指路横幅", () => {
    seedPrdProducedProject();
    // 停驻草稿只发生在已生成项目（#334 改需求回路：确认→生成→改稿回路）
    seed.detail = { ...seed.detail!, generatedAt: "2026-10-18T02:00:00Z" };
    seed.prdVersions = [{ versionNo: 2 }];
    seed.prd = { ...seed.prd!, draftChangeSummary: "新增「管理后台」" };
    render(<RequirementAnalysisPage projectId="p1" />);

    expect(screen.getByText("PRD 新草稿待确认 · 当前 v2")).toBeTruthy();
    expect(screen.getByRole("button", { name: /确认此版 PRD 并生成 Demo/ })).toBeTruthy();
    expect(screen.getByText("PRD 已更新，确认新版后生成下一版 Demo")).toBeTruthy();
  });

  it("「对话调优」：聚焦澄清输入", () => {
    seedPrdProducedProject();
    render(<RequirementAnalysisPage projectId="p1" />);

    fireEvent.click(screen.getByRole("button", { name: /对话调优/ }));

    expect(document.activeElement).toBe(screen.getByLabelText(/补充您的约束逻辑/));
  });
});
