"use client";

import { cn } from "@/lib/utils";

/**
 * 阶段页移动单区域切换件（#350，交接包 13.2「手机用对话、文档或预览的单区域
 * 切换」）：窄屏（<xl）双栏退化为单区域时的区域切换共用形——胶囊语言与顶栏
 * 五行导航同款。纯受控件：响应式隐藏（xl:hidden）与区域显隐归消费侧装配。
 */
export function MobileAreaSwitch<T extends string>({
  areas,
  value,
  onChange,
}: {
  /** 区域清单（序即呈现序）。 */
  areas: readonly { key: T; label: string }[];
  value: T;
  onChange: (area: T) => void;
}) {
  return (
    <div
      role="group"
      aria-label="阶段页区域切换"
      className="flex items-center gap-0.5 rounded-xl border border-slate-soft/60 bg-surface-dawn/80 p-1"
    >
      {areas.map((area) => (
        <button
          key={area.key}
          type="button"
          aria-pressed={value === area.key}
          onClick={() => onChange(area.key)}
          className={cn(
            "flex-1 rounded-lg px-3 py-1.5 text-label-md transition-colors",
            value === area.key
              ? "bg-white font-semibold text-electric-indigo shadow-e1"
              : "font-medium text-ink-muted hover:bg-white/60 hover:text-ink-primary",
          )}
        >
          {area.label}
        </button>
      ))}
    </div>
  );
}
