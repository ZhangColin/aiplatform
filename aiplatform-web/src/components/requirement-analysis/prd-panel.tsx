"use client";

import { FileText } from "lucide-react";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Ref } from "react";

import type { components } from "@/lib/api/schema";
import { splitPrdSections } from "@/lib/projects/prd-sections";

import { prdMarkdownComponents } from "../project-page/prd-doc";

type PrdReadiness = components["schemas"]["PrdReadinessResponse"];

/**
 * PRD 文档面（#346，spec #320 ⑥ 需求分析页右栏，照原型三新件——均为新建）：
 * 文档头（题块＋ISO 行＋就绪徽标行＋锚点导航 pill）＋结构化分节（## 章成节、
 * 节自带锚 id——分节提取归 lib/projects/prd-sections 单点）＋ SPEC_PARSER
 * 审计条（静态照原型，票注明示「静态照留」；「核对规则表」为装饰件、非控件——
 * 侧栏「系统架构与规约」同款先例）。
 *
 * <p>就绪徽标＝#331 维度态投影（readiness 直消）：无阻碍＋无次要＝「核心功能
 * 已收敛」；次要＝「N 项次要指标待确认」；阻碍＝「N 项核心流程待补齐」。markdown
 * 渲染样式复用 prd-doc 导出的 prdMarkdownComponents（PRD 呈现样式单点）。导出
 * （打印/Word）取件面＝articleRef 所指正文容器（页面装配层持有）。</p>
 */
export function PrdPanel({
  content,
  readiness,
  articleRef,
}: {
  /** PRD markdown 正文（当前工作稿）。 */
  content: string;
  /** #331 就绪投影（缺省＝后端未投影——徽标行不出）。 */
  readiness?: PrdReadiness;
  /** 正文容器 ref（导出取 innerHTML——打印/Word 同源）。 */
  articleRef?: Ref<HTMLElement>;
}) {
  const { preamble, sections } = splitPrdSections(content);
  const blockingCount = readiness?.blocking?.length ?? 0;
  const minorCount = readiness?.minor?.length ?? 0;

  return (
    <section
      aria-label="结构化需求规格说明书 (PRD)"
      className="flex h-full min-h-0 flex-col overflow-hidden rounded-2xl border border-slate-soft/80 bg-white shadow-e1"
    >
      {/* 文档头（题块＋徽标行＋锚点导航） */}
      <div className="flex shrink-0 flex-col gap-3 border-b border-slate-soft/70 bg-linear-to-b from-surface-dawn/80 to-white p-5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <div className="flex size-8 items-center justify-center rounded-xl border border-electric-indigo/30 bg-surface-violet-tint text-electric-indigo shadow-xs">
              <FileText className="size-5" aria-hidden="true" />
            </div>
            <div className="flex flex-col">
              <h2 className="text-[16px] font-bold tracking-tight text-ink-primary">
                结构化需求规格说明书 (PRD)
              </h2>
              <span className="font-mono text-[10px] text-ink-faint">ISO/IEC/IEEE 29148:2018</span>
            </div>
          </div>
          {readiness ? (
            <div className="flex items-center gap-2">
              {blockingCount === 0 && minorCount === 0 ? (
                <span className="flex items-center gap-1.5 rounded-full border border-emerald-active/30 bg-emerald-active/10 px-2.5 py-1 font-mono text-code-sm font-semibold text-success-deep shadow-xs">
                  <span className="size-1.5 animate-pulse rounded-full bg-emerald-active" aria-hidden="true" />
                  核心功能已收敛
                </span>
              ) : null}
              {minorCount > 0 ? (
                <span className="rounded-full border border-electric-indigo/30 bg-surface-violet-tint px-2.5 py-1 font-mono text-code-sm font-medium text-electric-indigo shadow-xs">
                  {minorCount}项次要指标待确认
                </span>
              ) : null}
              {blockingCount > 0 ? (
                <span className="rounded-full border border-error/30 bg-error/10 px-2.5 py-1 font-mono text-code-sm font-medium text-error shadow-xs">
                  {blockingCount}项核心流程待补齐
                </span>
              ) : null}
            </div>
          ) : null}
        </div>

        {/* 锚点导航（## 章成 pill；无章头不渲染） */}
        {sections.length > 0 ? (
          <nav aria-label="PRD 章节导航" className="flex items-center gap-1.5 overflow-x-auto pt-1 pb-0.5">
            {sections.map((section, index) => (
              <a
                key={section.id}
                href={`#${section.id}`}
                onClick={(event) => {
                  // 面板内滚动容器直达（整页不跳 hash）
                  event.preventDefault();
                  document.getElementById(section.id)?.scrollIntoView({ behavior: "smooth", block: "start" });
                }}
                className="rounded-lg bg-surface-dawn px-3 py-1 text-label-sm font-medium whitespace-nowrap text-ink-muted transition-colors hover:bg-slate-soft/80 hover:text-ink-primary"
              >
                {index + 1}. {section.title}
              </a>
            ))}
          </nav>
        ) : null}
      </div>

      {/* 结构化分节正文（打印/Word 导出的取件根） */}
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain p-6">
        <article
          id="prd-print-root"
          ref={articleRef}
          className="flex flex-col gap-6 text-body-md leading-relaxed text-ink-secondary"
        >
          {preamble ? (
            <Markdown remarkPlugins={[remarkGfm]} components={prdMarkdownComponents}>
              {preamble}
            </Markdown>
          ) : null}
          {sections.map((section, index) => (
            <section key={section.id} id={section.id} className="flex scroll-mt-4 flex-col gap-3">
              <h3 className="border-b border-slate-soft/60 pb-1 text-body-md font-bold tracking-tight text-ink-primary">
                {index + 1}.0 {section.title}
              </h3>
              <Markdown remarkPlugins={[remarkGfm]} components={prdMarkdownComponents}>
                {section.body}
              </Markdown>
            </section>
          ))}
        </article>
      </div>

      {/* SPEC_PARSER 审计条（静态照原型，票注明示；装饰件非控件） */}
      <div className="flex shrink-0 flex-col items-center justify-between gap-3 border-t border-slate-soft/80 bg-surface-dawn/90 p-4 sm:flex-row">
        <div className="flex flex-wrap items-center gap-3">
          <span className="flex items-center gap-1.5 rounded-lg border border-slate-soft/70 bg-white px-2.5 py-1 font-mono text-code-sm text-ink-faint shadow-xs">
            <span className="size-2 rounded-full bg-emerald-active" aria-hidden="true" />
            <span className="font-bold text-ink-primary">SPEC_PARSER: ACTIVE</span>
          </span>
          <span className="hidden text-slate-soft sm:inline" aria-hidden="true">|</span>
          <span className="font-mono text-code-sm text-ink-muted">
            收敛规则数: <strong className="text-electric-indigo">18</strong> 条已锁定
          </span>
          <span className="hidden text-slate-soft sm:inline" aria-hidden="true">|</span>
          <span className="truncate font-mono text-code-sm text-ink-faint">
            基线语义哈希: <span className="text-ink-muted">0x4a9b7e92c28f01b</span>
          </span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-label-sm font-medium text-ink-faint">已准备生成下一阶段工程原语</span>
          <span className="cursor-default rounded-lg border border-slate-soft bg-white px-3 py-1 text-label-sm font-semibold text-ink-muted shadow-xs select-none">
            核对规则表
          </span>
        </div>
      </div>
    </section>
  );
}
