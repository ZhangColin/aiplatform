"use client";

import { Archive, MoreHorizontal } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

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
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useArchiveProject } from "@/hooks/use-projects";
import { errorText } from "@/lib/api/api-error";
import { projectStage, stageLabel, type ProjectSummary } from "@/lib/projects/list";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 项目列表卡（issue #21）：项目名 + 四态 + 创建/更新时间（无动态摘要行），
 * 归档走下拉菜单 + 二次确认。已归档卡灰态、无菜单。
 */
export function ProjectCard({ project }: { project: ProjectSummary }) {
  const router = useRouter();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const archive = useArchiveProject(project.id);
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
        <p className="truncate text-sm text-muted-foreground">
          {stageLabel(projectStage(project))}
        </p>
        <p className="text-xs text-muted-foreground/70">{timeLine(project)}</p>
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

/** 时间行：创建于 X；更新晚于创建时追加「更新于 Y」（未真更新过不刷屏）。 */
function timeLine({ createdAt, updatedAt }: Pick<ProjectSummary, "createdAt" | "updatedAt">) {
  const created = formatRelativeTime(createdAt) || "未知时间";
  const updated = formatRelativeTime(updatedAt);
  const later =
    updated !== "" &&
    createdAt !== undefined &&
    updatedAt !== undefined &&
    Date.parse(updatedAt) > Date.parse(createdAt);
  return later ? `创建于 ${created} · 更新于 ${updated}` : `创建于 ${created}`;
}
