"use client";

import { CircleStop, Check, Send, ShieldCheck, Undo2, UserPlus } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { AgentAvatar } from "@/components/agent/agent-avatar";
import { badgeToneClass } from "@/components/badges";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { Separator } from "@/components/ui/separator";
import { Textarea } from "@/components/ui/textarea";
import { useAccounts } from "@/hooks/use-accounts";
import { useSettleWait } from "@/hooks/use-waits";
import {
  buildAnswerCommand,
  buildDeferredCommand,
  buildPermissionCommand,
  isQuestionWait,
  narrowPermissionBody,
  narrowQuestionBody,
  waitKindLabel,
  type Wait,
  type WaitQuestion,
} from "@/lib/agent/wait";
import { errorText } from "@/lib/api/api-error";
import { useAgentStreamsStore } from "@/lib/store/agent-streams";
import { formatRelativeTime } from "@/lib/utils/time";
import { cn } from "@/lib/utils";

/**
 * HITL 等待点卡（issue #45，spec 0001 §5）：对话模式嵌流底 + 待处理队列共用的
 * 卡体。按 kind 分派问答卡 / 审批卡；settle 三型载荷由 lib 构造（answer 二维 /
 * permission 布尔 / deferred 转任务）。审批卡带「终止任务」逃生口（动作待后端
 * 终止端点 aiplatform-server#38，UI 就位、降级为提示）。卡底常驻「转任务」入口
 * 把等待点交给 OPC 测试。settle 成功后经 `onSettled` 通知父层收卡。
 *
 * 需求端变体（issue #52，spec 0002 §4 访谈循环）：`variant="advisor"` 恒走问答
 * 体，裁掉审批体与「转任务」——权限等待点不出现在需求端（挂载层亦过滤，双保险）。
 */

export function WaitCard({
  projectId,
  wait,
  onSettled,
  highlight,
  variant = "dev",
  optionsOnly = false,
}: {
  projectId: string;
  wait: Wait;
  onSettled?: (waitId: string) => void;
  /** 深链定位：命中 waitId 时加 ring 高亮（父层滚动到此卡）。 */
  highlight?: boolean;
  /** 场景变体：dev = 全量卡片（问答/审批分派 + 转任务）；advisor = 需求端内联问答（Replit 式，去卡壳）。 */
  variant?: "dev" | "advisor";
  /** advisor 变体：只渲染选项 chip——题干已由流的 wait 分段渲染成顾问消息，这里只补可点选项。 */
  optionsOnly?: boolean;
}) {
  if (variant === "advisor") {
    return (
      <AdvisorQuestion
        projectId={projectId}
        wait={wait}
        onSettled={onSettled}
        highlight={highlight}
        optionsOnly={optionsOnly}
      />
    );
  }

  const kindLabel = waitKindLabel(wait.kind);
  const time = formatRelativeTime(wait.raisedAt);

  return (
    <Card
      data-wait-id={wait.waitId}
      className={cn("gap-3 border-amber-500/40 py-4", highlight && "ring-2 ring-primary/60")}
    >
      <CardHeader className="gap-1">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Badge variant="secondary" className={cn("h-5", badgeToneClass("amber"))}>
            {kindLabel}
          </Badge>
          {wait.runId && <span className="font-mono">{wait.runId}</span>}
          {time && <span className="ml-auto shrink-0">{time}</span>}
        </div>
        {wait.summary && <CardTitle className="text-sm font-medium">{wait.summary}</CardTitle>}
      </CardHeader>
      <CardContent className="space-y-3">
        {isQuestionWait(wait) ? (
          <QuestionCardBody
            projectId={projectId}
            wait={wait}
            onSettled={onSettled}
            questionOnly={false}
          />
        ) : (
          <PermissionCardBody projectId={projectId} wait={wait} onSettled={onSettled} />
        )}
        <Separator />
        <DeferredAction projectId={projectId} wait={wait} onSettled={onSettled} />
      </CardContent>
    </Card>
  );
}

// ── 需求端内联问答（advisor，Replit 式呈现）────────────────────────────────

/**
 * 顾问对话的问答呈现（variant="advisor"，issue #52 呈现修订）：不做 Card 外壳、
 * 无琥珀描边 / 顶部 badge / 标题——「问题 + 选项」作为顾问的一条消息内联进流
 * （spec 0002 §4 访谈循环，对齐 Replit 的提问方式）。选项为圆角按钮 chip；仅一题
 * 且单选无自定义时点选即答（少一步提交），多选 / 自定义保留「提交回答」确认钮。
 * settle 载荷不变（buildAnswerCommand 同一 answers 二维），只改呈现与触发时机。
 */
function AdvisorQuestion({
  projectId,
  wait,
  onSettled,
  highlight,
  optionsOnly = false,
}: {
  projectId: string;
  wait: Wait;
  onSettled?: (waitId: string) => void;
  highlight?: boolean;
  /** 只渲染选项 chip（题干已由流的 wait 分段渲染成顾问消息）。 */
  optionsOnly?: boolean;
}) {
  const settle = useSettleWait(projectId);
  const appendUserSegment = useAgentStreamsStore((s) => s.appendUserSegment);
  const { questions } = narrowQuestionBody(wait.body);
  const [picked, setPicked] = useState<Record<number, string[]>>({});
  const [customs, setCustoms] = useState<Record<number, string>>({});

  const buildAnswers = () =>
    questions.map((_, qi) => {
      const labels = [...(picked[qi] ?? [])];
      const custom = (customs[qi] ?? "").trim();
      if (custom) labels.push(custom);
      return labels;
    });

  const submit = (answers: string[][]) => {
    const answerText = answers.flat().filter(Boolean).join("、");
    settle.mutate(
      { waitId: wait.waitId, command: buildAnswerCommand(answers) },
      {
        onSuccess: () => {
          // 问答即对话：把用户选的选项作为「你」的右泡写进流（选项只是少打字）
          if (answerText && wait.runId) {
            appendUserSegment({ runId: wait.runId, projectId }, answerText);
          }
          toast.success("已提交回答");
          onSettled?.(wait.waitId);
        },
        onError: (error) => toast.error(errorText(error, "提交回答失败")),
      },
    );
  };

  if (questions.length === 0) {
    return (
      <p data-wait-id={wait.waitId} className="text-xs text-muted-foreground">
        这条提问的内容没能加载出来，请联系顾问
      </p>
    );
  }

  const toggle = (qi: number, label: string, multiple: boolean) =>
    setPicked((prev) => {
      const arr = prev[qi] ?? [];
      return {
        ...prev,
        [qi]: multiple
          ? arr.includes(label)
            ? arr.filter((x) => x !== label)
            : [...arr, label]
          : [label],
      };
    });

  // 快速作答：仅一题、单选、无自定义 → 点选即答（Replit 式单问快答）
  const quick = questions.length === 1 && !questions[0].multiple && !questions[0].custom;

  const allAnswered = questions.every(
    (q, qi) =>
      (picked[qi]?.length ?? 0) > 0 || (q.custom && (customs[qi] ?? "").trim() !== ""),
  );

  const select = (q: WaitQuestion, qi: number, label: string) => {
    if (q.multiple) {
      toggle(qi, label, true);
      return;
    }
    if (quick) {
      submit([[label]]);
      return;
    }
    toggle(qi, label, false);
  };

  return (
    <div
      data-wait-id={wait.waitId}
      className={cn(
        "flex max-w-[92%] flex-col gap-4",
        highlight && "rounded-xl p-2 ring-2 ring-primary/60",
      )}
    >
      {questions.map((q, qi) => (
        <div key={qi} className="space-y-2">
          {!optionsOnly && (
            <div className="flex items-start gap-2.5">
              <AgentAvatar className="mt-0.5" />
              <p className="min-w-0 text-sm leading-relaxed">
                <span className="font-medium">{q.header}</span>
                {q.question && <span className="text-muted-foreground">　{q.question}</span>}
              </p>
            </div>
          )}
          <div className="flex flex-wrap gap-2">
            {q.options.map((opt) => {
              const checked = (picked[qi] ?? []).includes(opt.label);
              return (
                <button
                  key={opt.label}
                  type="button"
                  aria-pressed={checked}
                  onClick={() => select(q, qi, opt.label)}
                  className={cn(
                    "flex flex-col items-start gap-0.5 rounded-xl border px-3 py-2 text-left text-sm transition-colors",
                    checked
                      ? "border-primary bg-primary text-primary-foreground"
                      : "border-border hover:bg-muted",
                  )}
                >
                  <span>{opt.label}</span>
                  {opt.description ? (
                    <span
                      className={cn(
                        "text-xs",
                        checked ? "text-primary-foreground/75" : "text-muted-foreground",
                      )}
                    >
                      {opt.description}
                    </span>
                  ) : null}
                </button>
              );
            })}
          </div>
          {q.custom && (
            <Input
              value={customs[qi] ?? ""}
              onChange={(e) => setCustoms((prev) => ({ ...prev, [qi]: e.target.value }))}
              placeholder="也可以直接输入你自己的答案…"
              className="h-9"
            />
          )}
        </div>
      ))}
      {!quick && (
        <div className="flex justify-end">
          <Button
            size="sm"
            onClick={() => submit(buildAnswers())}
            disabled={!allAnswered || settle.isPending}
          >
            <Send className="size-4" />
            {allAnswered ? "提交回答" : "每题选一个（或填写）后可提交"}
          </Button>
        </div>
      )}
    </div>
  );
}

// ── 问答卡（demo pendingQuestions 形状：一卡多题、单选/多选/自定义）──────────

function QuestionCardBody({
  projectId,
  wait,
  onSettled,
  questionOnly,
}: {
  projectId: string;
  wait: Wait;
  onSettled?: (waitId: string) => void;
  /** advisor 变体：空题兜底走用户话术（spec 0002 §5 用户侧禁技术词）。 */
  questionOnly: boolean;
}) {
  const settle = useSettleWait(projectId);
  const { questions } = narrowQuestionBody(wait.body);
  const [picked, setPicked] = useState<Record<number, string[]>>({});
  const [customs, setCustoms] = useState<Record<number, string>>({});

  if (questions.length === 0) {
    return (
      <p className="text-xs text-muted-foreground">
        {questionOnly ? "这条提问的内容没能加载出来，请联系顾问" : "等待点无可答内容（引擎载荷未含问题）"}
      </p>
    );
  }

  const toggle = (qi: number, label: string, multiple: boolean) =>
    setPicked((prev) => {
      const arr = prev[qi] ?? [];
      return {
        ...prev,
        [qi]: multiple
          ? arr.includes(label)
            ? arr.filter((x) => x !== label)
            : [...arr, label]
          : [label],
      };
    });

  const allAnswered = questions.every(
    (q, qi) =>
      (picked[qi]?.length ?? 0) > 0 || (q.custom && (customs[qi] ?? "").trim() !== ""),
  );

  const submit = () => {
    const answers = questions.map((_, qi) => {
      const labels = [...(picked[qi] ?? [])];
      const custom = (customs[qi] ?? "").trim();
      if (custom) labels.push(custom);
      return labels;
    });
    settle.mutate(
      { waitId: wait.waitId, command: buildAnswerCommand(answers) },
      {
        onSuccess: () => {
          toast.success("已提交回答");
          onSettled?.(wait.waitId);
        },
        onError: (error) => toast.error(errorText(error, "提交回答失败")),
      },
    );
  };

  return (
    <div className="space-y-4">
      {questions.map((q, qi) => (
        <div key={qi} className="space-y-2">
          <p className="text-sm leading-relaxed">
            <span className="font-medium">{q.header}</span>
            {q.question && <span className="text-muted-foreground">　{q.question}</span>}
          </p>
          <div className="space-y-2">
            {q.options.map((opt) => {
              const checked = (picked[qi] ?? []).includes(opt.label);
              return (
                <label
                  key={opt.label}
                  className={cn(
                    "flex cursor-pointer items-start gap-2.5 rounded-lg border px-3 py-2 text-sm transition-colors hover:bg-muted/50",
                    checked && "border-primary/50 bg-primary/5",
                  )}
                >
                  {q.multiple ? (
                    <Checkbox
                      checked={checked}
                      onCheckedChange={() => toggle(qi, opt.label, true)}
                      className="mt-0.5 size-4"
                    />
                  ) : (
                    <RadioGroup
                      value={picked[qi]?.[0] ?? ""}
                      onValueChange={(v) => toggle(qi, v, false)}
                      className="mt-0.5 gap-0"
                    >
                      <RadioGroupItem value={opt.label} className="size-4" />
                    </RadioGroup>
                  )}
                  <span className="flex-1">
                    {opt.label}
                    {opt.description ? (
                      <span className="block text-xs text-muted-foreground">{opt.description}</span>
                    ) : null}
                  </span>
                </label>
              );
            })}
          </div>
          {q.custom && (
            <Input
              value={customs[qi] ?? ""}
              onChange={(e) => setCustoms((prev) => ({ ...prev, [qi]: e.target.value }))}
              placeholder="也可以直接输入你自己的答案…"
              className="h-9"
            />
          )}
        </div>
      ))}
      <div className="flex justify-end">
        <Button size="sm" onClick={submit} disabled={!allAnswered || settle.isPending}>
          <Send className="size-4" />
          {allAnswered ? "提交回答" : "每题选一个（或填写）后可提交"}
        </Button>
      </div>
    </div>
  );
}

// ── 审批卡（工具 + 入参 pre + 允许/拒绝 + 过期 + 终止逃生口）────────────────

function PermissionCardBody({
  projectId,
  wait,
  onSettled,
}: {
  projectId: string;
  wait: Wait;
  onSettled?: (waitId: string) => void;
}) {
  const settle = useSettleWait(projectId);
  const body = narrowPermissionBody(wait.body);

  const decide = (approve: boolean) => {
    settle.mutate(
      { waitId: wait.waitId, command: buildPermissionCommand(approve) },
      {
        onSuccess: () => {
          toast.success(approve ? "已允许" : "已拒绝");
          onSettled?.(wait.waitId);
        },
        onError: (error) => toast.error(errorText(error, "审批操作失败")),
      },
    );
  };

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <ShieldCheck className="size-4 text-violet-600" />
        {body.tool && <span className="font-medium">{body.tool}</span>}
        {body.args && (
          <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs">{body.args}</code>
        )}
        <span className="text-xs text-muted-foreground">
          {body.expiresInMin} 分钟内未处理自动过期
          {body.reason && ` · ${body.reason}`}
        </span>
      </div>
      <div className="flex flex-wrap justify-end gap-2">
        <Button size="sm" variant="outline" onClick={() => decide(false)} disabled={settle.isPending}>
          <Undo2 /> 拒绝
        </Button>
        <Button size="sm" onClick={() => decide(true)} disabled={settle.isPending}>
          <Check /> 允许
        </Button>
        <Button
          size="sm"
          variant="ghost"
          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
          onClick={() => toast.warning("终止任务待后端终止端点接入（aiplatform-server#38）")}
        >
          <CircleStop /> 终止任务
        </Button>
      </div>
    </div>
  );
}

// ── 转任务（deferred）：把等待点交给 OPC 测试，而非现在答复 ─────────────────

function DeferredAction({
  projectId,
  wait,
  onSettled,
}: {
  projectId: string;
  wait: Wait;
  onSettled?: (waitId: string) => void;
}) {
  const accounts = useAccounts();
  const settle = useSettleWait(projectId);
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [assigneeId, setAssigneeId] = useState("");

  // deferred 必填 title + assignee（spec 0001 §5「deferred 必填 assignee 校验」）
  const canSubmit = title.trim() !== "" && assigneeId !== "" && !settle.isPending;

  const submit = () => {
    if (!canSubmit) return;
    settle.mutate(
      { waitId: wait.waitId, command: buildDeferredCommand(title, content, assigneeId) },
      {
        onSuccess: () => {
          setOpen(false);
          toast.success("已转任务");
          onSettled?.(wait.waitId);
        },
        onError: (error) => toast.error(errorText(error, "转任务失败")),
      },
    );
  };

  return (
    <>
      <Button
        size="sm"
        variant="ghost"
        className="text-muted-foreground hover:text-foreground"
        onClick={() => setOpen(true)}
      >
        <UserPlus className="size-4" /> 转任务
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>转任务</DialogTitle>
            <DialogDescription>把这件事交给 OPC 测试去做，而不是现在答复</DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor="deferred-title">任务标题</Label>
              <Input
                id="deferred-title"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="例如：首页与下单流程回归"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="deferred-content">任务说明（可选）</Label>
              <Textarea
                id="deferred-content"
                value={content}
                onChange={(e) => setContent(e.target.value)}
                placeholder="测什么、怎么算过"
                rows={3}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="deferred-assignee">指派给</Label>
              <NativeSelect
                id="deferred-assignee"
                className="w-full"
                value={assigneeId}
                onChange={(e) => setAssigneeId(e.target.value)}
                disabled={accounts.isPending}
              >
                <NativeSelectOption value="" disabled>
                  {accounts.isPending ? "加载成员中…" : "选择测试人员"}
                </NativeSelectOption>
                {(accounts.data ?? []).map((account) => (
                  <NativeSelectOption key={account.accountId} value={account.accountId}>
                    {account.displayName || account.accountId}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </div>
          </div>
          <DialogFooter>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button onClick={submit} disabled={!canSubmit}>
              转任务
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
