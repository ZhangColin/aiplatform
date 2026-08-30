"use client";

import { useRef, useState } from "react";
import { Check, CircleAlert } from "lucide-react";

import { WaitCard } from "@/components/agent/wait-card";
import { AGENT_ROLES, buildTaskCommand, roleByCode } from "@/lib/agent/task-command";
import { GateCard } from "@/components/main-chain/gate-card";
import {
  latestProjectRun,
  useAgentStreamsStore,
  type AgentRun,
} from "@/lib/store/agent-streams";
import { formatElapsed } from "@/lib/utils/time";
import { isGateReady } from "@/lib/main-chain/project";
import { useProjectJourney } from "@/hooks/use-project";
import { useProjectWaits } from "@/hooks/use-waits";

import { Badge } from "@/components/ui/badge";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Spinner } from "@/components/ui/spinner";

import { MessageFeed } from "./message-feed";
import { PromptComposer } from "./prompt-composer";
import { useRunElapsed } from "./run-elapsed";
import { useScrollFocusOnce } from "./scroll-focus";

/** 对话模式深链聚焦（issue #44）：定位等待点 / 门卡。 */
export type ChatFocus = { kind: "wait"; waitId: string } | { kind: "gate" };

/**
 * 对话模式（spec 0001 §4.1，issue #40/#43/#45）：消息流自上而下 + HITL 等待点卡
 * 与门卡并列嵌流底 + 底部运行条（任务号 + 计时，终止占位已随 #59 下架）+ 下任务输入框（可选
 * 角色卡 1–6，缺省取阶段默认）。消息流只读 streams store（桥唯一写入方），切 tab
 * 不丢状态；驳回理由由 StageRejectionBanner 在 Agent 区顶呈现。深链 `focus` 命中
 * 时给对应卡加 ring 高亮并滚动到视口中心（#44 消费 waitId）。
 */
export function ChatMode({ projectId, focus }: { projectId: string; focus?: ChatFocus }) {
  // 直播视图读口复用：该项目插入序最近的一个 run（#23）。返回引用稳定，run 内容
  // 变更（分段追加）时 store 替换 run 对象 → 自然触发本组件重渲染。
  const run = useAgentStreamsStore((s) => latestProjectRun(s, projectId));
  const { data: detail } = useProjectJourney(projectId);
  const waits = useProjectWaits(projectId);
  const footerRef = useRef<HTMLDivElement>(null);
  // 深链聚焦「只滚一次」（useScrollFocusOnce，与顾问对话共用）：等待卡在 footer，
  // 数据未到（isPending）不尝试。
  useScrollFocusOnce(focus, footerRef, { enabled: !waits.isPending });

  const gate = detail?.gate ?? null;
  const footer = (
    <div ref={footerRef} className="space-y-3">
      {(waits.data ?? []).map((wait) => (
        <WaitCard
          key={wait.waitId}
          projectId={projectId}
          wait={wait}
          highlight={focus?.kind === "wait" && focus.waitId === wait.waitId}
        />
      ))}
      {/* 就绪才挂（#58 收口，同顾问对话口径）：门未就绪不挂卡，流底只剩等待卡 */}
      {isGateReady(gate) && (
        <div data-focus-gate={focus?.kind === "gate" ? "true" : undefined}>
          <GateCard projectId={projectId} stageLabel={detail?.stageLabel ?? ""} gate={gate} />
        </div>
      )}
    </div>
  );

  return (
    <div className="flex h-full min-h-0 flex-col">
      <ScrollArea className="min-h-0 flex-1">
        <MessageFeed run={run} footer={footer} />
      </ScrollArea>
      <div className="border-t bg-background p-3">
        {run && <RunningBar run={run} />}
        <TaskInput projectId={projectId} />
      </div>
    </div>
  );
}

// ── 运行条 + 下任务输入 ────────────────────────────────────────────────────

/** 运行条（#40）：任务号 + 计时（useRunElapsed 锚 startedAt）+ 终态徽章；终止占位随 #59 下架。 */
function RunningBar({ run }: { run: AgentRun }) {
  const elapsed = useRunElapsed(run);
  const finished = run.status === "finished";
  const failed = run.status === "error";

  return (
    <div className="mb-2 flex items-center gap-2 text-xs text-muted-foreground">
      {failed ? (
        <CircleAlert className="size-3.5 text-destructive" />
      ) : finished ? (
        <Check className="size-3.5 text-emerald-600" />
      ) : (
        <Spinner className="size-3.5 text-primary" />
      )}
      <span className="font-mono font-medium text-foreground">{run.runId}</span>
      <span className="font-mono tabular-nums">{formatElapsed(elapsed)}</span>
      {finished && <Badge variant="secondary" className="h-5">已完成</Badge>}
      {failed && <Badge variant="destructive" className="h-5">已出错</Badge>}
    </div>
  );
}

/** 下任务输入框：prompt 必填；角色卡可选（缺省「阶段默认」= 不携带 role）。 */
function TaskInput({ projectId }: { projectId: string }) {
  const [role, setRole] = useState("default");

  return (
    <PromptComposer
      projectId={projectId}
      placeholder="给智能体下任务…（Enter 发送，Shift+Enter 换行）"
      errorLabel="下任务失败"
      buildCommand={(prompt) => {
        // 缺省语义：选「阶段默认」→ 省略 role；显式角色卡 → 经 roleByCode 校验后携带（词表外值不落到载荷）
        const selected = role === "default" ? undefined : roleByCode(Number(role))?.code;
        return buildTaskCommand(prompt, selected);
      }}
      roleSelect={
        <NativeSelect
          value={role}
          onChange={(e) => setRole(e.target.value)}
          aria-label="角色卡"
          className="w-32 shrink-0"
        >
          <NativeSelectOption value="default">阶段默认</NativeSelectOption>
          {AGENT_ROLES.map((r) => (
            <NativeSelectOption key={r.code} value={String(r.code)}>
              {r.label}
            </NativeSelectOption>
          ))}
        </NativeSelect>
      }
    />
  );
}
