import { cn } from "@/lib/utils";

/**
 * 「OS」徽标（#342）：视觉装饰件——spec 定案「保留为视觉装饰、不进品牌
 * 正本」，故对读屏隐藏（aria-hidden）；色板走 DESIGN token（电靛→科技蓝
 * 渐变浅底）。原型伴生位＝顶栏站名旁（#343/#345 消费）。
 */
export function OsMark({ className }: { className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "inline-flex items-center rounded-full border border-glass-border-hover/60 bg-linear-to-r from-surface-violet-tint to-surface-mist px-1.5 py-0.5 text-label-sm font-semibold tracking-wide text-electric-indigo",
        className,
      )}
    >
      OS
    </span>
  );
}
