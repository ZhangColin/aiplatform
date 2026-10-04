"use client";

/**
 * ============================================================================
 * 原 型 —— 设计过程体验（#278）：设计稿范式（分组稿卡阵列）× 三变体
 * ============================================================================
 * 验证正主：多稿呈现与挑选的动作感、渐进观感（占位→到达 / 界面类分阶段）、
 * 定稿显式动作。三变体结构不同（A 分组阵列流 / B 聚焦主舞台＋缩略带 /
 * C 代际画廊），范式语义同源（点选作用域、定稿收口、分代并存）。
 * ============================================================================
 */

import * as React from "react";
import {
  BadgeCheck,
  Check,
  Download,
  Layers,
  Maximize2,
  Palette,
  Trash2,
  X,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

import { coffeeBody, coffeeHTML, draftName, svgUrl, TOWEL_DRAFTS } from "./media";
import type { DesignState, Draft } from "./engine";

/* ---------- 共享：媒体渲染 ---------- */

export function isLive(id: string): boolean {
  return !TOWEL_DRAFTS[id];
}

const LIVE_STAGE_HINT = ["正在出稿…", "搭页面骨架…", "填内容…", "上色成形…", ""];

/** 容器实宽 → 等比缩放系数（固定画幅 frame 的缩放源）。 */
function useFitScale(baseW: number) {
  const ref = React.useRef<HTMLDivElement>(null);
  const [scale, setScale] = React.useState(0.25);
  React.useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width;
      if (w) setScale(w / baseW);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [baseW]);
  return { ref, scale };
}

/**
 * 界面类稿的固定画幅 frame（stitch screen / replit design frame 同构）：
 * 内页恒按 1280×800 桌面布局渲染、缩放进容器——设计稿是「帧」不是「网站」，
 * 不随窗口响应（正是「看着像直接写前端」的泄漏点，此为修正）。
 */
export function LiveFrame({
  draftId,
  stage = 4,
  interactive = false,
}: {
  draftId: string;
  stage?: number;
  interactive?: boolean;
}) {
  const { ref, scale } = useFitScale(1280);
  return (
    <div ref={ref} className="relative h-full w-full overflow-hidden bg-white">
      <iframe
        title={draftName(draftId)}
        srcDoc={coffeeHTML(draftId, stage)}
        className="absolute left-0 top-0 origin-top-left border-0 bg-white"
        style={{
          width: 1280,
          height: 800,
          transform: `scale(${scale})`,
          pointerEvents: interactive ? "auto" : "none",
        }}
        sandbox="allow-same-origin"
      />
    </div>
  );
}

/** 稿本体渲染：平面＝位图；界面类＝固定画幅 frame（stage 控渐进）。 */
export function DraftMedia({
  draftId,
  stage = 4,
  interactive = false,
  className,
}: {
  draftId: string;
  stage?: number;
  interactive?: boolean;
  className?: string;
}) {
  if (TOWEL_DRAFTS[draftId]) {
    return (
      // eslint-disable-next-line @next/next/no-img-element -- 原型本地 data url
      <img src={svgUrl(TOWEL_DRAFTS[draftId])} alt={draftName(draftId)} className={cn("h-full w-full object-cover", className)} />
    );
  }
  return <LiveFrame draftId={draftId} stage={stage} interactive={interactive} />;
}

/* ---------- 共享：占位（渐进呈现的一半） ---------- */

function DraftPlaceholder({ live, stage }: { live: boolean; stage: number }) {
  return (
    <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-muted/40">
      <div className="absolute inset-0 animate-pulse bg-gradient-to-br from-muted/60 via-muted/30 to-muted/60" />
      <Layers className="relative size-5 text-muted-foreground/60" />
      <div className="relative text-[11px] text-muted-foreground/80">
        {live ? LIVE_STAGE_HINT[Math.min(stage, 4)] : "正在出稿…"}
      </div>
    </div>
  );
}

/* ---------- 共享：设计规范条 ---------- */

function SpecStrip({ state }: { state: DesignState }) {
  const spec = state.spec;
  if (!spec) return null;
  return (
    <Popover>
      <PopoverTrigger className="flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted/50">
        <Palette className="size-3.5" />
        设计规范 v{spec.version}
        <span className="flex gap-0.5">
          {spec.pairs.filter(([, v]) => v.startsWith("#")).slice(0, 4).map(([k, v]) => (
            <span key={k} className="size-3 rounded-full border" style={{ background: v }} title={`${k} ${v}`} />
          ))}
        </span>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-56 p-2">
        <div className="px-1 pb-1 text-xs font-semibold">设计规范（定稿刷新 · 项目单一正本）</div>
        {spec.pairs.map(([k, v]) => (
          <div key={k} className="flex items-center gap-2 rounded px-1 py-1 text-[13px]">
            {v.startsWith("#") ? <span className="size-3.5 rounded-full border" style={{ background: v }} /> : <span className="w-3.5" />}
            <span className="flex-1">{k}</span>
            <span className="font-mono text-xs text-muted-foreground">{v}</span>
          </div>
        ))}
      </PopoverContent>
    </Popover>
  );
}

/* ---------- 共享：定稿确认 ---------- */

function FinalizeAction({ draftId, scenario, onFinalize }: { draftId: string; scenario: string; onFinalize: (id: string) => void }) {
  const [open, setOpen] = React.useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        onClick={(e) => e.stopPropagation()}
        className="flex items-center gap-1 rounded-md bg-primary px-2.5 py-1 text-xs font-medium text-primary-foreground transition-transform active:scale-95"
      >
        <BadgeCheck className="size-3.5" /> 定稿
      </PopoverTrigger>
      <PopoverContent align="end" className="w-64 p-3" onClick={(e) => e.stopPropagation()}>
        <div className="text-sm font-semibold">定稿「{draftName(draftId)}」？</div>
        <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
          定稿即收口：收进{scenario === "coffee" ? "交付物包" : "设计资产包"}、按这张刷新设计规范{scenario === "coffee" ? "，并自动开始按稿搭系统" : ""}。定稿前挑选可以随便换。
        </p>
        <div className="mt-2.5 flex justify-end gap-2">
          <Button variant="ghost" size="sm" className="h-7 text-xs" onClick={() => setOpen(false)}>再想想</Button>
          <Button size="sm" className="h-7 text-xs" onClick={() => { setOpen(false); onFinalize(draftId); }}>定稿这张</Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/* ---------- 共享：稿卡 ---------- */

function DraftCard({
  draft,
  state,
  onPick,
  onFinalize,
  onPreview,
  onDelete,
  aspect,
}: {
  draft: Draft;
  state: DesignState;
  onPick: (id: string) => void;
  onFinalize: (id: string) => void;
  onPreview: (id: string) => void;
  /** 画布整理：删掉不要的稿（定稿那张不出删除口）。 */
  onDelete?: (id: string) => void;
  /** 缺省跟介质走：界面类＝16:10 帧、平面类＝3:4。 */
  aspect?: string;
}) {
  const picked = state.picked === draft.id;
  const item = state.items.find((i) => i.id === draft.item);
  const finalized = item?.status === "done" && item.finalized === draft.id;
  const version = state.versions.find((v) => v.draftId === draft.id)?.n;
  const live = isLive(draft.media);
  const autoAspect = live ? "aspect-[8/5]" : "aspect-[3/4]";
  const cardAspect = aspect ?? autoAspect;
  return (
    <div
      className={cn(
        "group relative flex flex-col overflow-hidden rounded-xl border bg-card transition-all",
        picked ? "border-primary ring-2 ring-primary/25" : "hover:border-foreground/25",
        finalized && "border-green-600/50",
      )}
    >
      <button
        type="button"
        data-pick
        className={cn("relative block w-full cursor-pointer overflow-hidden bg-muted/20", cardAspect)}
        onClick={() => draft.state === "arrived" && onPick(draft.id)}
        title={draft.state === "arrived" ? "点选这张（挑选作用域，可再换）" : undefined}
      >
        {draft.state === "arrived" ? (
          <DraftMedia draftId={draft.media} stage={draft.stage} className="transition-all duration-500" />
        ) : (
          <DraftPlaceholder live={live} stage={draft.stage} />
        )}
        {/* 到达动效：一瞬高亮 */}
        {draft.state === "arrived" && <span className="pointer-events-none absolute inset-0 animate-in fade-in duration-500 bg-primary/5" />}
        {draft.state === "arrived" && live && draft.stage < 4 ? (
          <span className="absolute bottom-1.5 left-1.5 rounded-full bg-background/85 px-2 py-0.5 text-[10px] text-muted-foreground backdrop-blur">
            {LIVE_STAGE_HINT[draft.stage]}
          </span>
        ) : null}
        {draft.state === "arrived" ? (
          <span
            data-no-drag
            className="absolute right-1.5 top-1.5 flex size-6 items-center justify-center rounded-full bg-background/85 text-muted-foreground opacity-0 backdrop-blur transition-opacity group-hover:opacity-100"
            onClick={(e) => { e.stopPropagation(); onPreview(draft.media); }}
            title={live ? "点开试（可交互）" : "看大图"}
          >
            <Maximize2 className="size-3.5" />
          </span>
        ) : null}
        {picked ? (
          <span className="absolute left-1.5 top-1.5 flex items-center gap-1 rounded-full bg-primary px-2 py-0.5 text-[10px] font-semibold text-primary-foreground">
            <Check className="size-3" strokeWidth={3} /> 已选中
          </span>
        ) : null}
        {finalized ? (
          <span className="absolute left-1.5 top-1.5 flex items-center gap-1 rounded-full bg-green-600 px-2 py-0.5 text-[10px] font-semibold text-white">
            <BadgeCheck className="size-3" /> 定稿{version ? ` · v${version}` : ""}
          </span>
        ) : null}
      </button>
      <div className="flex items-center gap-1.5 px-2 py-1.5">
        <span className="min-w-0 flex-1 truncate text-xs text-foreground/80">
          {draftName(draft.media)}
          {live && draft.state === "arrived" ? <span className="ml-1 text-[10px] text-muted-foreground">可交互</span> : null}
        </span>
        {live ? <span className="shrink-0 font-mono text-[9px] text-muted-foreground/50">1280×800</span> : null}
        {onDelete && !finalized ? (
          <span data-no-drag className="shrink-0">
            <Popover>
              <PopoverTrigger
                onClick={(e) => e.stopPropagation()}
                onPointerDown={(e) => e.stopPropagation()}
                className="rounded p-1 text-muted-foreground/50 opacity-0 transition-colors group-hover:opacity-100 hover:text-destructive"
                aria-label={`删除${draftName(draft.media)}`}
              >
                <Trash2 className="size-3.5" />
              </PopoverTrigger>
              <PopoverContent className="w-56 p-3" onClick={(e) => e.stopPropagation()}>
                <div className="text-sm font-medium">删除「{draftName(draft.media)}」？</div>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                  把不要的稿从画布清掉；已定稿与版本不受影响。
                </p>
                <div className="mt-2 flex justify-end gap-2">
                  <PopoverTrigger className="rounded-md px-2.5 py-1 text-xs text-muted-foreground hover:bg-muted">取消</PopoverTrigger>
                  <Button variant="destructive" size="sm" className="h-7 text-xs" onClick={() => onDelete(draft.id)}>
                    删除
                  </Button>
                </div>
              </PopoverContent>
            </Popover>
          </span>
        ) : null}
        {picked && !finalized && item?.status !== "done" ? (
          <FinalizeAction draftId={draft.id} scenario={state.scenario} onFinalize={onFinalize} />
        ) : null}
      </div>
    </div>
  );
}

/* ---------- 共享：预览大图（点开预览：界面类可交互、平面类大图） ---------- */

/* ---------- 下载（stitch 双形态同构：图＝固定帧位图化、HTML＝帧源；支付门 #276） ---------- */

function triggerDownload(url: string, name: string) {
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
}

/** 界面类稿 → 1280×800 PNG（foreignObject 位图化——下载的图就是看到的那帧）。 */
async function exportFramePng(designId: string) {
  const body = coffeeBody(designId, 4);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="800"><foreignObject x="0" y="0" width="1280" height="800"><div xmlns="http://www.w3.org/1999/xhtml" style="width:1280px;height:800px;background:#fff;overflow:hidden">${body}</div></foreignObject></svg>`;
  const img = new Image();
  await new Promise((resolve, reject) => {
    img.onload = () => resolve(null);
    img.onerror = reject;
    img.src = svgUrl(svg);
  });
  const canvas = document.createElement("canvas");
  canvas.width = 1280;
  canvas.height = 800;
  canvas.getContext("2d")!.drawImage(img, 0, 0);
  await new Promise<void>((resolve) => {
    canvas.toBlob((b) => {
      if (b) triggerDownload(URL.createObjectURL(b), `${designId}-1280x800.png`);
      resolve();
    }, "image/png");
  });
}

function downloadFrameHtml(designId: string) {
  const blob = new Blob([coffeeHTML(designId, 4)], { type: "text/html" });
  triggerDownload(URL.createObjectURL(blob), `${designId}.html`);
}

function downloadFlatSvg(draftId: string) {
  const blob = new Blob([TOWEL_DRAFTS[draftId]], { type: "image/svg+xml" });
  triggerDownload(URL.createObjectURL(blob), `${draftId}.svg`);
}

export function PreviewModal({
  draftId,
  stage,
  paid,
  onClose,
}: {
  draftId: string;
  stage: number;
  /** 支付门（#276：平台随便看、带走才付费）。 */
  paid: boolean;
  onClose: () => void;
}) {
  const live = isLive(draftId);
  const [gate, setGate] = React.useState(false);
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);
  const download = (kind: "png" | "html") => {
    if (!paid) {
      setGate(true);
      return;
    }
    if (kind === "png") void exportFramePng(draftId);
    else downloadFrameHtml(draftId);
  };
  return (
    <div className="fixed inset-0 z-40 flex flex-col bg-black/50 backdrop-blur-sm" onClick={onClose}>
      <div
        className="mx-auto mt-10 flex max-h-[78vh] w-[min(920px,92vw)] flex-col overflow-hidden rounded-2xl border bg-background shadow-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex h-11 shrink-0 items-center gap-2 border-b px-4">
          <span className="text-sm font-medium">{draftName(draftId)}</span>
          {live ? (
            <Badge variant="secondary" className="text-[10px]">界面类候选 · 可直接点 · 1280×800</Badge>
          ) : (
            <Badge variant="secondary" className="text-[10px]">平面类候选</Badge>
          )}
          <span className="flex-1" />
          <Button variant="ghost" size="icon" className="size-8" onClick={onClose} aria-label="关闭预览">
            <X className="size-4" />
          </Button>
        </div>
        <div className={cn("min-h-0 flex-1 bg-muted/30", live ? "" : "flex items-center justify-center p-6")}>
          {live ? (
            <DraftMedia draftId={draftId} stage={Math.max(stage, 4)} interactive className="h-full" />
          ) : (
            // eslint-disable-next-line @next/next/no-img-element -- 原型本地 data url
            <img src={svgUrl(TOWEL_DRAFTS[draftId])} alt={draftName(draftId)} className="max-h-full rounded-lg shadow-lg" />
          )}
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-2 border-t px-4 py-2">
          <span className="text-xs text-muted-foreground">
            {live ? "固定画幅帧——下载的图就是看到的这帧" : "平面类候选 · 出稿即成品位图"}
          </span>
          <span className="flex-1" />
          {gate && !paid ? (
            <span className="text-xs text-amber-700 dark:text-amber-400">平台随便看、带走才付费——完成支付后开通下载</span>
          ) : null}
          <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => (live ? download("png") : paid ? downloadFlatSvg(draftId) : setGate(true))}>
            <Download className="size-3.5" /> 下载图{live ? "（PNG）" : "（源文件）"}
          </Button>
          {live ? (
            <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => download("html")}>
              <Download className="size-3.5" /> 下载 HTML
            </Button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/* ================= 变体 A：分组阵列流（正选基线） ================= */

function VariantA({ state, onPick, onFinalize, onPreview, onDelete }: VariantProps) {
  const itemsWithDrafts = state.items.filter((i) => state.drafts.some((d) => d.item === i.id));
  const pendingCount = state.items.filter((i) => !state.drafts.some((d) => d.item === i.id)).length;
  return (
    <div className="min-h-0 flex-1 overflow-y-auto p-4">
      {itemsWithDrafts.length === 0 ? (
        <EmptyDesignArea />
      ) : (
        <div className="mx-auto max-w-3xl space-y-8">
          {itemsWithDrafts.map((item) => {
            const gens = [...new Set(state.drafts.filter((d) => d.item === item.id).map((d) => d.gen))].sort();
            const done = item.status === "done";
            return (
              <section key={item.id}>
                <header className="mb-2.5 flex items-baseline gap-2">
                  <h3 className="text-sm font-semibold">{item.title}</h3>
                  <span className="truncate text-xs text-muted-foreground">{item.brief}</span>
                  {done ? (
                    <Badge className="ml-auto shrink-0 bg-green-600/10 text-green-700 hover:bg-green-600/10">
                      <BadgeCheck className="size-3" /> 已定稿
                    </Badge>
                  ) : (
                    <span className="ml-auto shrink-0 text-[11px] text-muted-foreground">挑选中</span>
                  )}
                </header>
                {gens.map((gen) => (
                  <div key={gen} className="mb-3">
                    <div className="mb-1.5 flex items-center gap-2 text-[11px] text-muted-foreground">
                      <span className="rounded-full border px-1.5 py-0.5">第 {gen} 代{gen > 1 ? "（改稿）" : ""}</span>
                      <span className="h-px flex-1 bg-border" />
                    </div>
                    <div className="grid grid-cols-3 gap-2.5">
                      {state.drafts
                        .filter((d) => d.item === item.id && d.gen === gen)
                        .map((d) => (
                          <DraftCard key={d.id} draft={d} state={state} onPick={onPick} onFinalize={onFinalize} onPreview={onPreview} onDelete={onDelete} />
                        ))}
                    </div>
                  </div>
                ))}
              </section>
            );
          })}
          {pendingCount > 0 ? (
            <div className="rounded-xl border border-dashed p-3 text-center text-xs text-muted-foreground">
              还有 {pendingCount} 件设计物排队中——当前件定稿后自动接着做
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

/* ================= 变体 B：聚焦主舞台＋缩略带 ================= */

function VariantB({ state, onPick, onFinalize }: VariantProps) {
  const itemsWithDrafts = state.items.filter((i) => state.drafts.some((d) => d.item === i.id));
  const [focus, setFocus] = React.useState<string | null>(null);
  const item =
    itemsWithDrafts.find((i) => i.id === focus) ??
    itemsWithDrafts.find((i) => i.status !== "done") ??
    itemsWithDrafts[itemsWithDrafts.length - 1];
  const itemDrafts = item ? state.drafts.filter((d) => d.item === item.id) : [];
  const heroDraft =
    itemDrafts.find((d) => d.id === state.picked) ??
    [...itemDrafts].reverse().find((d) => d.state === "arrived");
  const live = heroDraft ? isLive(heroDraft.id) : false;
  const done = item?.status === "done";
  return (
    <div className="flex min-h-0 flex-1">
      <div className="w-44 shrink-0 space-y-1 border-r bg-muted/20 p-2">
        <div className="px-2 pb-1 text-xs font-semibold text-muted-foreground">设计物</div>
        {itemsWithDrafts.map((i) => (
          <button
            key={i.id}
            onClick={() => setFocus(i.id)}
            className={cn(
              "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-[13px] transition-colors",
              item?.id === i.id ? "bg-background font-medium shadow-sm" : "text-muted-foreground hover:bg-background/60",
            )}
          >
            <span className={cn("size-1.5 shrink-0 rounded-full", i.status === "done" ? "bg-green-600" : "bg-primary")} />
            <span className="min-w-0 flex-1 truncate">{i.title}</span>
            {i.status === "done" ? <BadgeCheck className="size-3.5 shrink-0 text-green-600" /> : null}
          </button>
        ))}
        {state.items.filter((i) => !state.drafts.some((d) => d.item === i.id)).map((i) => (
          <div key={i.id} className="flex items-center gap-2 rounded-md px-2 py-1.5 text-[13px] text-muted-foreground/50">
            <span className="size-1.5 shrink-0 rounded-full border border-current" />
            <span className="min-w-0 flex-1 truncate">{i.title}</span>
            <span className="text-[10px]">排队</span>
          </div>
        ))}
      </div>
      <div className="flex min-w-0 flex-1 flex-col">
        {/* 主舞台：当前稿大图/可交互 */}
        <div className="relative min-h-0 flex-1 overflow-hidden bg-muted/30">
          {heroDraft && heroDraft.state === "arrived" ? (
            <div className="h-full p-4">
              <div className="mx-auto h-full max-w-2xl overflow-hidden rounded-xl border shadow-sm">
                <DraftMedia draftId={heroDraft.id} stage={heroDraft.stage} interactive={live} className="h-full" />
              </div>
            </div>
          ) : heroDraft ? (
            <DraftPlaceholder live={heroDraft ? isLive(heroDraft.id) : false} stage={heroDraft.stage} />
          ) : (
            <EmptyDesignArea />
          )}
          {heroDraft && state.picked === heroDraft.id ? (
            <span className="absolute left-4 top-4 flex items-center gap-1 rounded-full bg-primary px-2.5 py-1 text-[11px] font-semibold text-primary-foreground shadow">
              <Check className="size-3" strokeWidth={3} /> 已选中 · {draftName(heroDraft.id)}
            </span>
          ) : heroDraft ? (
            <span className="absolute left-4 top-4 rounded-full border bg-background/85 px-2.5 py-1 text-[11px] text-muted-foreground backdrop-blur">
              正在看 · {draftName(heroDraft.id)}（点击缩略选中）
            </span>
          ) : null}
        </div>
        {/* 动作条 */}
        {item && !done && state.picked ? (
          <div className="flex shrink-0 items-center gap-2 border-t bg-muted/20 px-4 py-2">
            <span className="text-xs text-muted-foreground">已选中「{draftName(state.picked)}」</span>
            <span className="flex-1" />
            <FinalizeAction draftId={state.picked} scenario={state.scenario} onFinalize={onFinalize} />
          </div>
        ) : item && done ? (
          <div className="flex shrink-0 items-center gap-2 border-t bg-green-600/5 px-4 py-2 text-xs text-green-700">
            <BadgeCheck className="size-4" /> 本件已定稿「{draftName(item.finalized!)}」
          </div>
        ) : null}
        {/* 缩略带：分代横向 */}
        <div className="shrink-0 border-t px-3 py-2.5">
          <div className="flex items-center gap-2 overflow-x-auto pb-1">
            {itemDrafts.map((d) => {
              const firstOfGen = itemDrafts.find((x) => x.gen === d.gen);
              return (
                <React.Fragment key={d.id}>
                  {firstOfGen?.id === d.id ? (
                    <span className="mr-0.5 shrink-0 rounded-full border px-1.5 py-0.5 text-[10px] text-muted-foreground">
                      第 {d.gen} 代
                    </span>
                  ) : null}
                  <button
                    onClick={() => d.state === "arrived" && onPick(d.id)}
                    className={cn(
                      "relative h-14 w-20 shrink-0 overflow-hidden rounded-md border transition-all",
                      state.picked === d.id ? "border-primary ring-2 ring-primary/25" : "hover:border-foreground/30",
                      item.finalized === d.id && "border-green-600 ring-2 ring-green-600/20",
                    )}
                    title={draftName(d.id)}
                  >
                    {d.state === "arrived" ? (
                      <DraftMedia draftId={d.id} stage={d.stage} />
                    ) : (
                      <DraftPlaceholder live={isLive(d.id)} stage={d.stage} />
                    )}
                  </button>
                </React.Fragment>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ================= 变体 C：全系统画布（正选——多屏共置＋拖排＋滚轮缩放＋删除） ================= */

const CARD_W = 300;
const CARD_H = Math.round(CARD_W * 0.625) + 36; /* 帧区 16:10 + 名条 */
const ZOOM_MIN = 0.3;
const ZOOM_MAX = 1.6;

function VariantC({ state, onPick, onFinalize, onPreview, onDelete }: VariantProps) {
  const [zoom, setZoom] = React.useState(0.55);
  const zoomRef = React.useRef(0.55);
  const scrollRef = React.useRef<HTMLDivElement>(null);
  const pan = React.useRef<{ x: number; y: number; sl: number; st: number } | null>(null);
  const [dragged, setDragged] = React.useState<Record<string, { x: number; y: number }>>({});
  const cardDrag = React.useRef<{ id: string; sx: number; sy: number; ox: number; oy: number; moved: boolean; pickable: boolean } | null>(null);

  /* 缩放：指向光标（滚轮）——板坐标系不动、层 transform 缩放，滚动量按比例补偿 */
  const zoomAt = (next: number, cx: number, cy: number) => {
    const el = scrollRef.current;
    if (!el) return;
    const z = Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.round(next * 100) / 100));
    const old = zoomRef.current;
    if (z === old) return;
    const bx = (el.scrollLeft + cx) / old;
    const by = (el.scrollTop + cy) / old;
    zoomRef.current = z;
    setZoom(z);
    el.scrollLeft = bx * z - cx;
    el.scrollTop = by * z - cy;
  };
  React.useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const r = el.getBoundingClientRect();
      zoomAt(zoomRef.current * (e.deltaY < 0 ? 1.12 : 1 / 1.12), e.clientX - r.left, e.clientY - r.top);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, []);

  /* 自动布局：每设计物一条横带（设计物标签），带内代左→右（gen 标签），卡横排（板坐标） */
  const layout = React.useMemo(() => {
    const pos: Record<string, { x: number; y: number }> = {};
    const labels: { x: number; y: number; text: string; strong?: boolean }[] = [];
    let y = 20;
    let maxX = 0;
    for (const item of state.items) {
      const ds = state.drafts.filter((d) => d.item === item.id);
      if (!ds.length) continue;
      labels.push({ x: 20, y, text: `${item.title}${item.status === "done" ? " ✓" : ""}`, strong: true });
      const gens = [...new Set(ds.map((d) => d.gen))].sort();
      let x = 20;
      for (const g of gens) {
        labels.push({ x, y: y + 28, text: `第 ${g} 代${g > 1 ? "（改稿）" : ""}` });
        ds.filter((d) => d.gen === g).forEach((d, i) => {
          pos[d.id] = { x: x + i * (CARD_W + 14), y: y + 52 };
        });
        x += ds.filter((d) => d.gen === g).length * (CARD_W + 14) + 56;
      }
      maxX = Math.max(maxX, x);
      y += 52 + CARD_H + 64;
    }
    return { pos, labels, w: maxX + CARD_W + 40, h: y };
  }, [state.drafts, state.items]);
  const positions = { ...layout.pos, ...dragged };

  /* 新代落板 → 滚到最右 */
  const seenCount = React.useRef(0);
  React.useEffect(() => {
    if (state.drafts.length !== seenCount.current && scrollRef.current) {
      seenCount.current = state.drafts.length;
      scrollRef.current.scrollTo({ left: scrollRef.current.scrollWidth, behavior: "smooth" });
    }
  }, [state.drafts.length]);

  const onPanDown = (e: React.PointerEvent) => {
    if ((e.target as HTMLElement).closest("[data-card]")) return;
    pan.current = { x: e.clientX, y: e.clientY, sl: scrollRef.current?.scrollLeft ?? 0, st: scrollRef.current?.scrollTop ?? 0 };
  };
  const onPanMove = (e: React.PointerEvent) => {
    if (!pan.current || !scrollRef.current) return;
    scrollRef.current.scrollLeft = pan.current.sl - (e.clientX - pan.current.x);
    scrollRef.current.scrollTop = pan.current.st - (e.clientY - pan.current.y);
  };

  /* 卡拖排（拖拽库标准法）：按下即接管指针——快甩也不丢事件；点选在抬起时
   * 自行合成（没拖动＋按在画面区＝点选），不依赖浏览器 click（接管后 click
   * 落在包装层上不会到按钮）；按钮区 data-no-drag 不接管、原生点击照常。 */
  const startCardDrag = (e: React.PointerEvent, d: Draft, p: { x: number; y: number }) => {
    if (d.state !== "arrived") return;
    const target = e.target as HTMLElement;
    if (target.closest("[data-no-drag]")) return;
    cardDrag.current = {
      id: d.id,
      sx: e.clientX,
      sy: e.clientY,
      ox: p.x,
      oy: p.y,
      moved: false,
      pickable: !!target.closest("[data-pick]"),
    };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  };
  const moveCardDrag = (e: React.PointerEvent, d: Draft) => {
    const dc = cardDrag.current;
    if (!dc || dc.id !== d.id) return;
    const dx = (e.clientX - dc.sx) / zoomRef.current;
    const dy = (e.clientY - dc.sy) / zoomRef.current;
    if (!dc.moved && Math.hypot(dx, dy) < 4) return;
    dc.moved = true;
    setDragged((m) => ({ ...m, [d.id]: { x: Math.max(0, dc.ox + dx), y: Math.max(0, dc.oy + dy) } }));
  };
  const endCardDrag = (d: Draft, cancelled = false) => {
    const dc = cardDrag.current;
    if (dc && dc.id === d.id && !cancelled && !dc.moved && dc.pickable) {
      onPick(d.id);
    }
    cardDrag.current = null;
  };

  const hasDrafts = state.drafts.length > 0;
  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center gap-2 border-b px-4 py-2 text-[11px] text-muted-foreground">
        <span className="rounded-full border bg-background px-1.5 py-0.5 shadow-sm">一次设计的各页面都长在这块板上</span>
        <span>滚轮缩放 · 拖卡排列 · 拖空白平移 · 悬卡可删</span>
        <span className="ml-auto">{state.items.filter((i) => i.status === "done").length}/{state.items.length} 件已定稿</span>
      </div>
      <div
        ref={scrollRef}
        className={cn(
          "min-h-0 flex-1 cursor-grab overflow-auto active:cursor-grabbing",
          "bg-[radial-gradient(circle_at_1px_1px,var(--color-border)_1px,transparent_0)] [background-size:24px_24px]",
        )}
        onPointerDown={onPanDown}
        onPointerMove={onPanMove}
        onPointerUp={() => (pan.current = null)}
        onPointerLeave={() => (pan.current = null)}
      >
        {!hasDrafts ? (
          <EmptyDesignArea />
        ) : (
          <div style={{ width: layout.w * zoom, height: layout.h * zoom }}>
            <div className="relative" style={{ width: layout.w, height: layout.h, transform: `scale(${zoom})`, transformOrigin: "0 0" }}>
              {layout.labels.map((l, i) => (
                <span
                  key={`${l.x}-${l.y}-${i}`}
                  className={cn("absolute select-none text-[11px] text-muted-foreground", l.strong && "text-[13px] font-semibold text-foreground/80")}
                  style={{ left: l.x, top: l.y }}
                >
                  {l.text}
                </span>
              ))}
              {state.drafts.map((d) => {
                const p = positions[d.id] ?? { x: 20, y: 20 };
                return (
                  <div
                    key={d.id}
                    data-card
                    className={cn("absolute touch-none select-none", d.state === "arrived" && "cursor-grab active:cursor-grabbing")}
                    style={{ left: p.x, top: p.y, width: CARD_W }}
                    onPointerDown={(e) => startCardDrag(e, d, p)}
                    onPointerMove={(e) => moveCardDrag(e, d)}
                    onPointerUp={() => endCardDrag(d)}
                    onPointerCancel={() => endCardDrag(d, true)}
                  >
                    <DraftCard draft={d} state={state} onPick={onPick} onFinalize={onFinalize} onPreview={onPreview} onDelete={onDelete} />
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
      {/* 缩放＋回到最新 */}
      <div className="pointer-events-none absolute bottom-3 right-3 z-10 flex items-center gap-0.5 rounded-full border bg-background/95 px-1.5 py-1 shadow-lg backdrop-blur">
        <button
          className="pointer-events-auto rounded-full px-2 py-0.5 text-sm text-muted-foreground transition-colors hover:bg-muted"
          onClick={() => {
            const el = scrollRef.current;
            if (el) zoomAt(zoomRef.current - 0.15, el.clientWidth / 2, el.clientHeight / 2);
          }}
          aria-label="缩小"
        >
          −
        </button>
        <button
          className="pointer-events-auto w-11 text-center text-[11px] tabular-nums text-muted-foreground hover:bg-muted"
          onClick={() => zoomAt(0.55, (scrollRef.current?.clientWidth ?? 0) / 2, (scrollRef.current?.clientHeight ?? 0) / 2)}
          title="回到 55%"
        >
          {Math.round(zoom * 100)}%
        </button>
        <button
          className="pointer-events-auto rounded-full px-2 py-0.5 text-sm text-muted-foreground transition-colors hover:bg-muted"
          onClick={() => {
            const el = scrollRef.current;
            if (el) zoomAt(zoomRef.current + 0.15, el.clientWidth / 2, el.clientHeight / 2);
          }}
          aria-label="放大"
        >
          ＋
        </button>
        <span className="mx-0.5 h-4 w-px bg-border" />
        <button
          className="pointer-events-auto rounded-full px-2.5 py-1 text-[11px] text-primary transition-colors hover:bg-muted"
          onClick={() => scrollRef.current?.scrollTo({ left: scrollRef.current.scrollWidth, behavior: "smooth" })}
        >
          回到最新
        </button>
      </div>
    </div>
  );
}

/* ---------- 装配 ---------- */

type VariantProps = {
  state: DesignState;
  onPick: (id: string) => void;
  onFinalize: (id: string) => void;
  onPreview: (id: string) => void;
  onDelete?: (id: string) => void;
};

export function EmptyDesignArea() {
  return (
    <div className="flex h-full min-h-40 flex-col items-center justify-center gap-2 p-6 text-center">
      <span className="flex size-10 items-center justify-center rounded-xl bg-muted text-muted-foreground">
        <Layers className="size-5" />
      </span>
      <div className="text-sm font-medium">设计稿会在这里长出来</div>
      <div className="text-xs text-muted-foreground">出稿开始后一张张到——平面类整图到达，界面类看着它一点点写出来</div>
    </div>
  );
}

export function DesignTab({
  variant,
  state,
  onPick,
  onFinalize,
  onPreview,
  onDelete,
}: VariantProps & { variant: string }) {
  const Variant = variant === "B" ? VariantB : variant === "C" ? VariantC : VariantA;
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex h-10 shrink-0 items-center gap-2 border-b px-4">
        <span className="text-xs font-semibold">设计稿</span>
        <span className="text-xs text-muted-foreground">候选分代并存 · 点选即挑选 · 定稿即收口</span>
        <span className="flex-1" />
        <SpecStrip state={state} />
      </div>
      <Variant state={state} onPick={onPick} onFinalize={onFinalize} onPreview={onPreview} onDelete={onDelete} />
    </div>
  );
}
