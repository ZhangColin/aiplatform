"use client";

import { useState, type ReactNode } from "react";

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
 * 项目列表页（spec 0002 §3.2，issue #20）：Segmented 四态过滤（全部/进行中/
 * 待处理/已归档）+ 卡片网格 + 归档操作。「全部」不传 status + 本地过滤已归档。
 */

const EMPTY_COPY: Record<ProjectListFilterKey, string> = {
  all: "还没有项目，先去聊一个想做的吧",
  active: "没有进行中的项目",
  pending: "现在没有需要你处理的事",
  archived: "还没有归档的项目",
};

/** 页头文案可场景化覆写（spec 0002 §2：列表页是通用页面能力，按场景菜单取用）。 */
export type ProjectListViewProps = {
  title?: string;
  description?: string;
  /** 页头右侧动作（如 dev 场景「新建项目」入口，spec 0002 §2 同形态建项目）。 */
  headerAction?: ReactNode;
};

export function ProjectListView({
  title = "我的项目",
  description = "每个项目从聊需求到交付共六步，需要你拍板时会明确告诉你",
  headerAction,
}: ProjectListViewProps) {
  const [filter, setFilter] = useState<ProjectListFilterKey>("all");
  const list = useProjectList(filter);
  const items = visibleProjects(list.data ?? [], filter);

  return (
    <PortalContent>
      <div className="mx-auto max-w-5xl p-6">
        {/* 非工作台页页头（spec 0001 §2）：标题 + 说明（收起归品牌行，issue #50） */}
        <header className="mb-5 flex items-center gap-2">
          <div>
            <h1 className="text-lg font-semibold">{title}</h1>
            <p className="text-xs text-muted-foreground">{description}</p>
          </div>
          {headerAction && <div className="ml-auto">{headerAction}</div>}
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
              <ProjectCard key={project.id} project={project} filter={filter} />
            ))}
          </div>
        )}
      </div>
    </PortalContent>
  );
}
