"use client";

import { ChevronRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { SidebarInset } from "@/components/ui/sidebar";
import { useRecentProjects } from "@/hooks/use-projects";
import {
  lastTouchedAt,
  projectStage,
  recentProjects,
  stageLabel,
  type ProjectSummary,
} from "@/lib/projects/list";
import { formatRelativeTime } from "@/lib/utils/time";

import { CreateProjectForm } from "./create-project-form";

/**
 * 首页 = 一句话 hero（issue #17 单站三路由之一）：整页居中 hero（标题 + 建
 * 项目表单）+ 下方「最近的项目」4 条 + 「查看全部」→ 项目列表页。提交建项目 →
 * 直进该项目页（项目页挂 agent 流，建即自动跑 BA 需求梳理）。区块为将来独立
 * 纯入口页预留（#9：首页可能不放项目列表）。
 */
export function CreateProjectHero() {
  const router = useRouter();
  // 「最近的项目」取未归档项目前 4，排序口径 = 更新时间新→旧（updatedAt，
  // #21 已随列表透出回填；缺失以 createdAt 代）。
  const recent = recentProjects(useRecentProjects(), 4);

  return (
    <SidebarInset className="h-svh min-h-0 flex-col">
      <main className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto flex w-full max-w-3xl flex-col items-center px-4 py-16">
          <div className="w-full space-y-6 text-center">
            <div className="space-y-3">
              <h1 className="text-3xl font-bold tracking-tight sm:text-4xl">
                一句话，开始做出你想要的东西
              </h1>
              <p className="text-base text-muted-foreground">
                平台陪你把需求聊清楚，做成可操作的系统——想调整随时提
              </p>
            </div>
            <CreateProjectForm
              className="mx-auto w-full max-w-3xl text-left"
              onCreated={(id) => router.push(`/projects/${id}`)}
            />
          </div>

          {recent.length > 0 && (
            <div className="mt-14 w-full max-w-3xl">
              <div className="mb-2 flex items-center justify-between">
                <h2 className="text-sm font-semibold text-muted-foreground">最近的项目</h2>
                <Button
                  size="xs"
                  variant="ghost"
                  nativeButton={false}
                  render={<Link href="/projects" />}
                >
                  查看全部 <ChevronRight className="size-3" />
                </Button>
              </div>
              <ul className="divide-y rounded-xl border">
                {recent.map((project) => (
                  <RecentProjectRow key={project.id} project={project} />
                ))}
              </ul>
            </div>
          )}
        </div>
      </main>
    </SidebarInset>
  );
}

/** 最近项目行：项目名 + 四态 · 更新时间，点击进项目页。 */
export function RecentProjectRow({ project }: { project: ProjectSummary }) {
  const meta = [
    stageLabel(projectStage(project)),
    formatRelativeTime(lastTouchedAt(project)),
  ].filter(Boolean);
  return (
    <li>
      <Link
        href={`/projects/${project.id}`}
        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-muted/40"
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">
            {project.name || "未命名项目"}
          </span>
          <span className="block truncate text-xs text-muted-foreground">{meta.join(" · ")}</span>
        </span>
        <ChevronRight className="size-4 text-muted-foreground" />
      </Link>
    </li>
  );
}
