"use client";

import { ChevronRight } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { useProjectList } from "@/hooks/use-projects";
import { errorText } from "@/lib/api/api-error";
import { projectListSections, recentProjects, type ProjectSummary } from "@/lib/projects/list";

import { ProjectCard } from "./project-card";

/** 我的项目 P08：生命周期阶段与待办列表，默认按最后更新排序，归档保留折叠组。
 * 未绑定访客只回当前会话的最近项目；绑定账号后才呈现全量列表。 */
export function ProjectListView({ guestOnly = false }: { guestOnly?: boolean }) {
  const router = useRouter();
  const list = useProjectList();
  const items = list.data ?? [];
  const { main, archivedGroup } = projectListSections(items);
  const currentGuestProject = guestOnly ? recentProjects(items, 1)[0] : undefined;

  useEffect(() => {
    if (guestOnly && currentGuestProject) {
      router.replace(`/projects/${currentGuestProject.id}`);
    }
  }, [currentGuestProject, guestOnly, router]);

  const returningGuest = guestOnly && currentGuestProject != null;

  return (
    <div className="mx-auto max-w-6xl p-4 py-6 lg:px-8">
        <header className="mb-6">
          <h1 className="text-headline-lg font-bold text-ink-primary">我的项目</h1>
          <p className="mt-1 text-body-sm text-ink-faint">查看每个交付项目的当前阶段与待处理事项</p>
        </header>

        {list.isPending || returningGuest ? (
          <ProjectListSkeleton />
        ) : list.isError ? (
          <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
            <p>{errorText(list.error, "项目列表加载失败")}</p>
            <Button variant="outline" size="sm" onClick={() => void list.refetch()}>
              重试
            </Button>
          </div>
        ) : main.length === 0 && archivedGroup.length === 0 ? (
          <div className="flex flex-col items-center rounded-2xl border border-dashed border-slate-soft px-4 py-16 text-center">
            <p className="text-body-md text-ink-faint">还没有项目，从一句话开始梳理你的产品需求。</p>
            <Link
              href="/"
              className="mt-5 inline-flex items-center justify-center rounded-lg bg-linear-to-br from-electric-indigo to-tech-blue px-4 py-2 text-label-md font-semibold text-white shadow-inset-highlight"
            >
              开始一个新需求
            </Link>
          </div>
        ) : (
          <>
            {main.length > 0 ? (
              <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
                {main.map((project) => (
                  <ProjectCard key={project.id} project={project} />
                ))}
              </div>
            ) : null}
            {archivedGroup.length > 0 ? <ArchivedGroup projects={archivedGroup} /> : null}
          </>
        )}
    </div>
  );
}

/** 历史归档分组：默认折叠只露计数，点开摊出归档卡。 */
export function ArchivedGroup({
  projects,
  defaultOpen = false,
}: {
  projects: ProjectSummary[];
  defaultOpen?: boolean;
}) {
  return (
    <Collapsible defaultOpen={defaultOpen} className="mt-8">
      <CollapsibleTrigger
        className="group flex w-full items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
        aria-label="展开历史归档"
      >
        <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />
        <span className="font-medium">历史归档 ({projects.length})</span>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {projects.map((project) => (
            <ProjectCard key={project.id} project={project} />
          ))}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

function ProjectListSkeleton() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {[0, 1, 2].map((i) => (
        <Card key={i} className="gap-3 py-5">
          <CardHeader>
            <Skeleton className="h-5 w-2/3" />
          </CardHeader>
          <CardContent className="space-y-3">
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-3 w-1/3" />
          </CardContent>
        </Card>
      ))}
    </div>
  );
}
