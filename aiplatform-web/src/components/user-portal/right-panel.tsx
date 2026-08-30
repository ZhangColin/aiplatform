"use client";

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { JourneyTimeline } from "@/components/main-chain/journey-views";
import { useProjectJourney } from "@/hooks/use-project";
import { formatRelativeTime } from "@/lib/utils/time";

import { DemandPoolCard } from "./demand-pool-card";
import { ProjectNameField } from "./project-name-field";
import { UsageCard } from "./usage-card";

/**
 * 工作台右栏（spec 0002 §4，issue #20）：项目信息 + 旅程 + 用量 + 下一期
 * 想法池。数据缺口（等后端补字段后回填）：项目信息的「当初一句话」（建项目
 * 原始输入）与「更新时间」——详情无对应字段，暂以创建时间代。
 */
export function WorkbenchRightPanel({ projectId }: { projectId: string }) {
  const { data: detail, steps, current } = useProjectJourney(projectId);

  return (
    <ScrollArea className="h-full">
      <div className="space-y-4 p-4">
        <Card className="gap-3 py-4">
          <CardHeader>
            <CardTitle className="text-sm">项目信息</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2 text-sm">
            {!detail ? (
              <Skeleton className="h-14 w-full" />
            ) : (
              <>
                <ProjectNameField projectId={projectId} />
                <div className="flex justify-between text-muted-foreground">
                  当前环节
                  <span className="text-foreground">
                    {current?.label ?? (detail.stageLabel || "—")}
                  </span>
                </div>
                <div className="flex justify-between text-muted-foreground">
                  创建时间
                  <span className="text-foreground">
                    {formatRelativeTime(detail.createdAt) || "—"}
                  </span>
                </div>
              </>
            )}
          </CardContent>
        </Card>

        {steps.length > 0 && (
          <Card className="gap-3 py-4">
            <CardHeader>
              <CardTitle className="text-sm">旅程</CardTitle>
              {current && (
                <CardDescription>
                  共 {steps.length} 步，现在是「{current.label}」
                </CardDescription>
              )}
            </CardHeader>
            <CardContent>
              <JourneyTimeline steps={steps} />
            </CardContent>
          </Card>
        )}

        <UsageCard projectId={projectId} />
        <DemandPoolCard projectId={projectId} />
      </div>
    </ScrollArea>
  );
}
