import { cn } from "@/lib/utils";
import type { components } from "@/lib/api/schema";

type DimensionStatus = components["schemas"]["DimensionStatus"];

/**
 * 收敛卡（#346，spec #320 ⑥ 需求分析页左栏首张，照原型）：「逐步澄清与
 * 收敛流水线」＋「n/5 维度就绪」徽章＋五维 pill（✓ 就绪／⚡ 次要待定／
 * 灰 阻碍缺口）。消费 #331 维度态投影（readiness.dimensions 固定五维序）；
 * 既定例外（#315）：不取原型「收敛度 92%」百分比与进度条——n/5 徽章替代。
 * 无维度数据（闲聊期 PRD 未产出）＝五维固定序词表全灰兜底、0/5。
 */

/** 五维固定序词表的空态镜像（正本＝后端 ReadinessDimension；闲聊期无 API 投影可消费）。 */
const DIMENSION_FALLBACK = ["目标与角色", "主流程", "功能与边界", "数据与约束", "验收"] as const;

export function ConvergenceCard({ dimensions }: { dimensions?: DimensionStatus[] }) {
  const pills = dimensions?.length
    ? dimensions.map((d) => ({ name: d.dimension ?? "", status: d.status ?? "" }))
    : DIMENSION_FALLBACK.map((name) => ({ name, status: "blocking" }));
  const readyCount = pills.filter((p) => p.status === "ready").length;

  return (
    <section
      aria-label="逐步澄清与收敛流水线"
      className="flex flex-col gap-3.5 rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1"
    >
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2.5">
          <span className="relative flex size-2.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-mint-teal opacity-75" />
            <span className="relative inline-flex size-2.5 rounded-full bg-mint-teal" />
          </span>
          <h2 className="text-[15px] font-bold tracking-tight text-ink-primary">
            逐步澄清与收敛流水线
          </h2>
        </div>
        <span className="flex items-center gap-1.5 rounded-full border border-mint-teal/40 bg-surface-mist/60 px-2.5 py-1 font-mono text-code-sm font-semibold text-mint-teal">
          <span className="size-1.5 rounded-full bg-mint-teal" aria-hidden="true" />
          {readyCount}/5 维度就绪
        </span>
      </div>

      {/* 五维 pill（✓ 就绪／⚡ 次要待定／灰 阻碍缺口——#331 状态词表直映） */}
      <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
        {pills.map((pill) => (
          <span
            key={pill.name}
            data-dimension={pill.name}
            data-status={pill.status}
            className={cn(
              "flex items-center gap-1 rounded-lg border px-2.5 py-1 text-label-sm shadow-xs",
              pill.status === "ready" &&
                "border-emerald-active/30 bg-emerald-active/10 font-medium text-success-deep",
              pill.status === "minor" &&
                "animate-pulse border-electric-indigo/30 bg-surface-violet-tint font-semibold text-electric-indigo",
              pill.status !== "ready" &&
                pill.status !== "minor" &&
                "border-slate-soft/70 bg-surface-dawn font-medium text-ink-faint",
            )}
          >
            {pill.status === "ready" ? (
              <span className="font-bold text-emerald-active" aria-hidden="true">✓</span>
            ) : pill.status === "minor" ? (
              <span className="font-bold text-electric-indigo" aria-hidden="true">⚡</span>
            ) : null}
            {pill.name}
          </span>
        ))}
      </div>
    </section>
  );
}
