"use client";

import { ArrowLeft, ExternalLink } from "lucide-react";
import Link from "next/link";

import { PreviewChrome } from "@/components/main-chain/preview-panel";
import { TaskStatusBadge } from "@/components/tasks/task-badges";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { SidebarInset } from "@/components/ui/sidebar";
import { Skeleton } from "@/components/ui/skeleton";
import { useTaskDetail } from "@/hooks/use-tasks";
import { errorText } from "@/lib/api/api-error";
import { isRejectedTask, type TaskDetail } from "@/lib/tasks/task";
import { formatRelativeTime } from "@/lib/utils/time";

import { BugWorkspace } from "./bug-workspace";

/**
 * 任务详情（issue #22，spec 0003 §2.2）：非工作台单页内部双栏——左栏任务信息
 * （项目名 / 预览地址 / 任务描述）+ Bug 工作区，右栏预览 iframe（通用预览口径）；
 * `<md` 退化上下两段、预览收为独立打开外链。被驳回顶部 destructive alert 原样
 * 呈现驳回说明（§2.4）。非指派且非项目 owner 访问 → 403 TASK_004 错误页。
 */
export function TaskDetailView({ taskId }: { taskId: string }) {
  const detail = useTaskDetail(taskId);
  const task = detail.data;

  if (detail.isError) {
    return (
      <SidebarInset className="flex h-svh min-h-0 flex-col">
        <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-3 text-sm text-muted-foreground">
          <p>{errorText(detail.error, "任务加载失败")}</p>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={() => void detail.refetch()}>
              重试
            </Button>
            <Button variant="ghost" size="sm" nativeButton={false} render={<Link href="/opc" />}>
              返回我的任务
            </Button>
          </div>
        </div>
      </SidebarInset>
    );
  }

  return (
    <SidebarInset className="flex h-svh min-h-0 flex-col">
      {/* 页头（spec 0001 §2 标准页）：返回 + 任务标题 + 状态徽章（收起归品牌行，issue #50） */}
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label="返回我的任务"
          nativeButton={false}
          render={<Link href="/opc" />}
        >
          <ArrowLeft />
        </Button>
        {detail.isPending || !task ? (
          <Skeleton className="h-5 w-40" />
        ) : (
          <>
            <span className="truncate text-sm font-semibold">{task.title || "未命名任务"}</span>
            <TaskStatusBadge task={task} />
          </>
        )}
      </header>

      {detail.isPending || !task ? (
        <div className="mx-auto w-full max-w-2xl space-y-4 p-6">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      ) : (
        <div className="flex min-h-0 flex-1">
          {/* 左栏：任务信息 + Bug 工作区 */}
          <div className="min-w-0 flex-1 overflow-y-auto">
            <div className="mx-auto max-w-2xl space-y-5 p-6">
              {isRejectedTask(task.status, task.rejectReason) && (
                <Alert variant="destructive">
                  <AlertTitle>任务被驳回，修改后重新提交</AlertTitle>
                  <AlertDescription>驳回理由：{task.rejectReason}</AlertDescription>
                </Alert>
              )}

              <TaskInfoCard task={task} />

              {/* `<md` 退化：预览收为独立打开外链 */}
              {task.previewUrl && (
                <Button
                  variant="outline"
                  className="md:hidden"
                  onClick={() => {
                    const url = task.previewUrl;
                    if (url) window.open(url, "_blank", "noopener,noreferrer");
                  }}
                >
                  <ExternalLink /> 打开预览
                </Button>
              )}

              <BugWorkspace detail={task} />
            </div>
          </div>

          {/* 右栏：预览 iframe（通用预览口径，测试场景刚需边操作边录 Bug） */}
          <aside className="hidden w-1/2 shrink-0 border-l md:flex md:flex-col">
            <PreviewChrome
              projectId={task.projectId}
              url={task.previewUrl}
              title={`${task.projectName || "项目"} 预览`}
            />
          </aside>
        </div>
      )}
    </SidebarInset>
  );
}

function TaskInfoCard({ task }: { task: TaskDetail }) {
  const previewUrl = task.previewUrl;
  return (
    <Card className="gap-3 py-4">
      <CardHeader>
        <CardTitle className="text-sm">任务信息</CardTitle>
      </CardHeader>
      <CardContent className="space-y-2 text-sm">
        <div className="flex justify-between gap-4 text-muted-foreground">
          所属项目
          <span className="truncate text-foreground">{task.projectName || "未知项目"}</span>
        </div>
        <div className="flex justify-between gap-4 text-muted-foreground">
          预览地址
          {previewUrl ? (
            <a
              href={previewUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="truncate text-primary underline-offset-4 hover:underline"
            >
              {previewUrl}
            </a>
          ) : (
            <span className="text-muted-foreground/70">工作区未建，暂无</span>
          )}
        </div>
        <div className="flex justify-between gap-4 text-muted-foreground">
          创建时间
          <span className="text-foreground">{formatRelativeTime(task.createdAt) || "—"}</span>
        </div>
        {task.content && (
          <p className="border-t pt-2 leading-relaxed whitespace-pre-wrap text-foreground/90">
            {task.content}
          </p>
        )}
      </CardContent>
    </Card>
  );
}
