"use client";

import { Badge } from "@/components/ui/badge";
import { useDemoVersions } from "@/hooks/use-demo-version";

/**
 * Demo 版本 chip（#333 最小呈现，spec #320 实施序③——完整版本记录抽屉归
 * #344）：Demo/预览面浏览器条挂「Demo vN · 基于 PRD vM 快照」——收口自动成版
 * 的最新版标识＋起跑时 PRD 确认版本锚（无确认版起跑〔legacy/设计类〕只出
 * 版本号，不虚构关联）。从未成版（空列表/加载中）不出 chip——诚实优先于占位。
 * 点开抽屉与确认标记按钮（「确认此版本，准备签约」）归 Demo 体验页重建
 * （#347）；确认标记态经阶段门（stageGateFactsOf）消费。
 */
export function DemoVersionChip({ projectId }: { projectId: string }) {
  const { data: versions } = useDemoVersions(projectId);
  const latest = versions?.[0];
  if (!latest) return null;
  return (
    <Badge variant="outline" className="shrink-0 gap-1 font-normal text-muted-foreground">
      Demo v{latest.versionNo}
      {latest.prdVersionNo != null ? ` · 基于 PRD v${latest.prdVersionNo} 快照` : null}
    </Badge>
  );
}
