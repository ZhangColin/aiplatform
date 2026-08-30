"use client";

import { useState } from "react";
import { toast } from "sonner";

import { BugStatusBadge, SeverityBadge } from "@/components/tasks/task-badges";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { useCloseBug, useDispatchFixes, useProjectBugs } from "@/hooks/use-tasks";
import { errorText } from "@/lib/api/api-error";
import { BUG_STATUS, byCreatedAtDesc, countOpenBugs, type Bug } from "@/lib/tasks/task";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * dev Bug 面板（issue #22，spec 0003 §2.7）：项目详情页主面板「Bug」tab。
 * 三态行（待修复 / 已修复 / 复测通过）+ 严重级；fixRunId / fixNote 内容随后端
 * #27（字段占位，不深呈现）。实时性 = task-updated / 修复回填事件 → 桥失效。
 *
 * #38：补齐测试循环最后两个 mutation——逐条关闭（OPEN/FIXED → VERIFIED，reason
 * 必填）+「派发修复」（幂等手动，触发后待修复 Bug 进修复链、agent 流可见、开发
 * 完成门随后端解锁）。派发钮 = 有 OPEN Bug 才可点（后端空转 in-flight 守卫）。
 */
export function BugPanel({ projectId }: { projectId: string }) {
  const bugs = useProjectBugs(projectId);
  const dispatch = useDispatchFixes(projectId);
  const items = byCreatedAtDesc(bugs.data ?? []);
  const openCount = countOpenBugs(bugs.data ?? []);

  if (bugs.isPending) {
    return (
      <div className="mx-auto max-w-3xl space-y-2 p-6">
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  }

  if (bugs.isError) {
    return (
      <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
        <p>{errorText(bugs.error, "Bug 列表加载失败")}</p>
        <Button variant="outline" size="sm" onClick={() => void bugs.refetch()}>
          重试
        </Button>
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <div className="rounded-xl border border-dashed py-10 text-center text-sm text-muted-foreground">
          暂无 Bug——测试提交的 Bug 确认后会出现在这里
        </div>
      </div>
    );
  }

  const onDispatch = () => {
    dispatch.mutate(undefined, {
      onSuccess: () => toast.success("已派发修复，待修复 Bug 进入修复流程"),
      onError: (error) => toast.error(errorText(error)),
    });
  };

  return (
    <div className="mx-auto max-w-3xl p-6">
      <div className="mb-3 flex items-center justify-between gap-2">
        <h3 className="text-sm font-medium">Bug</h3>
        <Button size="sm" onClick={onDispatch} disabled={openCount === 0 || dispatch.isPending}>
          派发修复{openCount > 0 ? `（${openCount}）` : ""}
        </Button>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Bug</TableHead>
            <TableHead className="w-20">严重级</TableHead>
            <TableHead className="w-24">状态</TableHead>
            <TableHead className="w-28 text-right">提交时间</TableHead>
            <TableHead className="w-20 text-right">操作</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {items.map((bug) => (
            <TableRow key={bug.bugId}>
              <TableCell>
                <p className="truncate font-medium">{bug.title || "未命名 Bug"}</p>
                {bug.description && (
                  <p className="truncate text-xs text-muted-foreground">{bug.description}</p>
                )}
              </TableCell>
              <TableCell>
                <SeverityBadge severity={bug.severity} severityName={bug.severityName} />
              </TableCell>
              <TableCell>
                <BugStatusBadge status={bug.status} statusName={bug.statusName} />
              </TableCell>
              <TableCell className="text-right text-xs text-muted-foreground">
                {formatRelativeTime(bug.createdAt) || "—"}
              </TableCell>
              <TableCell className="text-right">
                {bug.status !== BUG_STATUS.VERIFIED && (
                  <CloseBugAction projectId={projectId} bug={bug} />
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

/** 逐条关闭（OPEN/FIXED → VERIFIED）：reason 必填，空值拦截后才发。 */
function CloseBugAction({ projectId, bug }: { projectId: string; bug: Bug }) {
  const close = useCloseBug(projectId);
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const canClose = reason.trim() !== "";

  const onClose = () => {
    if (!canClose) return;
    setOpen(false);
    close.mutate(
      { bugId: bug.bugId, reason },
      {
        onSuccess: () => {
          toast.success("Bug 已关闭");
          setReason("");
        },
        onError: (error) => toast.error(errorText(error)),
      },
    );
  };

  return (
    <>
      <Button size="sm" variant="ghost" className="text-muted-foreground" onClick={() => setOpen(true)}>
        关闭
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>关闭「{bug.title || "未命名 Bug"}」</DialogTitle>
            <DialogDescription>
              关闭后标记为复测通过，需写明关闭理由（例如误报、产品本就如此）。
            </DialogDescription>
          </DialogHeader>
          <Textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="关闭理由（必填）"
            rows={3}
            aria-invalid={!canClose}
          />
          <DialogFooter>
            <Button variant="ghost" onClick={() => setOpen(false)}>
              取消
            </Button>
            <Button onClick={onClose} disabled={!canClose || close.isPending}>
              确认关闭
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
