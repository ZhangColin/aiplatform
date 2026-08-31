"use client";

import { useState } from "react";

import { PortalContent } from "@/components/layout/portal-sidebar";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { useProjectList } from "@/hooks/use-projects";
import { errorText } from "@/lib/api/api-error";
import {
  PROJECT_LIST_FILTERS,
  visibleProjects,
  type ProjectListFilterKey,
} from "@/lib/projects/list";

import { ProjectCard } from "./project-card";

/**
 * 项目列表页（issue #17 单门户三路由之一）：Segmented 过滤（全部 / 进行中 /
 * 已归档；订单四态过滤随交易环重组）+ 卡片网格 + 归档操作。「全部」不传
 * status + 本地过滤已归档。
 */

const EMPTY_COPY: Record<ProjectListFilterKey, string> = {
  all: "还没有项目，先去聊一个想做的吧",
  active: "没有进行中的项目",
  archived: "还没有归档的项目",
};

export function ProjectListView() {
  const [filter, setFilter] = useState<ProjectListFilterKey>("all");
  const list = useProjectList(filter);
  const items = visibleProjects(list.data ?? [], filter);

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
          value={[filter]}
          onValueChange={(values) => {
            // base-ui 受控值为数组（multiple=false 单选）；空数组 = 点掉当前项，保持不变
            const next = values.at(-1);
            const found = next && PROJECT_LIST_FILTERS.find((f) => f.key === next);
            if (found) setFilter(found.key);
          }}
          className="mb-4"
          aria-label="项目过滤"
        >
          {PROJECT_LIST_FILTERS.map((f) => (
            <ToggleGroupItem key={f.key} value={f.key} className="text-xs">
              {f.label}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>

        {list.isPending ? (
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
        ) : list.isError ? (
          <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
            <p>{errorText(list.error, "项目列表加载失败")}</p>
            <Button variant="outline" size="sm" onClick={() => void list.refetch()}>
              重试
            </Button>
          </div>
        ) : items.length === 0 ? (
          <div className="rounded-xl border border-dashed py-16 text-center text-sm text-muted-foreground">
            {EMPTY_COPY[filter]}
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {items.map((project) => (
              <ProjectCard key={project.id} project={project} />
            ))}
          </div>
        )}
      </div>
    </PortalContent>
  );
}
