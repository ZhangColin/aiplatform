"use client";

import { Badge } from "@/components/ui/badge";
import { useDemoVersions } from "@/hooks/use-demo-version";
import { useVersionRecordStore } from "@/lib/store/version-record";

/**
 * Demo 版本 chip（#333 最小呈现，#344 起可点开版本记录抽屉——完整 Demo 横幅
 * 归 #347 迁移后同接抽屉）：Demo/预览面浏览器条挂「Demo vN · 基于 PRD vM
 * 快照」——收口自动成版的最新版标识＋起跑时 PRD 确认版本锚（无确认版起跑
 * 〔legacy/设计类〕只出版本号，不虚构关联）。最新版带确认标记时缀「已确认」
 * （#335——确认态在版本可见处可见；完整确认卡归 #347）。从未成版（空列表/
 * 加载中）不出 chip——诚实优先于占位。确认标记态经阶段门（stageGateFactsOf）
 * 消费。点击＝版本记录抽屉的第二挂点（与顶栏「版本记录」同一抽屉，#344）。
 */
export function DemoVersionChip({ projectId }: { projectId: string }) {
  const { data: versions } = useDemoVersions(projectId);
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);
  const latest = versions?.[0];
  if (!latest) return null;
  return (
    <button
      type="button"
      onClick={openVersionRecord}
      title="查看版本记录"
      aria-label={`查看版本记录（当前 ${["Demo v" + latest.versionNo, latest.prdVersionNo != null ? `基于 PRD v${latest.prdVersionNo} 快照` : null, latest.confirmedAt != null ? "已确认" : null].filter(Boolean).join(" · ")}）`}
      className="shrink-0 rounded-full outline-none transition-transform focus-visible:ring-2 focus-visible:ring-ring/50 active:scale-95"
    >
      <Badge variant="outline" className="gap-1 font-normal text-muted-foreground">
        Demo v{latest.versionNo}
        {latest.prdVersionNo != null ? ` · 基于 PRD v${latest.prdVersionNo} 快照` : null}
        {latest.confirmedAt != null ? " · 已确认" : null}
      </Badge>
    </button>
  );
}
