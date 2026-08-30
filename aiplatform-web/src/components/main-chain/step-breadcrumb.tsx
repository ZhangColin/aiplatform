import { ChevronRight } from "lucide-react";
import { Fragment } from "react";

import type { StageStatus } from "@/lib/main-chain/stages";
import { cn } from "@/lib/utils";

/**
 * 步条面包屑主体（共享层内部件）：stage 口径（StageBreadcrumb）与旅程口径
 * （JourneyBreadcrumb）的同构横向呈现——已完成弱化 / 当前高亮 / 未来半透明。
 * 只收 `{key, label, status}` 同构项，不认场景类型。
 */

export type BreadcrumbItem = {
  key: string;
  label: string;
  status: StageStatus;
};

export function StatusBreadcrumb({
  items,
  className,
}: {
  items: BreadcrumbItem[];
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex min-w-0 items-center gap-0.5 overflow-x-auto text-xs whitespace-nowrap",
        className,
      )}
    >
      {items.map((item, i) => (
        <Fragment key={item.key}>
          {i > 0 && <ChevronRight className="size-3 shrink-0 text-muted-foreground/50" />}
          <span
            className={cn(
              item.status === "current"
                ? "font-medium text-foreground"
                : item.status === "done"
                  ? "text-muted-foreground"
                  : "text-muted-foreground/60",
            )}
          >
            {item.label}
          </span>
        </Fragment>
      ))}
    </div>
  );
}
