import { cn } from "@/lib/utils";

/**
 * 项目首字色块头像（#72 定稿：比 emoji 更产品化）。色调由项目名哈希稳定
 * 派生（同名恒色、跨页一致），五色轮转（Light 淡底深字 / Dark 深底亮字）。
 */

const TINTS = [
  "bg-rose-100 text-rose-700 dark:bg-rose-950 dark:text-rose-300",
  "bg-amber-100 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
  "bg-emerald-100 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
  "bg-sky-100 text-sky-700 dark:bg-sky-950 dark:text-sky-300",
  "bg-violet-100 text-violet-700 dark:bg-violet-950 dark:text-violet-300",
] as const;

/** 名字 → 色调类（charCode 求和取模；空名落中性灰）。 */
export function avatarTint(name: string): string {
  if (!name.trim()) return "bg-muted text-muted-foreground";
  let sum = 0;
  for (const ch of name) sum += ch.codePointAt(0) ?? 0;
  return TINTS[sum % TINTS.length];
}

export function ProjectAvatar({
  name,
  className,
}: {
  name: string;
  className?: string;
}) {
  return (
    <span
      className={cn(
        "flex size-7 shrink-0 items-center justify-center rounded-lg text-xs font-bold",
        avatarTint(name),
        className,
      )}
      aria-hidden
    >
      {name.slice(0, 1)}
    </span>
  );
}
