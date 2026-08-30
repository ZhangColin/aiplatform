import { badgeToneClass } from "@/components/badges";
import { Badge } from "@/components/ui/badge";
import {
  bugStatusTone,
  severityLabel,
  severityTone,
  taskStatusLabel,
  taskStatusTone,
} from "@/lib/tasks/task";
import { cn } from "@/lib/utils";

/**
 * 任务系统徽章（issue #22，opc / dev 双端共用）：tone 推导归 lib/tasks（枚举
 * 唯一散点），tone → 类名归共享 BADGE_TONE_CLASS。
 */

/** 任务状态徽章：被驳回（执行中 + rejectReason）自动派生 destructive。 */
export function TaskStatusBadge({
  task,
  className,
}: {
  /** 结构子集：TaskCard / Task / TaskDetail 天然兼容。 */
  task: { status: number; statusName: string; rejectReason?: string };
  className?: string;
}) {
  const { status, statusName, rejectReason = "" } = task;
  return (
    <Badge
      variant="secondary"
      className={cn("shrink-0", badgeToneClass(taskStatusTone(status, rejectReason)), className)}
    >
      {taskStatusLabel(status, statusName, rejectReason)}
    </Badge>
  );
}

/** Bug 三态徽章（待修复 / 已修复 / 复测通过）。 */
export function BugStatusBadge({
  status,
  statusName,
  className,
}: {
  status: number;
  statusName: string;
  className?: string;
}) {
  return (
    <Badge
      variant="secondary"
      className={cn("shrink-0", badgeToneClass(bugStatusTone(status)), className)}
    >
      {statusName || "未知"}
    </Badge>
  );
}

/** 严重级徽章：致命 destructive / 严重 amber / 一般 default / 轻微 muted。 */
export function SeverityBadge({
  severity,
  severityName = "",
  className,
}: {
  severity: number;
  severityName?: string;
  className?: string;
}) {
  return (
    <Badge
      variant="secondary"
      className={cn("shrink-0", badgeToneClass(severityTone(severity)), className)}
    >
      {severityLabel(severity, severityName)}
    </Badge>
  );
}
