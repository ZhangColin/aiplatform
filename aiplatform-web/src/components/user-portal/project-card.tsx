"use client";

import { Archive, MoreHorizontal } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { JourneyMiniProgress } from "@/components/main-chain/journey-views";
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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useProjectJourney } from "@/hooks/use-project";
import { useArchiveProject } from "@/hooks/use-projects";
import { errorText } from "@/lib/api/api-error";
import { isGateReady } from "@/lib/main-chain/project";
import type { ProjectListFilterKey, ProjectSummary } from "@/lib/projects/list";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 项目列表卡（spec 0002 §3.2）：六步 mini 进度按详情数据渲染（stages[] 为唯一
 * 源）；「需要你」amber 行 = 待处理视图或门就绪；归档走下拉菜单 + 二次确认
 * （issue #20）。已归档卡灰态、无菜单。
 *
 * 数据缺口（等后端列表补字段后回填）：emoji / 当初一句话 / 更新时间——
 * `ProjectResponse` 无对应字段，暂以创建时间代「更新时间」。amber 行口径：
 * 仅等待点待处理（无门）的项目只在「待处理」视图亮灯，「全部/进行中」视图的
 * 等待点信号等 #21 的 todos 聚合接管后回收此妥协。
 */
export function ProjectCard({
  project,
  filter,
}: {
  project: ProjectSummary;
  filter: ProjectListFilterKey;
}) {
  const router = useRouter();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const archive = useArchiveProject(project.id);

  // 卡内订阅详情（stages[] / gate 只在详情端点）：旅程 mini 进度与门就绪的数据源
  const { data: detail, steps, current } = useProjectJourney(project.id);
  const delivered = current?.terminal === true;

  const needsYou = filter === "pending" || isGateReady(detail?.gate);
  const archived = project.archived;

  const onArchive = () => {
    setConfirmOpen(false);
    archive.mutate(undefined, {
      onSuccess: () => toast.success(`已归档「${project.name || "未命名项目"}」`),
      onError: (error) => toast.error(errorText(error)),
    });
  };

  return (
    <Card
      onClick={() => router.push(`/projects/${project.id}`)}
      className={cn(
        "cursor-pointer gap-3 py-5 transition-shadow hover:shadow-md",
        archived && "opacity-60 saturate-50 hover:shadow-none",
      )}
    >
      <CardHeader className="gap-1.5">
        <CardTitle className="flex items-center gap-2 text-base">
          <span className="truncate">{project.name || "未命名项目"}</span>
          {delivered && !archived && (
            <Badge variant="secondary" className="ml-auto shrink-0">
              已交付
            </Badge>
          )}
          {archived && (
            <Badge variant="outline" className="ml-auto shrink-0 text-muted-foreground">
              已归档
            </Badge>
          )}
          {!archived && (
            <DropdownMenu>
              <DropdownMenuTrigger
                onClick={(e) => e.stopPropagation()}
                render={
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label="项目操作"
                    className="ml-auto size-7 shrink-0 text-muted-foreground"
                  />
                }
              >
                <MoreHorizontal className="size-4" />
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
                <DropdownMenuItem onClick={() => setConfirmOpen(true)}>
                  <Archive />
                  归档
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {steps.length > 0 ? (
          <JourneyMiniProgress steps={steps} />
        ) : (
          // 详情未就绪 / stages 缺失：先以段展示名占位，不造旅程
          <p className="truncate text-xs text-muted-foreground">{project.stageLabel}</p>
        )}
        {needsYou ? (
          <p className="text-sm font-medium text-amber-600 dark:text-amber-400">
            {current?.gateLabel ? `需要你：${current.gateLabel}` : "有需要你处理的事"}
          </p>
        ) : (
          current && <p className="truncate text-sm text-muted-foreground">{current.hint}</p>
        )}
        <p className="text-xs text-muted-foreground/70">
          创建于 {formatRelativeTime(project.createdAt) || "未知时间"}
        </p>
      </CardContent>

      <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
        <AlertDialogContent onClick={(e) => e.stopPropagation()}>
          <AlertDialogHeader>
            <AlertDialogTitle>归档「{project.name || "未命名项目"}」？</AlertDialogTitle>
            <AlertDialogDescription>
              归档后项目会从常用视图移入「已归档」，只能查看，不能继续推进。这个操作不可恢复，请确认。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>再想想</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={onArchive}>
              确认归档
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
