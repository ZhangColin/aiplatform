"use client";

import { ChevronRight } from "lucide-react";
import { useState } from "react";

import { PortalContent } from "@/components/layout/portal-sidebar";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useProjectList } from "@/hooks/use-projects";
import { errorText } from "@/lib/api/api-error";
import {
  PROJECT_STAGES,
  projectListSections,
  type ProjectStageKey,
  type ProjectSummary,
} from "@/lib/projects/list";

import { ProjectCard } from "./project-card";

/**
 * 项目列表页（issue #21 四态重组）：四态过滤（进行中/待报价/待支付/已归档；
 * 后两态为空壳过滤位，订单态接线归交易环）+ 卡片网格 + 「历史归档 (N)」默认
 * 折叠分组。全量拉取本地分区，v1 不分页；选中已归档时主网格即归档全量、
 * 折叠分组不再重复出。
 */

const EMPTY_COPY: Record<ProjectStageKey, string> = {
  in_progress: "没有进行中的项目，先去聊一个想做的吧",
  awaiting_quote: "没有待报价的项目",
  awaiting_payment: "没有待支付的项目",
  archived: "还没有归档的项目",
};

export function ProjectListView() {
  const [stage, setStage] = useState<ProjectStageKey>("in_progress");
  const list = useProjectList();
  const { main, archivedGroup } = projectListSections(list.data ?? [], stage);

  return (
    <PortalContent>
      <div className="mx-auto max-w-5xl p-6">
        {/* 非工作台页页头：标题 + 说明（收起归品牌行） */}
        <header className="mb-5 flex items-center gap-2">
          <div>
            <h1 className="text-lg font-semibold">我的项目</h1>
            <p className="text-xs text-muted-foreground">每个项目承载一次定制需求的全程</p>
          </div>
        </header>

        <ToggleGroup
          variant="outline"
          spacing={0}
          value={[stage]}
          onValueChange={(values) => {
            // base-ui 受控值为数组（multiple=false 单选）；空数组 = 点掉当前项，保持不变
            const next = values.at(-1);
            const found = next && PROJECT_STAGES.find((s) => s.key === next);
            if (found) setStage(found.key);
          }}
          className="mb-4"
          aria-label="项目过滤"
        >
          {PROJECT_STAGES.map((s) => (
            <ToggleGroupItem key={s.key} value={s.key} className="text-xs">
              {s.label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>

        {list.isPending ? (
          <ProjectListSkeleton />
        ) : list.isError ? (
          <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
            <p>{errorText(list.error, "项目列表加载失败")}</p>
            <Button variant="outline" size="sm" onClick={() => void list.refetch()}>
              重试
            </Button>
          </div>
        ) : main.length === 0 ? (
          <div className="rounded-xl border border-dashed py-16 text-center text-sm text-muted-foreground">
            {EMPTY_COPY[stage]}
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {main.map((project) => (
              <ProjectCard key={project.id} project={project} />
            ))}
          </div>
        )}

        {archivedGroup.length > 0 && <ArchivedGroup projects={archivedGroup} />}
      </div>
    </PortalContent>
  );
}

/** 历史归档分组：默认折叠只露计数，点开摊出归档卡（选中已归档时不出）。
 * defaultOpen 供 SSR 断言注入展开态（node 环境点不了 trigger）。 */
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
        <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
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
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
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
