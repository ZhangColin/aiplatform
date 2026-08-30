"use client";

import { ExternalLink } from "lucide-react";
import { useRouter } from "next/navigation";

import { PortalContent } from "@/components/layout/portal-sidebar";
import { TaskStatusBadge } from "@/components/tasks/task-badges";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useMyTasks } from "@/hooks/use-tasks";
import { errorText } from "@/lib/api/api-error";
import { isRejectedTask, type TaskCard as TaskCardData } from "@/lib/tasks/task";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 我的任务列表（issue #22，spec 0003 §2.1）：OPC 门户落地页，卡片网格新→旧。
 * 卡片 = 任务标题 + 项目名 + 状态徽章 + 创建时间（A4 勘定收缩口径，无类型徽章 /
 * Bug 计数）；被驳回 destructive 并亮 rejectReason 摘要；预览地址存在时给独立
 * 打开入口。空态「暂无任务」。实时性 = SSE task-updated → 桥失效 tasks 域。
 */
export function TaskListView() {
  const list = useMyTasks();
  const items = list.data ?? [];

  return (
    <PortalContent>
      <div className="mx-auto max-w-5xl p-6">
        {/* 非工作台页页头（spec 0001 §2）：标题 + 说明（收起归品牌行，issue #50） */}
        <header className="mb-5 flex items-center gap-2">
          <div>
            <h1 className="text-lg font-semibold">我的任务</h1>
            <p className="text-xs text-muted-foreground">分配给你的测试任务，新的在前</p>
          </div>
        </header>

        {list.isPending ? (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {[0, 1, 2].map((i) => (
              <Card key={i} className="gap-3 py-5">
                <CardHeader>
                  <Skeleton className="h-5 w-2/3" />
                </CardHeader>
                <CardContent className="space-y-2">
                  <Skeleton className="h-3 w-1/3" />
                  <Skeleton className="h-3 w-1/4" />
                </CardContent>
              </Card>
            ))}
          </div>
        ) : list.isError ? (
          <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
            <p>{errorText(list.error, "任务列表加载失败")}</p>
            <Button variant="outline" size="sm" onClick={() => void list.refetch()}>
              重试
            </Button>
          </div>
        ) : items.length === 0 ? (
          <div className="rounded-xl border border-dashed py-16 text-center text-sm text-muted-foreground">
            暂无任务
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {items.map((task) => (
              <TaskCard key={task.taskId} task={task} />
            ))}
          </div>
        )}
      </div>
    </PortalContent>
  );
}

function TaskCard({ task }: { task: TaskCardData }) {
  const router = useRouter();
  const rejected = isRejectedTask(task.status, task.rejectReason);
  const previewUrl = task.previewUrl;

  return (
    <Card
      onClick={() => router.push(`/opc/tasks/${task.taskId}`)}
      className="cursor-pointer gap-3 py-5 transition-shadow hover:shadow-md"
    >
      <CardHeader className="gap-1.5">
        <CardTitle className="flex items-center gap-2 text-base">
          <span className="truncate">{task.title || "未命名任务"}</span>
          <TaskStatusBadge task={task} className="ml-auto" />
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <span className="truncate">{task.projectName || "未知项目"}</span>
          {previewUrl && (
            <Button
              size="icon-xs"
              variant="ghost"
              aria-label="打开预览"
              className="ml-auto shrink-0 text-muted-foreground"
              onClick={(e) => {
                e.stopPropagation();
                window.open(previewUrl, "_blank", "noopener,noreferrer");
              }}
            >
              <ExternalLink />
            </Button>
          )}
        </div>
        {rejected && (
          <p className="line-clamp-2 text-sm text-destructive">驳回理由：{task.rejectReason}</p>
        )}
        <p className="text-xs text-muted-foreground/70">
          创建于 {formatRelativeTime(task.createdAt) || "未知时间"}
        </p>
      </CardContent>
    </Card>
  );
}
