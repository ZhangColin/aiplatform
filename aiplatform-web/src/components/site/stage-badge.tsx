import { Badge } from "@/components/ui/badge";
import { stageLabel, type ProjectStageKey } from "@/lib/projects/list";

/** P08 生命周期阶段徽标：主徽标只报四阶段/归档，交易子态由项目卡副行承担。 */
export function StageBadge({ stage }: { stage: ProjectStageKey }) {
  return (
    <Badge variant={stage === "production" ? "success" : "secondary"} className="px-1.5 py-0 text-xs">
      {stageLabel(stage)}
    </Badge>
  );
}
