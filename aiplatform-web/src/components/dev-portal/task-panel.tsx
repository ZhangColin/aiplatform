"use client";

import { ChevronDown, ChevronUp, Plus } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";

import { TaskStatusBadge } from "@/components/tasks/task-badges";
import { SubmittedPayloadView } from "@/components/tasks/submitted-payload-view";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useAccounts } from "@/hooks/use-accounts";
import {
  useCancelTask,
  useConfirmTask,
  useCreateTask,
  useProjectBugs,
  useProjectTasks,
  useRejectTask,
} from "@/hooks/use-tasks";
import { errorText } from "@/lib/api/api-error";
import { buildCreateTaskCommand, byCreatedAtDesc, isRejectedTask, TASK_STATUS, type Bug, type Task } from "@/lib/tasks/task";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * dev 任务面板（issue #22，spec 0003 §2.7）：项目详情页主面板「任务」tab。
 * 上 = 建任务表单（title / content / 指派下拉，源 GET /api/accounts；开发段内
 * 这是开发 → 测试的唯一触发）；下 = 项目任务全量列表，已提交任务展开
 * submittedPayload 明细并给确认 / 驳回裁决（reject reason 必填），已发布 / 执行
 * 中可取消（已提交只能驳回，409 TASK_002 由后端守卫）。非项目 owner 403 TASK_009、
 * 指派账号不存在 404 TASK_008 走 toast 呈现。
 */
export function TaskPanel({ projectId }: { projectId: string }) {
  const tasks = useProjectTasks(projectId);
  const bugs = useProjectBugs(projectId);
  const items = byCreatedAtDesc(tasks.data ?? []);

  return (
    <div className="mx-auto max-w-3xl space-y-6 p-6">
      <CreateTaskCard projectId={projectId} />

      <section className="space-y-3">
        <h3 className="text-sm font-medium">全部任务</h3>
        {tasks.isPending ? (
          <div className="space-y-2">
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
          </div>
        ) : tasks.isError ? (
          <div className="flex items-center gap-3 rounded-xl border border-dashed py-8 text-sm text-muted-foreground">
            <p className="flex-1 text-center">{errorText(tasks.error, "任务列表加载失败")}</p>
          </div>
        ) : items.length === 0 ? (
          <div className="rounded-xl border border-dashed py-10 text-center text-sm text-muted-foreground">
            还没有测试任务
          </div>
        ) : (
          items.map((task) => (
            <TaskRow key={task.taskId} task={task} bugs={bugs.data ?? []} />
          ))
        )}
      </section>
    </div>
  );
}

/** 建任务表单：title / content / assigneeAccountId 均必填（swagger CreateTaskCommand）。 */
function CreateTaskCard({ projectId }: { projectId: string }) {
  const accounts = useAccounts();
  const create = useCreateTask(projectId);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [assigneeId, setAssigneeId] = useState("");

  const canSubmit =
    title.trim() !== "" && content.trim() !== "" && assigneeId !== "" && !create.isPending;

  const onSubmit = () => {
    if (!canSubmit) return;
    create.mutate(buildCreateTaskCommand(title, content, assigneeId), {
      onSuccess: () => {
        toast.success("测试任务已创建并指派");
        setTitle("");
        setContent("");
        setAssigneeId("");
      },
      onError: (error) => toast.error(errorText(error)),
    });
  };

  return (
    <Card className="gap-3 py-4">
      <CardHeader>
        <CardTitle className="text-sm">新建测试任务</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1.5">
          <Label htmlFor="create-task-title">任务标题</Label>
          <Input
            id="create-task-title"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            placeholder="例如：首页与下单流程回归"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="create-task-content">任务说明</Label>
          <Textarea
            id="create-task-content"
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="测什么、怎么算过；测试会照这个清单执行"
            rows={3}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="create-task-assignee">指派给</Label>
          <NativeSelect
            id="create-task-assignee"
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
        <Button onClick={onSubmit} disabled={!canSubmit}>
          <Plus /> 创建并指派
        </Button>
      </CardContent>
    </Card>
  );
}

/** 项目任务行：已提交可展开明细 + 确认 / 驳回；已发布 / 执行中可取消。 */
function TaskRow({ task, bugs }: { task: Task; bugs: Bug[] }) {
  const [expanded, setExpanded] = useState(false);
  const submitted = task.status === TASK_STATUS.SUBMITTED;
  const cancellable =
    task.status === TASK_STATUS.PUBLISHED || task.status === TASK_STATUS.RUNNING;

  return (
    <Card className="gap-3 py-4">
      <CardContent className="space-y-3">
        <div className="flex items-center gap-2">
          <span className="min-w-0 flex-1 truncate text-sm font-medium">
            {task.title || "未命名任务"}
          </span>
          <span className="shrink-0 text-xs text-muted-foreground">
            {task.assigneeName ? `指派给 ${task.assigneeName}` : ""}
          </span>
          <TaskStatusBadge task={task} />
          <span className="shrink-0 text-xs text-muted-foreground/70">
            {formatRelativeTime(task.createdAt)}
          </span>
          {submitted && (
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={expanded ? "收起明细" : "展开明细"}
              onClick={() => setExpanded((v) => !v)}
            >
              {expanded ? <ChevronUp /> : <ChevronDown />}
            </Button>
          )}
        </div>

        {submitted && expanded && (
          <div className="space-y-3 border-t pt-3">
            <SubmittedPayloadView payload={task.submittedPayload} bugs={bugs} />
            <DecisionActions task={task} />
          </div>
        )}

        {isRejectedTask(task.status, task.rejectReason) && (
          <p className="text-xs text-destructive">上次驳回理由：{task.rejectReason}</p>
        )}

        {cancellable && <CancelAction taskId={task.taskId} />}
      </CardContent>
    </Card>
  );
}

/** 确认 / 驳回裁决（已提交任务）：confirm 一事务内 Bug 入库 / 复测翻态；reject reason 必填。 */
function DecisionActions({ task }: { task: Task }) {
  const confirm = useConfirmTask();
  const reject = useRejectTask();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [rejectOpen, setRejectOpen] = useState(false);
  const [reason, setReason] = useState("");

  const onConfirm = () => {
    setConfirmOpen(false);
    confirm.mutate(task.taskId, {
      onSuccess: () => toast.success("已确认通过"),
      onError: (error) => toast.error(errorText(error)),
    });
  };

  const onReject = () => {
    if (reason.trim() === "") return;
    setRejectOpen(false);
    reject.mutate(
      { taskId: task.taskId, command: { reason: reason.trim() } },
      {
        onSuccess: () => {
          toast.success("已驳回，待测试重新提交");
          setReason("");
        },
        onError: (error) => toast.error(errorText(error)),
      },
    );
  };

  return (
    <div className="flex items-center gap-2">
      <Button size="sm" onClick={() => setConfirmOpen(true)}>
        确认通过
      </Button>
      <Button size="sm" variant="destructive" onClick={() => setRejectOpen(true)}>
        驳回
      </Button>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认通过「{task.title || "未命名任务"}」？</AlertDialogTitle>
            <AlertDialogDescription>
              确认后本次提交的 Bug 会入库、复测结果翻态，任务进入已确认。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>再想想</AlertDialogCancel>
            <AlertDialogAction onClick={onConfirm} disabled={confirm.isPending}>
              确认通过
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <Dialog open={rejectOpen} onOpenChange={setRejectOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>驳回「{task.title || "未命名任务"}」</DialogTitle>
            <DialogDescription>驳回理由会原样转给测试，任务回到执行中待重交。</DialogDescription>
          </DialogHeader>
          <Textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="驳回理由（必填）"
            rows={3}
            aria-invalid={reason.trim() === ""}
          />
          <DialogFooter>
            <Button variant="ghost" onClick={() => setRejectOpen(false)}>
              取消
            </Button>
            <Button
              variant="destructive"
              onClick={onReject}
              disabled={reason.trim() === "" || reject.isPending}
            >
              确认驳回
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** 取消任务（已发布 / 执行中）：二次确认；已提交只能驳回（409 TASK_002 后端守卫）。 */
function CancelAction({ taskId }: { taskId: string }) {
  const cancel = useCancelTask();
  const [open, setOpen] = useState(false);

  return (
    <div className="border-t pt-3">
      <Button size="sm" variant="ghost" className="text-muted-foreground" onClick={() => setOpen(true)}>
        取消任务
      </Button>
      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>取消这个任务？</AlertDialogTitle>
            <AlertDialogDescription>取消后任务终止，测试侧不再看到待办。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>再想想</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                setOpen(false);
                cancel.mutate(taskId, {
                  onSuccess: () => toast.success("任务已取消"),
                  onError: (error) => toast.error(errorText(error)),
                });
              }}
              disabled={cancel.isPending}
            >
              确认取消
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
