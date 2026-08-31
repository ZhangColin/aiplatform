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
import { Badge } from "@/components/ui/badge";
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
import type { ProjectSummary } from "@/lib/projects/list";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 项目列表卡（issue #17 清场后骨架）：项目名 + 状态 + 创建时间，归档走下拉菜单
 * + 二次确认。已归档卡灰态、无菜单；卡片内的订单状态呈现随交易环落位。
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
        <p className="truncate text-sm text-muted-foreground">
          {project.statusName || "进行中"}
        </p>
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
