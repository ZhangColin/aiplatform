import { Gavel, MessageCircle, MonitorSmartphone, Timer } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/**
 * 首页指标卡×4（#345，spec #320 ⑥ 首页 P01 照原型）：大数值 + 单位 + 标题 + 说明，
 * 话术逐字照原型（含夸张数值，清理留老板上线前后过）。
 */

type Metric = {
  icon: ReactNode;
  value: string;
  unit: string;
  title: string;
  description: string;
  /** 图标底与数值强调色（照原型 indigo / blue / emerald / cyan 四色轮）。 */
  iconTint: string;
  unitTint: string;
};

const METRICS: Metric[] = [
  {
    icon: <Timer className="size-5.5" aria-hidden="true" />,
    value: "15",
    unit: "min",
    title: "PRD 快速对齐生成",
    description: "多轮自然语言提问澄清，自动结构化生成标准化软件规格书。",
    iconTint: "border-indigo-100/80 bg-indigo-50 text-electric-indigo",
    unitTint: "text-electric-indigo",
  },
  {
    icon: <MonitorSmartphone className="size-5.5" aria-hidden="true" />,
    value: "1:1",
    unit: "仿真",
    title: "真实可操作 DEMO 验证",
    description: "拒绝静态原型画饼，直接体验完整的前后端交互与业务链路。",
    iconTint: "border-blue-100/80 bg-blue-50 text-blue-600",
    unitTint: "text-blue-600",
  },
  {
    icon: <Gavel className="size-5.5" aria-hidden="true" />,
    value: "100",
    unit: "%",
    title: "认可后启动付款开工",
    description: "PRD 与 Demo 双重确认，契约严格锁定生产交付边界与周期。",
    iconTint: "border-emerald-100/80 bg-emerald-50 text-emerald-600",
    unitTint: "text-emerald-600",
  },
  {
    icon: <MessageCircle className="size-5.5" aria-hidden="true" />,
    value: "0",
    unit: "门槛",
    title: "纯自然语言即可发起",
    description: "无需专业技术背景或编写文档，对话即可将业务灵感转化为系统工程。",
    iconTint: "border-cyan-100/80 bg-cyan-50 text-cyan-600",
    unitTint: "text-cyan-600",
  },
];

export function HomeMetrics() {
  return (
    <section className="mx-auto w-full max-w-6xl px-4 py-16 md:px-6 lg:px-8">
      <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
        {METRICS.map((metric) => (
          <div
            key={metric.title}
            className="flex flex-col justify-between rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1 transition-all hover:border-indigo-200 hover:shadow-e2"
          >
            <div>
              <div
                className={cn(
                  "mb-4 flex size-10 items-center justify-center rounded-xl border",
                  metric.iconTint,
                )}
              >
                {metric.icon}
              </div>
              <div className="text-headline-xl font-semibold tracking-tight text-ink-primary">
                {metric.value}
                <span className={cn("ml-1 text-headline-md font-bold", metric.unitTint)}>
                  {metric.unit}
                </span>
              </div>
              <div className="mt-1 text-headline-sm font-semibold text-ink-secondary">
                {metric.title}
              </div>
            </div>
            <p className="mt-3 border-t border-slate-soft/60 pt-3 text-body-sm text-ink-muted">
              {metric.description}
            </p>
          </div>
        ))}
      </div>
    </section>
  );
}
