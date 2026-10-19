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
import {
  lastTouchedAt,
  projectPendingAction,
  projectStage,
  projectTransactionSubstate,
  type ProjectSummary,
} from "@/lib/projects/list";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/utils/time";

import { EndpointMark } from "./endpoint-mark";
import { StageBadge } from "./stage-badge";

/** P08 项目卡：项目名称、生命周期阶段（交易子态副行）、最后更新与待用户处理事项。 */
export function ProjectCard({ project }: { project: ProjectSummary }) {
  const router = useRouter();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const archive = useArchiveProject(project.id);
  const archived = project.archived;
  const transactionSubstate = projectTransactionSubstate(project);
  const updated = formatRelativeTime(lastTouchedAt(project)) || "未知时间";

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
      <CardContent className="space-y-4">
        <div>
          <div className="flex items-center gap-1.5">
            <StageBadge stage={projectStage(project)} />
            <EndpointMark
              endpointType={project.endpointType}
              endpointTypeName={project.endpointTypeName}
            />
          </div>
          {transactionSubstate ? (
            <p className="mt-1.5 text-xs font-medium text-muted-foreground">{transactionSubstate}</p>
          ) : null}
        </div>
        <p className="text-xs text-muted-foreground">
          <span>最后更新</span>
          <span className="ml-1.5">{updated}</span>
        </p>
        <div className="border-t border-border/60 pt-3">
          <p className="text-xs text-muted-foreground">待用户处理事项</p>
          <p className="mt-1 text-sm text-foreground">{projectPendingAction(project)}</p>
        </div>
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
