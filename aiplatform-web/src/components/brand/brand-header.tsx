import { SITE_NAME } from "@/lib/brand";
import { cn } from "@/lib/utils";

import { BrandLogo } from "./brand-logo";

/**
 * 品牌头（#342 品牌落位，spec #320 ⑥）：深色圆底 logo ＋「语义引力」＋
 * 「DELIVERY PIPELINE」管线标——全局壳（侧栏品牌位）消费件；顶栏形
 * （＋OS 徽标/英文副行）归壳与首页重建票（#343/#345）。
 */
export function BrandHeader({ className }: { className?: string }) {
  return (
    <div className={cn("flex min-w-0 items-center gap-2.5", className)}>
      <BrandLogo />
      <div className="flex min-w-0 flex-col leading-tight">
        <span className="truncate text-headline-sm font-semibold tracking-tight text-ink-primary">
          {SITE_NAME}
        </span>
        <span className="truncate font-mono text-code-sm font-semibold tracking-wider text-tech-blue">
          DELIVERY PIPELINE
        </span>
      </div>
    </div>
  );
}
