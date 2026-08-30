"use client";

import { CircleCheck, CircleX, Play, Plus, Send, X } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { BugStatusBadge, SeverityBadge } from "@/components/tasks/task-badges";
import { SubmittedPayloadView } from "@/components/tasks/submitted-payload-view";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Textarea } from "@/components/ui/textarea";
import { useStartTask, useSubmitTask } from "@/hooks/use-tasks";
import { errorText } from "@/lib/api/api-error";
import {
  buildFirstRoundPayload,
  buildRetestPayload,
  isRetestTask,
  SEVERITY_OPTIONS,
  TASK_STATUS,
  type BugDraft,
  type TaskDetail,
} from "@/lib/tasks/task";
import { cn } from "@/lib/utils";

/**
 * Bug 工作区（issue #22，spec 0003 §2.3）：按任务状态分派——
 * 已发布 = 「开始测试」显式按钮；执行中 = 提交表单（首轮录入 / 复测勾选双形状，
 * 判别 = 详情 bugs[] 非空；被驳回重交预填 submittedPayload）；已提交 = 明细只读；
 * 已确认 / 已取消 = 终态说明。截图位降级生效（BugPayload 无 attachments，§2.5）。
 */
export function BugWorkspace({ detail }: { detail: TaskDetail }) {
  switch (detail.status) {
    case TASK_STATUS.PUBLISHED:
      return <StartTaskCard taskId={detail.taskId} />;
    case TASK_STATUS.RUNNING:
      return isRetestTask(detail.bugs) ? (
        <RetestForm taskId={detail.taskId} detail={detail} />
      ) : (
        <FirstRoundForm taskId={detail.taskId} detail={detail} />
      );
    case TASK_STATUS.SUBMITTED:
      return (
        <Card className="gap-3 py-4">
          <CardHeader>
            <CardTitle className="text-sm">测试报告已提交</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <SubmittedPayloadView payload={detail.submittedPayload} bugs={detail.bugs} />
            <p className="text-xs text-muted-foreground">等待项目方确认；被驳回会回到这里重交。</p>
          </CardContent>
        </Card>
      );
    case TASK_STATUS.CONFIRMED:
    case TASK_STATUS.CANCELLED:
      return (
        <Card className="gap-3 py-4">
          <CardContent className="text-sm text-muted-foreground">
            {detail.status === TASK_STATUS.CONFIRMED
              ? "任务已确认，辛苦。被确认通过的 Bug 修复与复测记录留在项目 Bug 面板。"
              : "任务已取消，无需再处理。"}
          </CardContent>
        </Card>
      );
    default:
      return null;
  }
}

/** 已发布 → 执行中（留痕）：开始测试显式按钮（仅指派本人，403 TASK_004 走 toast）。 */
function StartTaskCard({ taskId }: { taskId: string }) {
  const start = useStartTask(taskId);
  return (
    <Card className="gap-3 py-4">
      <CardHeader>
        <CardTitle className="text-sm">Bug 工作区</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-sm text-muted-foreground">
          对照右侧预览把任务过一遍，边测边记。准备好了就开始。
        </p>
        <Button
          onClick={() =>
            start.mutate(undefined, {
              onError: (error) => toast.error(errorText(error)),
            })
          }
          disabled={start.isPending}
        >
          <Play /> 开始测试
        </Button>
      </CardContent>
    </Card>
  );
}

/** 首轮形状：report + Bug 卡动态增删（空清单 = 测试全过）；驳回重交预填原 bugs。 */
function FirstRoundForm({ taskId, detail }: { taskId: string; detail: TaskDetail }) {
  const submit = useSubmitTask(taskId);
  const [report, setReport] = useState(detail.submittedPayload.report);
  const [bugs, setBugs] = useState<BugDraft[]>(
    () => detail.submittedPayload.bugs.map((bug) => ({ ...bug })),
  );

  const updateBug = (index: number, patch: Partial<BugDraft>) =>
    setBugs((prev) => prev.map((bug, i) => (i === index ? { ...bug, ...patch } : bug)));
  const removeBug = (index: number) => setBugs((prev) => prev.filter((_, i) => i !== index));
  const addBug = () =>
    setBugs((prev) => [...prev, { title: "", description: "", reproSteps: "", severity: 3 }]);

  const reportOk = report.trim() !== "";
  const bugsOk = bugs.every((bug) => bug.title.trim() !== "");
  const canSubmit = reportOk && bugsOk && !submit.isPending;

  const onSubmit = () => {
    if (!canSubmit) return;
    submit.mutate(buildFirstRoundPayload(report, bugs), {
      onSuccess: () => toast.success("测试报告已提交"),
      onError: (error) => toast.error(errorText(error)),
    });
  };

  return (
    <Card className="gap-3 py-4">
      <CardHeader>
        <CardTitle className="text-sm">Bug 工作区</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          {bugs.map((bug, index) => (
            <div key={index} className="space-y-2 rounded-lg border p-3">
              <div className="flex items-center gap-2">
                <Input
                  value={bug.title}
                  onChange={(e) => updateBug(index, { title: e.target.value })}
                  placeholder="Bug 标题（必填）"
                  className="h-8"
                  aria-invalid={bug.title.trim() === ""}
                />
                <NativeSelect
                  size="sm"
                  aria-label="严重级"
                  value={String(bug.severity)}
                  onChange={(e) => updateBug(index, { severity: Number(e.target.value) })}
                >
                  {SEVERITY_OPTIONS.map((option) => (
                    <NativeSelectOption key={option.value} value={String(option.value)}>
                      {option.label}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label="删除这条 Bug"
                  onClick={() => removeBug(index)}
                >
                  <X />
                </Button>
              </div>
              <Textarea
                value={bug.description}
                onChange={(e) => updateBug(index, { description: e.target.value })}
                placeholder="问题描述"
                rows={2}
              />
              <Textarea
                value={bug.reproSteps}
                onChange={(e) => updateBug(index, { reproSteps: e.target.value })}
                placeholder="复现步骤"
                rows={2}
              />
            </div>
          ))}
          <Button variant="outline" size="sm" onClick={addBug}>
            <Plus /> 添加 Bug
          </Button>
          {bugs.length === 0 && (
            <p className="text-xs text-muted-foreground">
              没发现 Bug 也可以直接提交——空清单即「测试全过」。
            </p>
          )}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="first-round-report">测试报告（必填）</Label>
          <Textarea
            id="first-round-report"
            value={report}
            onChange={(e) => setReport(e.target.value)}
            placeholder="这轮测了什么、结论如何"
            rows={4}
            aria-invalid={!reportOk}
          />
        </div>

        <div className="flex items-center gap-3">
          <Button onClick={onSubmit} disabled={!canSubmit}>
            <Send /> 提交测试报告
          </Button>
          {!bugsOk && (
            <p className="text-xs text-destructive">有 Bug 缺标题，补全或删除后再提交</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}

/** 复测形状：report + 逐 bugId 勾「复测通过 / 不通过」，不通过必填说明。 */
function RetestForm({ taskId, detail }: { taskId: string; detail: TaskDetail }) {
  const bugs = detail.bugs;
  const submit = useSubmitTask(taskId);
  const [report, setReport] = useState(detail.submittedPayload.report);
  // 驳回重交预填：按 bugId 回收上次勾选与说明
  const [verdicts, setVerdicts] = useState<Record<string, { pass: boolean | null; note: string }>>(
    () =>
      Object.fromEntries(
        bugs.map((bug) => {
          const prev = detail.submittedPayload.results.find((r) => r.bugId === bug.bugId);
          return [bug.bugId, { pass: prev ? prev.pass : null, note: prev?.note ?? "" }];
        }),
      ),
  );

  const setVerdict = (bugId: string, patch: Partial<{ pass: boolean | null; note: string }>) =>
    setVerdicts((prev) => ({ ...prev, [bugId]: { ...prev[bugId], ...patch } }));

  const allVoted = bugs.every((bug) => verdicts[bug.bugId]?.pass !== null);
  const notesOk = bugs.every(
    (bug) => verdicts[bug.bugId]?.pass !== false || verdicts[bug.bugId].note.trim() !== "",
  );
  const reportOk = report.trim() !== "";
  const canSubmit = reportOk && allVoted && notesOk && !submit.isPending;

  const onSubmit = () => {
    if (!canSubmit) return;
    submit.mutate(
      buildRetestPayload(
        report,
        bugs.map((bug) => ({
          bugId: bug.bugId,
          pass: verdicts[bug.bugId].pass === true,
          note: verdicts[bug.bugId].note,
        })),
      ),
      {
        onSuccess: () => toast.success("复测报告已提交"),
        onError: (error) => toast.error(errorText(error)),
      },
    );
  };

  return (
    <Card className="gap-3 py-4">
      <CardHeader>
        <CardTitle className="text-sm">Bug 工作区 · 复测</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="space-y-2">
          {bugs.map((bug) => {
            const verdict = verdicts[bug.bugId];
            return (
              <div key={bug.bugId} className="space-y-2 rounded-lg border p-3">
                <div className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate text-sm font-medium">
                    {bug.title || "未命名 Bug"}
                  </span>
                  <SeverityBadge severity={bug.severity} severityName={bug.severityName} />
                  <BugStatusBadge status={bug.status} statusName={bug.statusName} />
                </div>
                {bug.reproSteps && (
                  <p className="text-xs text-muted-foreground/80 whitespace-pre-wrap">
                    复现步骤：{bug.reproSteps}
                  </p>
                )}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant={verdict?.pass === true ? "default" : "outline"}
                    className={cn(verdict?.pass === true && "bg-emerald-600 hover:bg-emerald-600")}
                    onClick={() => setVerdict(bug.bugId, { pass: true })}
                  >
                    <CircleCheck /> 复测通过
                  </Button>
                  <Button
                    size="sm"
                    variant={verdict?.pass === false ? "destructive" : "outline"}
                    onClick={() => setVerdict(bug.bugId, { pass: false })}
                  >
                    <CircleX /> 不通过
                  </Button>
                </div>
                {verdict?.pass === false && (
                  <Textarea
                    value={verdict.note}
                    onChange={(e) => setVerdict(bug.bugId, { note: e.target.value })}
                    placeholder="不通过说明（必填）"
                    rows={2}
                    aria-invalid={verdict.note.trim() === ""}
                  />
                )}
              </div>
            );
          })}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="retest-report">复测报告（必填）</Label>
          <Textarea
            id="retest-report"
            value={report}
            onChange={(e) => setReport(e.target.value)}
            placeholder="这轮复测的结论"
            rows={3}
            aria-invalid={!reportOk}
          />
        </div>

        <div className="flex items-center gap-3">
          <Button onClick={onSubmit} disabled={!canSubmit}>
            <Send /> 提交复测报告
          </Button>
          {!allVoted && <p className="text-xs text-muted-foreground">每条 Bug 都要给出结论</p>}
          {allVoted && !notesOk && (
            <p className="text-xs text-destructive">不通过的 Bug 要填说明</p>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
