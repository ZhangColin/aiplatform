import { Badge } from "@/components/ui/badge";
import { stageLabel, type ProjectStageKey } from "@/lib/projects/list";

/**
 * 四态徽标（#205 三面渗透）：路标层只报状态不带金额（金额进项目语境内看——
 * 对话区报价卡与订单 tab）。档位单点：待支付 = 最强视觉档（primary 实底——
 * 「平台找我有事」一进站/扫一眼即见）；其余三态次级胶囊，呈现不劣化。
 * 首页最近项目卡与项目列表卡共用，两面分量同口径。
 */
export function StageBadge({ stage }: { stage: ProjectStageKey }) {
  const strong = stage === "awaiting_payment";
  return (
    <Badge variant={strong ? "default" : "secondary"} className="px-1.5 py-0 text-xs">
      {stageLabel(stage)}
    </Badge>
  );
}
