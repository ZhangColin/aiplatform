"use client";

import {
  AppWindow,
  CalendarCheck,
  ChevronRight,
  Clock3,
  LayoutTemplate,
  Megaphone,
  Palette,
  Store,
  UtensilsCrossed,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type ReactNode } from "react";
import { toast } from "sonner";

import { Composer } from "@/components/composer/composer";
import { ProjectAvatar } from "@/components/project-avatar";
import { Button } from "@/components/ui/button";
import { SidebarInset } from "@/components/ui/sidebar";
import { useCreateProject } from "@/hooks/use-create-project";
import { useRecentProjects } from "@/hooks/use-projects";
import { errorText } from "@/lib/api/api-error";
import { ENTRY_MODE, type EntryMode } from "@/lib/modes";
import {
  lastTouchedAt,
  projectStage,
  recentProjects,
  type ProjectSummary,
} from "@/lib/projects/list";
import { buildCreateProjectCommand } from "@/lib/projects/create";
import { useEntryModeStore } from "@/lib/store/entry-mode";
import { formatRelativeTime } from "@/lib/utils/time";

import { EndpointMark } from "./endpoint-mark";
import { StageBadge } from "./stage-badge";

/**
 * 首页 = 居中对谈入口（#72 定稿 / #76 落地；#299 入口两档，ADR-0029）：主标 +
 * 共享发送框（hero 加大一号）+ 示例需求 chips（一点即填）+ 模板卡（点击填入对应
 * 需求句）+ 最近项目卡。一句话提交建项目（POST /api/projects，项目名后端 LLM 取）
 * → 直进项目页开聊；入口档随载荷（做设计＝终点初值 1，做系统缺省不携——主链路
 * 零变化）。默认做系统（不选即主链路、「生成无门」守恒）；「先做设计」＝输入框
 * 近旁轻量显式次入口（不点开就可见），就地切换上下文（主标/placeholder/示例
 * chips/模板卡随切换、不跳页不弹层）、再点切回。
 */

/** 系统态示例需求（chips 与模板卡同源一句，照着样例开口——现状文案不动）。 */
const SYSTEM_EXAMPLES = [
  "帮我的花店做个能下单的小程序",
  "做一个餐厅扫码点单系统",
  "做个瑜伽馆课程预约页",
] as const;

/** 设计态示例需求（#299 实施文案清单——设计向样例）。 */
const DESIGN_EXAMPLES = [
  "给我的咖啡店设计一个 logo",
  "做一张开业宣传海报",
  "把小程序页面换个更精致的风格",
] as const;

/** 模板卡：图标色 + 名称 + 描述，点击填入对应需求句。 */
type TemplateCard = {
  text: string;
  icon: ReactNode;
  tint: string;
  title: string;
  description: string;
};

const SYSTEM_TEMPLATES: TemplateCard[] = [
  {
    text: SYSTEM_EXAMPLES[0],
    icon: <Store className="size-5" />,
    tint: "bg-rose-100 text-rose-600 dark:bg-rose-950 dark:text-rose-300",
    title: "花店小程序",
    description: "展示鲜花、在线下单",
  },
  {
    text: SYSTEM_EXAMPLES[1],
    icon: <UtensilsCrossed className="size-5" />,
    tint: "bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-300",
    title: "餐厅点单",
    description: "扫码看菜单、下单",
  },
  {
    text: SYSTEM_EXAMPLES[2],
    icon: <CalendarCheck className="size-5" />,
    tint: "bg-emerald-100 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-300",
    title: "预约系统",
    description: "课程表、在线预约",
  },
];

const DESIGN_TEMPLATES: TemplateCard[] = [
  {
    text: DESIGN_EXAMPLES[0],
    icon: <Palette className="size-5" />,
    tint: "bg-rose-100 text-rose-600 dark:bg-rose-950 dark:text-rose-300",
    title: "logo 设计",
    description: "品牌标识、配色气质",
  },
  {
    text: DESIGN_EXAMPLES[1],
    icon: <Megaphone className="size-5" />,
    tint: "bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-300",
    title: "宣传海报",
    description: "开业、新品、活动",
  },
  {
    text: DESIGN_EXAMPLES[2],
    icon: <LayoutTemplate className="size-5" />,
    tint: "bg-emerald-100 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-300",
    title: "页面风格",
    description: "整页换肤、视觉升级",
  },
];

/** 两档文案集（#299 实施文案清单，ADR-0029）：主标/副标/placeholder/示例 chips/
 * 模板卡随切换；设计态主标与 placeholder 为决议原文。 */
const MODE_COPY: Record<EntryMode, {
  headline: string;
  subline: string;
  placeholder: string;
  examples: readonly string[];
  templates: TemplateCard[];
}> = {
  [ENTRY_MODE.system]: {
    headline: "想做什么，直接说",
    subline: "聊清楚需求，看着它一点点变成能用的系统",
    placeholder: "一句话说说你想做什么…",
    examples: SYSTEM_EXAMPLES,
    templates: SYSTEM_TEMPLATES,
  },
  [ENTRY_MODE.design]: {
    headline: "想要什么样子，直接说",
    subline: "聊清楚想要的样子，看着它一点点画出来（logo、海报、页面样式）",
    placeholder: "一句话说说你想要的设计…（logo、海报、页面样式…）",
    examples: DESIGN_EXAMPLES,
    templates: DESIGN_TEMPLATES,
  },
};

export function CreateProjectHero() {
  const router = useRouter();
  const [requirement, setRequirement] = useState("");
  const mode = useEntryModeStore((s) => s.mode);
  const setMode = useEntryModeStore((s) => s.setMode);
  const createProject = useCreateProject();
  // 「最近的项目」取未归档项目（更新时间新→旧），卡式三列。
  const recent = recentProjects(useRecentProjects(), 6);
  const copy = MODE_COPY[mode];

  function submit(text: string) {
    createProject.mutate(buildCreateProjectCommand({ requirement: text, mode }), {
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

  return (
    <SidebarInset className="h-svh min-h-0 flex-col">
      <main className="relative min-h-0 flex-1 overflow-y-auto">
        {/* 克制的底色层次：顶部一线极淡的主色晕，向下即隐（不喧宾夺主） */}
        <div className="pointer-events-none absolute inset-x-0 top-0 h-72 bg-[radial-gradient(60%_100%_at_50%_0%,var(--color-primary)_0%,transparent_100%)] opacity-[0.05] dark:opacity-[0.09]" />
        <div className="relative mx-auto flex w-full max-w-2xl flex-col px-6 pb-24 pt-16">
          <h1 className="text-center text-3xl font-bold md:text-4xl">{copy.headline}</h1>
          <p className="mt-3 text-center text-base leading-7 text-muted-foreground">
            {copy.subline}
          </p>
          <div className="mt-6">
            {/* 「先做设计」次入口（#299，ADR-0029）：输入框近旁、不点开就可见；
                就地切换上下文（下方主标/placeholder/chips/模板卡已随 mode 换）、
                再点切回。轻量示意＝排版/色彩层次（色点），不做作品画廊零内容维护。 */}
            <div className="mb-2 flex justify-end">
              {mode === ENTRY_MODE.design ? (
                <button
                  type="button"
                  onClick={() => setMode(ENTRY_MODE.system)}
                  className="inline-flex items-center gap-1.5 rounded-full border bg-background px-3 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                >
                  <AppWindow className="size-3.5" /> 先做系统
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => setMode(ENTRY_MODE.design)}
                  className="inline-flex items-center gap-1.5 rounded-full border bg-background px-3 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                >
                  {/* 排版/色彩层次示意：三枚小色点传达「这边是设计」 */}
                  <span className="flex items-center gap-0.5" aria-hidden>
                    <span className="size-1.5 rounded-full bg-rose-400" />
                    <span className="size-1.5 rounded-full bg-amber-400" />
                    <span className="size-1.5 rounded-full bg-sky-400" />
                  </span>
                  先做设计
                </button>
              )}
            </div>
            <Composer
              hero
              value={requirement}
              onValueChange={setRequirement}
              onSubmit={submit}
              submitPending={createProject.isPending}
              mode={mode}
              onModeChange={setMode}
              placeholder={copy.placeholder}
            />
          </div>
          <div className="mt-4 flex flex-wrap justify-center gap-2">
            {copy.examples.map((example) => (
              <button
                key={example}
                type="button"
                onClick={() => setRequirement(example)}
                className="rounded-full border bg-background px-3.5 py-1.5 text-[13px] text-muted-foreground transition-all hover:border-primary/40 hover:text-foreground active:scale-95"
              >
                {example}
              </button>
            ))}
          </div>
          <div className="mt-12 grid grid-cols-3 gap-3">
            {copy.templates.map((card) => (
              <button
                key={card.title}
                type="button"
                onClick={() => setRequirement(card.text)}
                aria-label={`${card.title}：${card.description}`}
                className="group rounded-2xl border bg-background p-4 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-foreground/20 hover:shadow-md active:translate-y-0"
              >
                <span className={`inline-flex rounded-xl p-2 ${card.tint}`}>{card.icon}</span>
                <div className="mt-2.5 text-sm font-semibold">{card.title}</div>
                <div className="mt-0.5 text-[13px] text-muted-foreground">{card.description}</div>
              </button>
            ))}
          </div>
          <p className="mt-5 text-center text-[13px] text-muted-foreground">
            点模板一句话开工，或直接在输入框里描述你的想法
          </p>

          {recent.length > 0 ? (
            <div className="mt-14">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">最近的项目</h2>
                <Button
                  size="xs"
                  variant="ghost"
                  className="text-muted-foreground"
                  nativeButton={false}
                  render={<Link href="/projects" />}
                >
                  查看全部 <ChevronRight className="size-3" />
                </Button>
              </div>
              <div className="grid grid-cols-3 gap-3">
                {recent.map((project) => (
                  <RecentProjectCard key={project.id} project={project} />
                ))}
              </div>
            </div>
          ) : null}
        </div>
      </main>
    </SidebarInset>
  );
}

/** 最近项目卡：首字色块头像 + 项目名 + 四态徽标（#205 待支付最强档）·终点弱标识
 * （#299）· 更新时间，点击进项目页。 */
export function RecentProjectCard({ project }: { project: ProjectSummary }) {
  const name = project.name || "未命名项目";
  return (
    <Link
      href={`/projects/${project.id}`}
      className="group rounded-2xl border bg-background p-4 shadow-sm transition-all hover:-translate-y-0.5 hover:border-foreground/20 hover:shadow-md"
    >
      <ProjectAvatar name={name} />
      <div className="mt-2.5 truncate text-sm font-semibold">{name}</div>
      <div className="mt-1.5 flex items-center gap-1.5 text-[13px] text-muted-foreground">
        <StageBadge stage={projectStage(project)} />
        <EndpointMark
          endpointType={project.endpointType}
          endpointTypeName={project.endpointTypeName}
        />
        <Clock3 className="size-3 shrink-0" />
        <span className="truncate">{formatRelativeTime(lastTouchedAt(project))}</span>
      </div>
    </Link>
  );
}
