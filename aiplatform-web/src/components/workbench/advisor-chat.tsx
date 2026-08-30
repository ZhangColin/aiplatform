"use client";

import { useRef } from "react";

import { buildTaskCommand } from "@/lib/agent/task-command";
import { WaitCard } from "@/components/agent/wait-card";
import { GateCard, type GateCardCopy } from "@/components/main-chain/gate-card";
import { userGateCopy, type UserGateCopy } from "@/lib/main-chain/gate-copy";
import { isGateReady } from "@/lib/main-chain/project";
import { isQuestionWait, type Wait } from "@/lib/agent/wait";
import {
  latestProjectRun,
  useAgentStreamsStore,
} from "@/lib/store/agent-streams";
import { useProjectJourney } from "@/hooks/use-project";
import { useProjectWaits } from "@/hooks/use-waits";

import { ScrollArea } from "@/components/ui/scroll-area";
import { Button } from "@/components/ui/button";
import { useProjectNoticesStore } from "@/lib/store/project-notices";

import type { ChatFocus } from "./chat-mode";
import { MessageFeed } from "./message-feed";
import { PromptComposer } from "./prompt-composer";
import { useScrollFocusOnce } from "./scroll-focus";

/**
 * 顾问对话（spec 0002 §4，issue #43）：需求端单模式 Agent 区——顾问消息流 + 门卡
 * 嵌流底（三扇门禁词口径，验收门走专门验收动作）+ 底部补充需求输入。消息流复用
 * 开发平台同一读口（streams store），差异只在下任务 vs 补充需求输入、门卡文案。
 * 问答（issue #52 访谈循环，Replit 式内联）：问题从流的 wait 分段渲染成顾问消息、
 * 选项以 chip 内联（waitSlot 只补可点选项，题干走分段，variant=advisor 去 dev 胶囊）；
 * 作答成功 → 答案作为「你」的右泡写进流（appendUserSegment），chip 随 waits 重查
 * 消解、SSE wait-settled 链路不动；无对应分段的跨会话遗留流底兜底挂整卡（题干+选项）。
 * 深链聚焦（issue #49，同 ChatMode「只滚一次」语义）：wait → 问答卡 / 等待胶囊
 * （`data-wait-id`）、gate → 流底门卡（`data-focus-gate`）。
 * PRD 更新提示胶囊（issue #54）：`document-updated` SSE 置位（桥写入
 * project-notices），输入条上方提示「文档」页有新版；点击即确认清除，文档内容
 * 由失效重拉自动更新（失效为主，ADR 0003）。
 */

/** 用户侧门卡文案（禁词红线，spec 0002 §5）：门名 → GateCard copy 的翻译收口。 */
function advisorGateCardCopy(gate: UserGateCopy): Partial<GateCardCopy> {
  const recipient = gate.label === "验收" ? "团队" : "顾问";
  return {
    title: "需要你拍板",
    heading: gate.waiting,
    showActor: false,
    approveLabel: gate.approve,
    rejectToggleLabel: gate.reject,
    rejectPlaceholder: `意见（必填），将原样转给${recipient}`,
    rejectSubmitLabel: "提交",
    cancelLabel: "先不提了",
    approvedToast: gate.label === "验收" ? "已验收" : "已确认",
    rejectedToast: `意见已转给${recipient}`,
    footer: "确认后继续推进；想调整的地方提出来，会改好后再请你确认。",
  };
}

export function AdvisorChat({
  projectId,
  focus,
}: {
  projectId: string;
  focus?: ChatFocus;
}) {
  const run = useAgentStreamsStore((s) => latestProjectRun(s, projectId));
  const { data: detail, current } = useProjectJourney(projectId);
  const prdUpdated = useProjectNoticesStore((s) => s.notices[projectId]?.documentUpdate === true);
  const ackPrdUpdate = useProjectNoticesStore((s) => s.ackDocumentUpdate);
  const gateCopy = userGateCopy(current?.gateLabel);
  // 挂卡三条件（#58 收口）：详情 gate 存在 ∧ 就绪（ready 显式 true，缺失 = 未
  // 就绪）∧ 用户侧门名映射齐备——访谈期 / PRD 未写出时不挂卡，聊天区只剩消息流
  // 与问答卡；PRD 写出 → document-updated 连带 projects 失效 → 重拉翻转即挂。
  const gateMounted = isGateReady(detail?.gate) && gateCopy !== null;
  // PENDING 问答等待点（跨会话口径，useProjectWaits 只回待处理）：权限等待点不
  // 出现在需求端（顾问只发问答），过滤即双保险。
  const waits = useProjectWaits(projectId);
  const questionWaits = (waits.data ?? []).filter(isQuestionWait);

  // 深链聚焦「只滚一次」（useScrollFocusOnce，与对话模式共用）：问答卡在流内
  // 分段位 / 流底，waits 未到（isPending）不尝试。
  const feedRef = useRef<HTMLDivElement>(null);
  useScrollFocusOnce(focus, feedRef, { enabled: !waits.isPending });

  // wait 分段位挂卡：琥珀胶囊保留为流内提示（票面要求），卡紧随其下；作答成功
  // → waits 失效重查 → 卡随 PENDING 消失，胶囊与 wait-settled 分段留存。
  const waitSlot = (waitId: string) => {
    const wait = questionWaits.find((w) => w.waitId === waitId);
    if (!wait) return null;
    // 题干由流的 wait 分段渲染成顾问消息，这里只补可点选项（optionsOnly）
    return <AdvisorWaitCard projectId={projectId} wait={wait} focus={focus} optionsOnly />;
  };

  // 流底兜底：PENDING 问答在当前流内无对应分段（跨会话遗留 / 刷新后流不在）→
  // 挂流底（与门卡并列），待办深链仍可达。
  const segmentWaitIds = new Set(
    (run?.segments ?? [])
      .filter((segment) => segment.kind === "wait")
      .map((segment) => segment.waitId),
  );
  const footerWaits = questionWaits.filter((wait) => !segmentWaitIds.has(wait.waitId));

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-1 border-b px-2 text-sm font-medium">
        顾问对话
      </div>
      <div ref={feedRef} className="flex min-h-0 flex-1 flex-col">
        <ScrollArea className="min-h-0 flex-1">
          <MessageFeed
            run={run}
            variant="advisor"
            empty={
              <>
                <p>把想做的事告诉顾问，顾问会陪你把需求聊清楚</p>
                <p className="text-xs text-muted-foreground/70">
                  聊清楚后做好原型请你确认，最后做好系统请你验收
                </p>
              </>
            }
            waitSlot={waitSlot}
            footer={
              footerWaits.length > 0 || gateMounted ? (
                <div className="space-y-3">
                  {footerWaits.map((wait) => (
                    <AdvisorWaitCard
                      key={wait.waitId}
                      projectId={projectId}
                      wait={wait}
                      focus={focus}
                    />
                  ))}
                  {gateMounted && detail?.gate && gateCopy ? (
                    // 三条件判定见上（#58）：`detail.gate` 就绪 + `userGateCopy` 用户侧
                    // 门名映射。开发平台侧门（如「开发完成」）在用户视角折叠（spec
                    // 0002 §5 质检折叠），无用户门名即不嵌门卡——与开发平台右栏/流底
                    // 的决策门有意分叉。
                    <div data-focus-gate={focus?.kind === "gate" ? "true" : undefined}>
                      <GateCard
                        projectId={projectId}
                        stageLabel={gateCopy.label}
                        gate={detail.gate}
                        copy={advisorGateCardCopy(gateCopy)}
                      />
                    </div>
                  ) : null}
                </div>
              ) : null
            }
          />
        </ScrollArea>
      </div>
      {prdUpdated ? (
        // 提示胶囊贴输入条上方（不随流滚动）；点击 = 知道了
        <div className="flex shrink-0 justify-center border-t bg-background px-3 pt-2">
          <Button
            size="sm"
            variant="secondary"
            className="text-amber-600"
            onClick={() => ackPrdUpdate(projectId)}
          >
            PRD 有更新 · 去「文档」页看看
          </Button>
        </div>
      ) : null}
      <div className="border-t bg-background p-3">
        <PromptComposer
          projectId={projectId}
          placeholder="补充需求或提问，想改的地方请具体说明…（Enter 发送）"
          errorLabel="发送失败"
          buildCommand={buildTaskCommand}
        />
      </div>
    </div>
  );
}

/** 需求端问答卡（issue #52）：WaitCard advisor 变体（裁审批体与转任务），深链命中 waitId 加 ring。 */
function AdvisorWaitCard({
  projectId,
  wait,
  focus,
  optionsOnly,
}: {
  projectId: string;
  wait: Wait;
  focus?: ChatFocus;
  /** 只渲染选项 chip（题干由流的 wait 分段渲染成顾问消息）。 */
  optionsOnly?: boolean;
}) {
  return (
    <WaitCard
      projectId={projectId}
      wait={wait}
      variant="advisor"
      optionsOnly={optionsOnly}
      highlight={focus?.kind === "wait" && focus.waitId === wait.waitId}
    />
  );
}
