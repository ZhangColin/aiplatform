"use client";

import { ChevronRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { SidebarInset } from "@/components/ui/sidebar";
import { useSidebarProjects } from "@/hooks/use-projects";
import { useProjectAttention } from "@/hooks/use-todos";
import { recentProjects, type ProjectSummary } from "@/lib/projects/list";
import type { ProjectAttention } from "@/lib/todos/todo";

import { CreateProjectForm } from "./create-project-form";

/**
 * 需求端落地页 = 一句话 hero（spec 0002 §3.1，issue #39）：无页头 Trigger、
 * 整页居中 hero（标题 + 建项目表单）+ 下方「最近的项目」4 条 + 「查看全部」→
 * 项目列表页。提交建项目 → 直进该项目工作台（顾问单对话模式；工作台随 #37
 * 就绪、agent 流按 projectId 挂载，BA 首轮问答卡 / 门卡随对话模式票填）。
 *
 * 「最近的项目」卡行（spec 0002 §3.1 2026-08-25 修订，issue #49）：有待办显
 * 「需要你」amber 行（问答待答 / 门待拍板，点击深链直达工作台对应位置），无
 * 待办显安心态行（阶段推进中）。
 */

export function CreateProjectHero() {
  const router = useRouter();
  // sidebar 项目列表 = 未归档口径（同列表页「全部」），「最近的项目」直接取其前 4。
  // 排序口径 = 更新时间新→旧；后端列表暂无 updatedAt，以 createdAt 代（同列表卡
  // 妥协，等字段补齐后回填）。
  const recent = recentProjects(useSidebarProjects(), 4);
  // 「需要你」数据源 = 待办聚合（AGENT_WAIT / GATE_PENDING，useProjectAttention
  // 收口）。首页无 SSE 建连，轮询兜底常开（与 dev 侧边栏徽章同口径）。
  const attention = useProjectAttention();

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
                顾问陪你聊清楚需求，原型你确认，交付你验收——关键节点你拍板，其余交给团队。
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
                  <RecentProjectRow
                    key={project.id}
                    project={project}
                    attention={attention.get(project.id)}
                  />
                ))}
              </ul>
            </div>
          )}
        </div>
      </main>
    </SidebarInset>
  );
}

/**
 * 最近项目卡行（issue #49）：有待办 → 整行深链直达（等待点 / 门卡）+ amber 提醒
 * 行；无待办 → 普通进项目 + 安心态行（阶段推进中）。两种去向都落在该项目工作台。
 */
export function RecentProjectRow({
  project,
  attention,
}: {
  project: ProjectSummary;
  attention?: ProjectAttention;
}) {
  return (
    <li>
      <Link
        href={attention ? attention.href : `/projects/${project.id}`}
        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-muted/40"
      >
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium">
            {project.name || "未命名项目"}
          </span>
          {attention ? (
            <span className="block truncate text-xs font-medium text-amber-600 dark:text-amber-400">
              {attention.label}
            </span>
          ) : (
            <span className="block truncate text-xs text-muted-foreground">
              {project.stageLabel || "进行中"}
            </span>
          )}
        </span>
        <ChevronRight className="size-4 text-muted-foreground" />
      </Link>
    </li>
  );
}
