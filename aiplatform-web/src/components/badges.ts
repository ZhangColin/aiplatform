/**
 * 徽章 tone → 类名的共享映射（任务 / 待办 / Bug 徽章同语汇，code review #22 收口）：
 * amber=等你动作、primary=待裁决 / 主链门、destructive=被驳回 / 致命、success=通过、
 * muted=终态 / 轻微。tone 字符串由各域 lib 推导（如 lib/tasks/task.ts 的 StatusTone），
 * 本表只做呈现映射；缺 key 回退空串 = Badge secondary 默认样。
 */
export const BADGE_TONE_CLASS: Record<string, string> = {
  amber: "border-transparent bg-amber-500 text-amber-950",
  primary: "border-transparent bg-primary/15 text-primary",
  destructive: "border-transparent bg-destructive/15 text-destructive",
  success: "border-transparent bg-emerald-600/15 text-emerald-700 dark:text-emerald-400",
  muted: "border-transparent bg-muted text-muted-foreground",
  default: "",
};

export function badgeToneClass(tone: string): string {
  return BADGE_TONE_CLASS[tone] ?? "";
}
