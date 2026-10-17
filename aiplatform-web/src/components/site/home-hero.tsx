"use client";

import {
  ArrowRight,
  ArrowUpRight,
  Brain,
  CalendarClock,
  Paperclip,
  RotateCcw,
  ShieldCheck,
  TableProperties,
  X,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import type { ChangeEvent, KeyboardEvent, ReactNode } from "react";
import { useRef, useState } from "react";
import { toast } from "sonner";

import { useCreateProject } from "@/hooks/use-create-project";
import { useRecentProjects } from "@/hooks/use-projects";
import { errorText } from "@/lib/api/api-error";
import { isSubmitEnter } from "@/lib/chat/enter";
import { buildCreateProjectCommand } from "@/lib/projects/create";
import { formatFileSize } from "@/lib/projects/files";
import { lastTouchedAt, recentProjects } from "@/lib/projects/list";
import { cn } from "@/lib/utils";
import { formatRelativeTime } from "@/lib/utils/time";

/**
 * 首页 Hero（#345，spec #320 ⑥ 首页 P01 照原型；话术逐字保留）：徽章 + 主标
 * 「你想做一个什么产品？」+ 副标 + 草稿恢复横幅（真会话数据——有效访客/账号
 * 会话最近未归档项目，无会话不呈现）+ 大输入卡（附加资料 + 免注册提示 + CTA
 * 「开始梳理需求」）+ 示例 chip×3（一点即填全文）。
 *
 * 一句话提交照旧建项目（POST /api/projects，项目名后端 LLM 取）→ 直进项目空间
 * 新壳（#343）。主链路载荷零变化——本票删除模式切换 pill/模板卡/最近项目卡区
 * 三件（#310 设计收起），回单档（做系统）呈现、不再携终点字段；PLATFORM_MODES
 * 注册表/live 位（lib/modes）机制保留不动。
 *
 * <p>{@code credentialPresent}＝服务端 cookie 面判定的凭证存在性：匿名首访不发
 * 最近项目背景查询（只逛零足迹——横幅无数据自然不呈现），也不因背景 401 弹 M01。</p>
 */

/** 首页快速起步样例（照原型：chip 题目 + 概述 + 点击填入输入框的完整需求句
 * ——原型 fillPrompt 参数逐字）。 */
type HomeExample = {
  icon: ReactNode;
  title: string;
  description: string;
  /** 点击 chip 填入输入框的完整需求句（照原型 fillPrompt 全文）。 */
  fill: string;
  /** 图标底色调（照原型 blue / indigo / cyan 三色轮）。 */
  tint: string;
};

const HOME_EXAMPLES: HomeExample[] = [
  {
    icon: <CalendarClock className="size-4.5" aria-hidden="true" />,
    title: "做一个预约系统",
    description: "移动端选时、日历排期与自动化核销看板",
    fill: "做一个移动端客户预约系统，支持顾问日历排期、客户时段预约、短信通知与现场二维码核销",
    tint: "border-blue-100/60 bg-blue-50 text-blue-600",
  },
  {
    icon: <TableProperties className="size-4.5" aria-hidden="true" />,
    title: "把表格流程做成工具",
    description: "多表分散统计转为结构化协同与权限系统",
    fill: "把我们目前的Excel分散销售统计与审批流重构为一套结构化企业内部协作系统，包含权限分配和实时数据报表",
    tint: "border-subtle-blue/60 bg-indigo-50 text-electric-indigo",
  },
  {
    icon: <Brain className="size-4.5" aria-hidden="true" />,
    title: "我有想法，帮我理清",
    description: "语义启发式对齐，层层拆解直接生成结构化PRD",
    fill: "我目前只有一个粗略构想：想做一个面向自由职业者的AI财务合规助手。请先通过多轮提问帮我理清业务路径和核心功能",
    tint: "border-cyan-100/60 bg-cyan-50 text-cyan-600",
  },
];

/** 本地挂载附件条目（首页无上传管道——#280 入口票；选即挂 chip、随发即弃）。 */
type LocalAttachment = { id: string; name: string; sizeLabel: string };

export function HomeHero({ credentialPresent }: { credentialPresent: boolean }) {
  const router = useRouter();
  const [requirement, setRequirement] = useState("");
  const [attachments, setAttachments] = useState<LocalAttachment[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const createProject = useCreateProject();
  // 草稿恢复＝有效会话（owner 过滤后的列表）里最近一次的未归档项目；无则不呈现。
  // 匿名首访（无凭证 cookie）不发列表请求——「只逛零足迹」。
  const recent = recentProjects(useRecentProjects(credentialPresent), 1)[0];
  const draftAge = recent ? formatRelativeTime(lastTouchedAt(recent)) : "";

  const canSubmit = requirement.trim().length > 0 && !createProject.isPending;

  function submit() {
    const text = requirement.trim();
    if (!text || createProject.isPending) return;
    createProject.mutate(buildCreateProjectCommand({ requirement: text }), {
      onSuccess: (result) => {
        const projectId = result.project?.id;
        if (!projectId) {
          toast.error("项目已创建，但未返回项目标识，请到项目列表查看");
          return;
        }
        router.push(`/projects/${projectId}`);
      },
      onError: (error) => toast.error(errorText(error, "创建失败，请稍后重试")),
    });
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (!isSubmitEnter(event)) return;
    event.preventDefault();
    submit();
  }

  function onFilesSelected(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (files.length === 0) return;
    setAttachments((prev) => [
      ...prev,
      ...files.map((file, index) => ({
        id: `${Date.now()}-${index}-${file.name}`,
        name: file.name,
        sizeLabel: formatFileSize(file.size),
      })),
    ]);
  }

  return (
    <section className="relative w-full overflow-hidden">
      {/* 氛围光晕（照原型——三团柔和色晕，纯装饰） */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-36 left-1/2 -z-10 h-[520px] w-[1100px] -translate-x-1/2 rounded-full bg-gradient-to-b from-blue-100/60 via-indigo-50/50 to-transparent blur-3xl"
      />
      <div
        aria-hidden="true"
        className="pointer-events-none absolute top-24 right-1/4 -z-10 size-80 rounded-full bg-cyan-100/40 blur-3xl"
      />

      <div className="mx-auto flex w-full max-w-6xl flex-col items-center px-4 pt-14 pb-16 text-center md:px-6 lg:px-8">
        {/* 顶部徽章 */}
        <div className="mb-6 inline-flex items-center gap-1 rounded-full border border-subtle-blue/60 bg-white/80 px-3.5 py-1 shadow-e1 backdrop-blur-md">
          <span className="size-1.5 animate-ping rounded-full bg-electric-indigo" aria-hidden="true" />
          <span className="bg-linear-to-r from-electric-indigo via-tech-blue to-mint-teal bg-clip-text font-mono text-[11px] font-semibold tracking-wider text-transparent uppercase">
            SEMANTIC GRAVITY · AI NATIVE DELIVERY OS
          </span>
        </div>

        {/* 主标 + 副标 */}
        <h1 className="max-w-4xl text-headline-xl-mobile font-semibold tracking-tight text-ink-primary md:text-[56px] md:leading-[64px]">
          你想做一个什么产品？
        </h1>
        <p className="mt-4 max-w-2xl text-body-lg text-ink-muted">
          聊清需求，形成 PRD，再体验你的专属 Demo。所见即所得，零盲盒交付。
        </p>

        {/* 草稿恢复横幅（真会话数据；无会话不呈现） */}
        {recent ? (
          <div className="mt-8 flex w-full max-w-3xl items-center justify-between rounded-xl border border-slate-soft bg-white/90 px-3 py-2.5 text-left shadow-e1 backdrop-blur-sm transition-colors hover:border-indigo-200">
            <div className="flex min-w-0 items-center gap-2">
              <div className="flex size-7 shrink-0 items-center justify-center rounded-lg border border-subtle-blue/60 bg-indigo-50">
                <RotateCcw className="size-4 text-electric-indigo" aria-hidden="true" />
              </div>
              <div className="flex min-w-0 items-center gap-1">
                <span className="shrink-0 rounded-md border border-subtle-blue/60 bg-indigo-50 px-2 py-0.5 text-label-sm font-semibold text-electric-indigo">
                  草稿恢复
                </span>
                <span className="truncate text-body-sm font-medium text-ink-secondary">
                  {recent.name || "未命名项目"}
                </span>
                {draftAge ? (
                  <span className="hidden shrink-0 font-mono text-[11px] text-ink-faint sm:inline">
                    · {draftAge}自动暂存
                  </span>
                ) : null}
              </div>
            </div>
            <Link
              href={`/projects/${recent.id}`}
              className="group flex shrink-0 items-center gap-0.5 pl-3 text-label-md font-medium text-electric-indigo transition-colors hover:text-indigo-800"
            >
              继续梳理
              <ArrowRight
                className="size-4 transition-transform group-hover:translate-x-0.5"
                aria-hidden="true"
              />
            </Link>
          </div>
        ) : null}

        {/* 大输入卡 */}
        <div className="mt-4 flex w-full max-w-3xl flex-col rounded-2xl border border-slate-soft bg-white text-left shadow-e2 transition-shadow focus-within:border-electric-indigo focus-within:shadow-focus-halo">
          <div className="p-5 pb-2">
            <label className="sr-only" htmlFor="home-demand-input">
              产品需求描述
            </label>
            <textarea
              id="home-demand-input"
              value={requirement}
              onChange={(e) => setRequirement(e.target.value)}
              onKeyDown={onKeyDown}
              rows={4}
              placeholder="说说你想解决的问题，比如做一个客户预约系统，支持移动端日历选择、顾问分配和核销统计……"
              className="w-full resize-none bg-transparent text-body-lg text-ink-secondary outline-none placeholder:text-ink-faint"
            />
            {attachments.length > 0 ? (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {attachments.map((a) => (
                  <span
                    key={a.id}
                    className="flex items-center gap-1.5 rounded-lg border border-slate-soft bg-surface-dawn/60 py-1 pl-2 pr-1 text-body-sm text-ink-muted"
                  >
                    <Paperclip className="size-3.5 text-ink-faint" aria-hidden="true" />
                    <span className="max-w-48 truncate">{a.name}</span>
                    <span className="text-[11px] text-ink-faint">{a.sizeLabel}</span>
                    <button
                      type="button"
                      aria-label={`移除${a.name}`}
                      className="rounded p-0.5 text-ink-faint transition-colors hover:bg-white hover:text-ink-primary"
                      onClick={() => setAttachments((prev) => prev.filter((x) => x.id !== a.id))}
                    >
                      <X className="size-3" />
                    </button>
                  </span>
                ))}
              </div>
            ) : null}
          </div>
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-b-2xl border-t border-slate-soft bg-gradient-to-r from-surface-dawn/80 via-white to-surface-mist/30 px-5 py-2">
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => fileInputRef.current?.click()}
                className="inline-flex items-center gap-1.5 rounded-lg border border-transparent px-2.5 py-1.5 text-label-md text-ink-muted transition-all hover:border-slate-soft hover:bg-surface-dawn/70 hover:text-electric-indigo"
              >
                <Paperclip className="size-4.5 text-ink-faint" aria-hidden="true" />
                附加资料
              </button>
              <input
                ref={fileInputRef}
                type="file"
                multiple
                className="sr-only"
                aria-label="附加资料上传"
                onChange={onFilesSelected}
              />
              <div className="hidden items-center gap-1.5 rounded-md border border-slate-soft bg-white/70 px-2.5 py-1 text-label-sm text-ink-muted sm:flex">
                <ShieldCheck className="size-3.5 text-emerald-active" aria-hidden="true" />
                免注册即可开始 · 私有云自动隔离保存
              </div>
            </div>
            <button
              type="button"
              onClick={submit}
              disabled={!canSubmit}
              className={cn(
                "group inline-flex items-center gap-1 rounded-xl px-5 py-2.5 text-headline-sm font-semibold text-white shadow-glow-primary transition-all",
                "bg-gradient-to-r from-electric-indigo to-tech-blue hover:from-indigo-700 hover:to-blue-700",
                "disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none",
              )}
            >
              开始梳理需求
              <ArrowRight
                className="size-4.5 transition-transform group-hover:translate-x-0.5"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>

        {/* 示例 chip×3（一点即填全文） */}
        <div className="mt-6 flex w-full max-w-3xl flex-col items-stretch gap-3 sm:flex-row">
          {HOME_EXAMPLES.map((example) => (
            <button
              key={example.title}
              type="button"
              onClick={() => setRequirement(example.fill)}
              className="group flex flex-1 flex-col justify-between rounded-2xl border border-slate-soft bg-white p-3 text-left transition-all hover:border-indigo-300 hover:shadow-e2"
            >
              <div className="mb-1.5 flex items-center justify-between gap-2">
                <span className="flex items-center gap-2 text-headline-sm font-semibold text-ink-secondary transition-colors group-hover:text-electric-indigo">
                  <span
                    className={cn(
                      "flex size-7 shrink-0 items-center justify-center rounded-lg border",
                      example.tint,
                    )}
                  >
                    {example.icon}
                  </span>
                  {example.title}
                </span>
                <ArrowUpRight
                  className="size-4 shrink-0 text-ink-faint transition-transform group-hover:-translate-y-0.5 group-hover:translate-x-0.5"
                  aria-hidden="true"
                />
              </div>
              <p className="line-clamp-2 text-body-sm text-ink-muted">{example.description}</p>
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}
