import { cn } from "@/lib/utils";
import type { ProjectSummary } from "@/lib/projects/list";

/**
 * 终点类型弱标识（#299，ADR-0029 项目卡回显）：文字级小标签（设计/系统/
 * 系统＋设计），不做颜色强调（不抢四态徽标视觉焦点）、不构成过滤维度。
 * 首页最近项目卡与列表页项目卡共用（两面同口径）；终点缺省（旧后端）不出。
 */

/** 弱标识文案（*Name 缺席时的兜底——与后端枚举名同源语义的平面名词形）。 */
const ENDPOINT_MARK_NAMES: Record<number, string> = {
  1: "设计",
  2: "系统",
  3: "系统＋设计",
};

export function EndpointMark({
  endpointType,
  endpointTypeName,
  className,
}: Pick<ProjectSummary, "endpointType" | "endpointTypeName"> & { className?: string }) {
  if (endpointType == null) return null;
  // 未知码不猜归属（不假装是系统）：名缺席即不出标
  const label = endpointTypeName ?? ENDPOINT_MARK_NAMES[endpointType];
  if (!label) return null;
  return (
    <span
      data-endpoint-type={endpointType}
      className={cn("shrink-0 text-xs text-muted-foreground/80", className)}
    >
      {label}
    </span>
  );
}
