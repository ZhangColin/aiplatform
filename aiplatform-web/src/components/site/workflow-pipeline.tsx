import {
  CircleCheck,
  FileText,
  Lock,
  MessagesSquare,
  MonitorPlay,
  MoveRight,
  NotebookPen,
  PenLine,
  ReceiptText,
  RefreshCw,
  Rocket,
  Factory,
  Shield,
} from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/lib/utils";

/**
 * 首页流程画板（#345，spec #320 ⑥ 首页 P01 照原型）：PHASE 01/02 两段九步 +
 * 步内方向指示 + 锁版里程碑 + 核心保障条。纯静态装饰版（照原型）——话术逐字
 * 保留（含装饰/夸张文案，清理留老板上线前后过）。九步各配 Material 图标位
 * （以 lucide 同义件呈现）。
 */

type WorkflowStep = {
  code: string;
  title: string;
  note: string;
  icon: ReactNode;
  variant?: "default" | "core" | "final";
  /** 角标（CORE ARTIFACT / 反馈循环）。 */
  badge?: string;
  /** 标题行尾随图标（core/final 档）。 */
  trailingIcon?: ReactNode;
};

const PHASE_ONE_STEPS: WorkflowStep[] = [
  {
    code: "STEP 01",
    title: "需求输入",
    note: "文本 / 脑图 / 表格",
    icon: <NotebookPen className="size-4" aria-hidden="true" />,
  },
  {
    code: "STEP 02",
    title: "问答澄清",
    note: "启发式补齐逻辑缺口",
    icon: <MessagesSquare className="size-4" aria-hidden="true" />,
  },
  {
    code: "STEP 03",
    title: "PRD 确认",
    note: "自动化规范文档生成",
    icon: <FileText className="size-4" aria-hidden="true" />,
  },
  {
    code: "STEP 04",
    title: "1 个 Demo",
    note: "真实可点击、全交互体验",
    icon: <MonitorPlay className="size-5" aria-hidden="true" />,
    variant: "core",
    badge: "CORE ARTIFACT",
    trailingIcon: <CircleCheck className="size-4.5" aria-hidden="true" />,
  },
  {
    code: "STEP 05",
    title: "反馈迭代",
    note: "按反馈毫秒更新Demo",
    icon: <RefreshCw className="size-4" aria-hidden="true" />,
    badge: "反馈循环",
  },
];

const PHASE_TWO_STEPS: WorkflowStep[] = [
  {
    code: "STEP 06",
    title: "确认版本",
    note: "固化PRD与UI交互拓扑",
    icon: <Lock className="size-4" aria-hidden="true" />,
  },
  {
    code: "STEP 07",
    title: "合同签署",
    note: "规范权责与生产周期",
    icon: <PenLine className="size-4" aria-hidden="true" />,
  },
  {
    code: "STEP 08",
    title: "开工款核验",
    note: "对公打款/网银直连对账",
    icon: <ReceiptText className="size-4" aria-hidden="true" />,
  },
  {
    code: "STEP 09 · FINAL",
    title: "待安排生产",
    note: "生产集群排期接入",
    icon: <Factory className="size-5" aria-hidden="true" />,
    variant: "final",
    trailingIcon: <Rocket className="size-4.5" aria-hidden="true" />,
  },
];

export function WorkflowPipeline() {
  return (
    <section className="w-full border-y border-slate-soft/80 bg-gradient-to-b from-slate-100/60 via-blue-50/30 to-slate-100/60 py-16">
      <div className="mx-auto flex w-full max-w-6xl flex-col px-4 md:px-6 lg:px-8">
        {/* 区块头 */}
        <div className="mb-10 flex flex-col justify-between gap-4 md:flex-row md:items-end">
          <div>
            <div className="mb-2 flex items-center gap-2">
              <span className="size-2 rounded-full bg-electric-indigo" aria-hidden="true" />
              <span className="font-mono text-code-sm font-semibold tracking-wider text-electric-indigo uppercase">
                Operational Workflow
              </span>
            </div>
            <h2 className="text-headline-xl-mobile font-semibold tracking-tight text-ink-primary md:text-headline-xl">
              从需求对话到可交互 Demo，再衔接签约与生产
            </h2>
            <p className="mt-1 text-body-lg text-ink-muted">
              全流程严谨闭环 · 验证认可后付款开工 · 零盲盒交付机制
            </p>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-soft bg-white px-3 py-1.5 text-label-sm font-medium text-ink-muted shadow-e1">
              <span className="size-2 rounded-full bg-emerald-active" aria-hidden="true" /> 契约驱动
            </span>
            <span className="inline-flex items-center gap-1.5 rounded-lg border border-slate-soft bg-white px-3 py-1.5 text-label-sm font-medium text-ink-muted shadow-e1">
              <span className="size-2 rounded-full bg-electric-indigo" aria-hidden="true" /> 1:1高保真Demo
            </span>
          </div>
        </div>

        {/* 画板 */}
        <div className="w-full rounded-3xl border border-slate-soft/80 bg-white/90 p-6 shadow-e2 backdrop-blur-sm md:p-8">
          {/* PHASE 01 */}
          <div className="mb-10">
            <PhaseHeader
              tag="PHASE 01"
              tagTone="indigo"
              title="阶段一：语义探索与可交互验证"
              note="纯自然语言对齐 · 毫秒级生成验证"
            />
            <div className="relative grid grid-cols-1 gap-3 md:grid-cols-5">
              {PHASE_ONE_STEPS.map((step) => (
                <StepCard key={step.code} step={step} />
              ))}
            </div>
            {/* 步内方向指示 */}
            <div className="mt-3 hidden items-center justify-between px-6 font-mono text-[12px] text-ink-muted md:flex">
              {["输入直达", "对齐逻辑", "输出规格"].map((label) => (
                <span key={label} className="flex items-center gap-1">
                  {label} <MoveRight className="size-3.5 text-ink-faint" aria-hidden="true" />
                </span>
              ))}
              <span className="flex items-center gap-1 rounded-md border border-subtle-blue/60 bg-indigo-50/80 px-2 py-0.5 font-semibold text-electric-indigo">
                ← 按反馈重编译 / 满意则进入签约 →
              </span>
            </div>
          </div>

          {/* 锁版里程碑 */}
          <div className="relative my-8 flex items-center justify-center border-t border-slate-soft/80">
            <span className="flex items-center gap-2 rounded-full border border-slate-soft/80 bg-white px-4 py-1 text-label-md font-medium text-ink-muted shadow-e1">
              <CircleCheck className="size-4.5 text-emerald-active" aria-hidden="true" />
              认可Demo后锁定版本 · 开启履约通道
            </span>
          </div>

          {/* PHASE 02 */}
          <div>
            <PhaseHeader
              tag="PHASE 02"
              tagTone="cyan"
              title="阶段二：契约锁定与履约保障"
              note="法务级电子合同 · 资金存管核验"
            />
            <div className="grid grid-cols-1 gap-3 md:grid-cols-4">
              {PHASE_TWO_STEPS.map((step) => (
                <StepCard key={step.code} step={step} />
              ))}
            </div>
          </div>

          {/* 保障条 */}
          <div className="mt-8 flex items-start gap-3.5 rounded-2xl border border-subtle-blue/60 bg-gradient-to-r from-blue-50/70 via-indigo-50/40 to-slate-50/70 p-4">
            <div className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg bg-indigo-100 text-electric-indigo">
              <Shield className="size-5" aria-hidden="true" />
            </div>
            <div className="flex flex-col">
              <span className="text-headline-sm font-semibold text-ink-primary">
                语义引力核心保障机制
              </span>
              <p className="mt-0.5 text-body-md text-ink-muted">
                合同要求的签署完成且开工付款条件满足后，才正式划入生产排期。在此之前，需求沟通、PRD文档梳理以及 1 个高保真 Demo 的迭代验证均无需承担不可预期的盲盒风险。
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

/** 阶段小标题（PHASE 徽章 + 标题 + 尾注）。 */
function PhaseHeader({
  tag,
  tagTone,
  title,
  note,
}: {
  tag: string;
  tagTone: "indigo" | "cyan";
  title: string;
  note: string;
}) {
  return (
    <div className="mb-4 flex items-center justify-between">
      <div className="flex items-center gap-2.5">
        <span
          className={cn(
            "rounded-md border px-2.5 py-0.5 font-mono text-code-sm font-semibold",
            tagTone === "indigo"
              ? "border-indigo-200/60 bg-indigo-50 text-electric-indigo"
              : "border-cyan-200/60 bg-cyan-50 text-cyan-700",
          )}
        >
          {tag}
        </span>
        <span className="text-headline-sm font-semibold text-ink-primary">{title}</span>
      </div>
      <span className="hidden font-mono text-code-sm text-ink-muted sm:inline">{note}</span>
    </div>
  );
}

/** 单步卡（default＝灰卡；core＝主色高亮卡（CORE ARTIFACT）；final＝成果绿卡）。 */
function StepCard({ step }: { step: WorkflowStep }) {
  const core = step.variant === "core";
  const final = step.variant === "final";
  return (
    <div
      className={cn(
        "relative flex flex-col justify-between rounded-2xl border p-4 transition-all",
        core
          ? "border-indigo-500 bg-gradient-to-br from-electric-indigo via-electric-indigo to-tech-blue text-white shadow-[0_8px_24px_rgba(79,70,229,0.3)]"
          : final
            ? "border-emerald-200 bg-gradient-to-br from-emerald-50 via-teal-50 to-cyan-50 shadow-[0_4px_16px_rgba(16,185,129,0.12)]"
            : "border-slate-soft/70 bg-slate-50/70 hover:border-indigo-200 hover:bg-white hover:shadow-e1",
      )}
    >
      {step.badge ? (
        <div
          className={cn(
            "absolute -top-2.5 right-3 rounded-full px-2.5 py-0.5 font-mono text-[10px] font-bold tracking-wider uppercase shadow-sm",
            core
              ? "bg-gradient-to-r from-emerald-400 to-teal-300 text-ink-primary"
              : "border border-cyan-100 bg-cyan-50 text-cyan-700",
          )}
        >
          {step.badge}
        </div>
      ) : null}
      <div>
        <span
          className={cn(
            "font-mono text-[11px] font-semibold",
            core ? "text-indigo-200" : final ? "font-bold tracking-wider text-emerald-700 uppercase" : "text-ink-faint",
          )}
        >
          {step.code}
        </span>
        <div
          className={cn(
            "mt-1 flex items-center gap-1.5",
            core || final ? "text-headline-md font-bold" : "text-headline-sm font-semibold",
            core ? "text-white" : final ? "text-emerald-900" : "text-ink-primary",
          )}
        >
          <span>{step.title}</span>
          {step.trailingIcon}
        </div>
      </div>
      <div
        className={cn(
          "mt-4 flex items-center justify-between border-t pt-3",
          core
            ? "border-white/20 text-indigo-100"
            : final
              ? "border-emerald-200/60 text-emerald-800"
              : "border-slate-soft/60 text-ink-muted",
        )}
      >
        <span className={cn("text-label-sm", core ? "font-medium" : final ? "font-semibold" : "")}>
          {step.note}
        </span>
        <span className={cn(core ? "text-teal-300" : final ? "text-emerald-600" : "text-ink-faint")}>
          {step.icon}
        </span>
      </div>
    </div>
  );
}
