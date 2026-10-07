"use client";

import * as React from "react";
import { BadgeCheck, Check, Layers, Maximize2, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { useDeleteDesignDraft } from "@/hooks/use-delete-design-draft";
import { useFinalizeDesignItem } from "@/hooks/use-finalize-design-item";
import { useProject } from "@/hooks/use-project";
import { useProjectFiles } from "@/hooks/use-project-files";
import { errorText } from "@/lib/api/api-error";
import {
  buildDesignCanvas,
  draftDisplayName,
  FRAME_H,
  FRAME_W,
  type CanvasDraft,
  type CanvasItem,
} from "@/lib/projects/design-canvas";
import { rawFileUrl } from "@/lib/projects/files";
import { useChatStore } from "@/lib/store/chat";
import { useDesignScopeStore } from "@/lib/store/design-scope";
import { useWorkMessageStore, DRAFT_WRITING_TOOLS, type WorkPart } from "@/lib/store/work-message";
import { cn } from "@/lib/utils";

import { DesignPreviewModal } from "./design-preview-modal";
import { activityOf, draftNoOf } from "./work-message";

/**
 * 全系统画布（#293 设计稿范式，原型正选蓝本＝proto/design-process 变体 C；#294
 * 渐进长出＋点哪改哪＋定稿＋下载）：一次设计的各设计物稿卡多屏共置、代际并置
 * （轮收口＝代、左→右）——界面类稿＝固定画幅帧 1280×800（稿是帧不是网站，iframe
 * live 直渲正身、取件走 raw 稿伺服通道），平面类＝大图（raw 直出）。拖动排列、
 * 滚轮缩放（0.3–1.6 指向光标）、空白平移、悬卡删除（定稿不可删——真删工作区
 * 文件，候选整理）。
 *
 * <p><b>渐进长出（#294，ADR-0025 用户拍板硬要求）</b>：live 设计会话的目标件长
 * 出占位卡（「正在出稿…」活性行语料），design/ 新落文件即提升为在途稿卡（
 * {@link buildDesignCanvas} 的在途提升——随写随显、多稿逐张到达不等齐；写完的
 * 动作收口经桥失效文件树驱动）；在途界面稿的帧随写动作收口数刷新（rev 戳取件
 * ——看着它一点点写出来）；平面类图一次到达（img 直出）。收口后收尾卡轮接管
 * 锚定、占位退场。</p>
 *
 * <p><b>点哪改哪（#294）</b>：点选任意稿卡＝选中该设计物为改稿作用域（抬起合成
 * ——拖排与点选同一手势分流），作用域 chip 长在发送框上方、随话直达该件设计会话
 * （可跨件回溯）；稿卡点开放大预览（界面类可交互、平面类大图——下载动作在预览
 * 面）。定稿＝稿卡显式动作（Popover 确认 → POST finalize：成版＋后续分岔触发），
 * 定稿卡不可删。</p>
 */

const CARD_W = 300;
/** 卡全高＝帧区 16:10（188）＋名条（36）。 */
const CARD_H = Math.round(CARD_W * 0.625) + 36;
const ZOOM_MIN = 0.3;
const ZOOM_MAX = 1.6;
const ZOOM_DEFAULT = 0.55;

/** 在途稿帧刷新戳：本场写动作完成数（每完成一次＝一次可取的新内容）。 */
function writingRevOf(parts: WorkPart[]): number {
  return parts.filter(
    (part) =>
      part.kind === "action" && DRAFT_WRITING_TOOLS.has(part.toolName) && part.state === "completed",
  ).length;
}

export function DesignCanvas({ projectId }: { projectId: string }) {
  const { data: detail } = useProject(projectId);
  const files = useProjectFiles(projectId);
  const messages = useChatStore((state) => state.chats[projectId]?.messages);
  const deleteDraft = useDeleteDesignDraft(projectId);
  const finalizeItem = useFinalizeDesignItem(projectId);
  const scope = useDesignScopeStore((state) => state.scopes[projectId]);
  const pickScope = useDesignScopeStore((state) => state.pick);
  // live 设计会话（#294 渐进长出）：designer 座未定格＝在途——目标件占位＋在途稿
  const work = useWorkMessageStore((state) => state.works[projectId]);

  // 稿事实轮（对话序的收尾卡稿清单——live 入流与回访水合同一源）
  const rounds = React.useMemo(
    () =>
      (messages ?? []).flatMap((message) =>
        message.kind === "closing" && message.closing.drafts
          ? [message.closing.drafts]
          : [],
      ),
    [messages],
  );
  const canvas = React.useMemo(
    () =>
      buildDesignCanvas(
        rounds,
        files.data,
        detail?.designItems,
        work?.seat === "designer" && !work.frozen
          ? { itemTitle: work.slice?.title ?? null }
          : null,
      ),
    [rounds, files.data, detail?.designItems, work],
  );
  const writingRev = React.useMemo(() => writingRevOf(work?.parts ?? []), [work]);
  // 板上呈现：有稿的件＋live 目标件（占位也是「长出来」的一部分——不空白等待）
  const items = React.useMemo(
    () => canvas.filter((item) => item.gens.length > 0 || item.live),
    [canvas],
  );
  const finalizedCount = canvas.filter((item) => item.status === "finalized").length;

  // 点开预览的稿（null＝关）
  const [previewing, setPreviewing] = React.useState<CanvasDraft | null>(null);

  // ---------- 板坐标系：缩放（滚轮指向光标）＋平移（空白拖）＋拖排 ----------

  const scrollRef = React.useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = React.useState(ZOOM_DEFAULT);
  const zoomRef = React.useRef(ZOOM_DEFAULT);
  const pan = React.useRef<{ x: number; y: number; sl: number; st: number } | null>(null);
  /** 拖排的卡位覆盖（板坐标；会话内态不落库——重排是画布整理不是设计事实）。 */
  const [dragged, setDragged] = React.useState<Record<string, { x: number; y: number }>>({});
  const cardDrag = React.useRef<{
    path: string;
    sx: number;
    sy: number;
    ox: number;
    oy: number;
    moved: boolean;
  } | null>(null);

  /** 缩放后待补偿的滚动位（指向光标锚定——内容尺寸随 zoom 重渲染后再应用，
   * 同步写会被旧尺寸的最大滚动钳制，滚到边缘时锚点漂移）。 */
  const pendingScroll = React.useRef<{ left: number; top: number } | null>(null);

  /** 缩放：指向光标——板坐标系不动、层 transform 缩放，滚动量按比例补偿。 */
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
    pendingScroll.current = { left: bx * z - cx, top: by * z - cy };
  };
  // 布局提交后应用滚动补偿（zoom 变化 → 内容宽高重渲染完成，钳制上限已就位）
  React.useLayoutEffect(() => {
    const el = scrollRef.current;
    const pending = pendingScroll.current;
    if (!el || !pending) return;
    pendingScroll.current = null;
    el.scrollLeft = pending.left;
    el.scrollTop = pending.top;
  });
  React.useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const rect = el.getBoundingClientRect();
      zoomAt(
        zoomRef.current * (event.deltaY < 0 ? 1.12 : 1 / 1.12),
        event.clientX - rect.left,
        event.clientY - rect.top,
      );
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, []);

  /** 自动布局：每设计物一条横带（件标签），带内代左→右（代标签），卡横排；
   * live 件的带尾加占位卡位（正在出稿——渐进长出的第一形态）。 */
  const layout = React.useMemo(() => {
    const positions: Record<string, { x: number; y: number }> = {};
    const labels: { x: number; y: number; node: React.ReactNode; key: string }[] = [];
    const placeholders: { item: string; x: number; y: number }[] = [];
    let y = 20;
    let maxX = 0;
    for (const item of items) {
      labels.push({ x: 20, y, node: <ItemLabel item={item} />, key: `item-${item.item}` });
      let x = 20;
      for (const gen of item.gens) {
        labels.push({
          x,
          y: y + 30,
          node: <GenLabel gen={gen.gen} />,
          key: `gen-${item.item}-${gen.gen}`,
        });
        gen.drafts.forEach((draft, index) => {
          positions[draft.path] = { x: x + index * (CARD_W + 14), y: y + 54 };
        });
        x += gen.drafts.length * (CARD_W + 14) + 56;
      }
      if (item.live) {
        placeholders.push({ item: item.item, x, y: y + 54 });
        x += CARD_W + 14;
      }
      maxX = Math.max(maxX, x);
      y += 54 + CARD_H + 64;
    }
    return {
      positions,
      labels,
      placeholders,
      w: maxX + CARD_W + 40,
      h: items.length > 0 ? y : 0,
    };
  }, [items]);
  const positions = { ...layout.positions, ...dragged };

  // 新稿落板 → 滚到最右（最新代在右）
  const seenCount = React.useRef(0);
  React.useEffect(() => {
    const count = items.reduce((sum, item) => sum + item.gens.length, 0);
    if (count !== seenCount.current && scrollRef.current) {
      seenCount.current = count;
      scrollRef.current.scrollTo({ left: scrollRef.current.scrollWidth, behavior: "smooth" });
    }
  }, [items]);

  const onPanDown = (event: React.PointerEvent) => {
    if ((event.target as HTMLElement).closest("[data-card]")) return;
    pan.current = {
      x: event.clientX,
      y: event.clientY,
      sl: scrollRef.current?.scrollLeft ?? 0,
      st: scrollRef.current?.scrollTop ?? 0,
    };
  };
  const onPanMove = (event: React.PointerEvent) => {
    if (!pan.current || !scrollRef.current) return;
    scrollRef.current.scrollLeft = pan.current.sl - (event.clientX - pan.current.x);
    scrollRef.current.scrollTop = pan.current.st - (event.clientY - pan.current.y);
  };

  /* 卡拖排（#278 拖拽标准法）：按下即接管指针——快甩也不丢事件；按钮区
   * data-no-drag 不接管、原生点击照常。点选作用域在抬起处合成（#294 点哪改哪）：
   * 未拖动的抬起＝点选——选中该稿卡所属设计物为改稿作用域（chip 随话发送）。 */
  const startCardDrag = (event: React.PointerEvent, draft: CanvasDraft) => {
    const target = event.target as HTMLElement;
    if (target.closest("[data-no-drag]")) return;
    const position = positions[draft.path];
    if (!position) return;
    cardDrag.current = {
      path: draft.path,
      sx: event.clientX,
      sy: event.clientY,
      ox: position.x,
      oy: position.y,
      moved: false,
    };
    (event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId);
  };
  const moveCardDrag = (event: React.PointerEvent, draft: CanvasDraft) => {
    const drag = cardDrag.current;
    if (!drag || drag.path !== draft.path) return;
    const dx = (event.clientX - drag.sx) / zoomRef.current;
    const dy = (event.clientY - drag.sy) / zoomRef.current;
    if (!drag.moved && Math.hypot(dx, dy) < 4) return;
    drag.moved = true;
    setDragged((map) => ({
      ...map,
      [draft.path]: { x: Math.max(0, drag.ox + dx), y: Math.max(0, drag.oy + dy) },
    }));
  };
  const endCardDrag = (draft: CanvasDraft, item: CanvasItem) => {
    const drag = cardDrag.current;
    cardDrag.current = null;
    // 点选合成：未拖动＝点选——该件为改稿作用域（可跨件回溯；无件序不路由）
    if (drag && drag.path === draft.path && !drag.moved && item.ord != null) {
      pickScope(projectId, { ord: item.ord, itemTitle: item.item });
    }
  };

  const onDelete = (draft: CanvasDraft) => {
    deleteDraft.mutate(draft.path, {
      onError: (error) => toast.error(errorText(error, "暂时删不掉这张稿，请稍后再试")),
    });
  };

  const onFinalize = (item: CanvasItem, draft: CanvasDraft) => {
    finalizeItem.mutate(
      { ord: item.ord!, path: draft.path },
      {
        onSuccess: () => toast.success(`「${item.item}」已定稿`),
        onError: (error) => toast.error(errorText(error, "暂时定不了稿，请稍后再试")),
      },
    );
  };

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center gap-2 border-b px-4 py-2 text-[11px] text-muted-foreground">
        <span className="rounded-full border bg-background px-1.5 py-0.5 shadow-sm">
          一次设计的各页面都长在这块板上
        </span>
        <span>滚轮缩放 · 点稿改哪件 · 拖卡排列 · 悬卡可删</span>
        {canvas.length > 0 ? (
          <span className="ml-auto">
            {finalizedCount}/{canvas.length} 件已定稿
          </span>
        ) : null}
      </div>
      <div
        ref={scrollRef}
        data-design-board
        className={cn(
          "min-h-0 flex-1 cursor-grab overflow-auto active:cursor-grabbing",
          "bg-[radial-gradient(circle_at_1px_1px,var(--color-border)_1px,transparent_0)] [background-size:24px_24px]",
        )}
        onPointerDown={onPanDown}
        onPointerMove={onPanMove}
        onPointerUp={() => (pan.current = null)}
        onPointerLeave={() => (pan.current = null)}
      >
        {items.length === 0 ? (
          <EmptyDesignBoard />
        ) : (
          <div style={{ width: layout.w * zoom, height: layout.h * zoom }}>
            <div
              className="relative"
              style={{ width: layout.w, height: layout.h, transform: `scale(${zoom})`, transformOrigin: "0 0" }}
            >
              {layout.labels.map((label) => (
                <span
                  key={label.key}
                  className="absolute select-none"
                  style={{ left: label.x, top: label.y }}
                >
                  {label.node}
                </span>
              ))}
              {items.flatMap((item) =>
                item.gens.flatMap((gen) =>
                  gen.drafts.map((draft) => {
                    const position = positions[draft.path] ?? { x: 20, y: 20 };
                    return (
                      <div
                        key={draft.path}
                        data-card={draft.path}
                        className="absolute cursor-grab touch-none select-none active:cursor-grabbing"
                        style={{ left: position.x, top: position.y, width: CARD_W }}
                        onPointerDown={(event) => startCardDrag(event, draft)}
                        onPointerMove={(event) => moveCardDrag(event, draft)}
                        onPointerUp={() => endCardDrag(draft, item)}
                        onPointerCancel={() => (cardDrag.current = null)}
                      >
                        <DraftCard
                          projectId={projectId}
                          draft={draft}
                          item={item}
                          picked={scope?.ord === item.ord}
                          finalized={item.finalizedPath === draft.path}
                          /** 在途稿的内容版本戳（本场写动作完成数——帧随写刷新）。 */
                          rev={draft.incoming ? writingRev : undefined}
                          deleting={deleteDraft.isPending}
                          finalizing={finalizeItem.isPending}
                          onDelete={() => onDelete(draft)}
                          onFinalize={() => onFinalize(item, draft)}
                          onPreview={() => setPreviewing(draft)}
                        />
                      </div>
                    );
                  }),
                ),
              )}
              {layout.placeholders.map((placeholder) => (
                <IncomingPlaceholder
                  key={`placeholder-${placeholder.item}`}
                  item={placeholder.item}
                  x={placeholder.x}
                  y={placeholder.y}
                  activity={liveActivityText(work?.parts ?? [])}
                />
              ))}
            </div>
          </div>
        )}
      </div>
      {/* 缩放控件＋回到最新 */}
      {items.length > 0 ? (
        <div className="pointer-events-none absolute bottom-3 right-3 z-10 flex items-center gap-0.5 rounded-full border bg-background/95 px-1.5 py-1 shadow-lg backdrop-blur">
          <button
            type="button"
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
            type="button"
            className="pointer-events-auto w-11 text-center text-[11px] tabular-nums text-muted-foreground hover:bg-muted"
            onClick={() =>
              zoomAt(
                ZOOM_DEFAULT,
                (scrollRef.current?.clientWidth ?? 0) / 2,
                (scrollRef.current?.clientHeight ?? 0) / 2,
              )
            }
            title={`回到 ${Math.round(ZOOM_DEFAULT * 100)}%`}
          >
            {Math.round(zoom * 100)}%
          </button>
          <button
            type="button"
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
            type="button"
            className="pointer-events-auto rounded-full px-2.5 py-1 text-[11px] text-primary transition-colors hover:bg-muted"
            onClick={() =>
              scrollRef.current?.scrollTo({ left: scrollRef.current.scrollWidth, behavior: "smooth" })
            }
          >
            回到最新
          </button>
        </div>
      ) : null}
      {/* 点开预览（放大＋下载）：渲染期挂载即无 SSR 面 */}
      {previewing ? (
        <DesignPreviewModal
          projectId={projectId}
          draft={previewing}
          name={draftDisplayName(previewing.path)}
          onClose={() => setPreviewing(null)}
        />
      ) : null}
    </div>
  );
}

/** 占位卡活性行语料（live 会话的当前动作——「正在出第 N 稿」优先，缺省「正在出稿」）。 */
function liveActivityText(parts: WorkPart[]): string {
  const activity = activityOf(parts);
  const no = draftNoOf(parts, activity);
  if (no) return `正在出第 ${no} 稿…`;
  if (activity.kind === "action") return `${activity.part.label}…`;
  return "正在出稿…";
}

/* ---------- 件与代标签 ---------- */

function ItemLabel({ item }: { item: CanvasItem }) {
  return (
    <span className="flex items-center gap-1.5 text-[13px] font-semibold text-foreground/80">
      {item.item}
      {item.status === "finalized" ? (
        <span className="flex items-center gap-0.5 text-[11px] font-medium text-green-700 dark:text-green-400">
          <BadgeCheck className="size-3.5" /> 已定稿
        </span>
      ) : null}
    </span>
  );
}

function GenLabel({ gen }: { gen: number }) {
  return (
    <span className="rounded-full border px-1.5 py-0.5 text-[11px] text-muted-foreground">
      第 {gen} 代{gen > 1 ? "（改稿）" : ""}
    </span>
  );
}

/* ---------- 占位卡（渐进长出第一形态：进行中） ---------- */

function IncomingPlaceholder({
  item,
  x,
  y,
  activity,
}: {
  item: string;
  x: number;
  y: number;
  activity: string;
}) {
  return (
    <div
      data-draft-placeholder={item}
      className="absolute flex flex-col overflow-hidden rounded-xl border border-dashed"
      style={{ left: x, top: y, width: CARD_W, height: CARD_H }}
    >
      <div className="relative flex flex-1 items-center justify-center bg-muted/20">
        <div className="absolute inset-0 animate-pulse bg-gradient-to-br from-muted/60 via-muted/30 to-muted/60" />
        <div className="relative flex flex-col items-center gap-2">
          <Layers className="size-5 text-muted-foreground/60" />
          <div className="text-[11px] text-muted-foreground/80" data-placeholder-activity>
            {activity}
          </div>
        </div>
      </div>
      <div className="px-2 py-1.5 text-xs text-muted-foreground/50">下一张稿正在路上</div>
    </div>
  );
}

/* ---------- 稿卡 ---------- */

function DraftCard({
  projectId,
  draft,
  item,
  picked,
  finalized,
  rev,
  deleting,
  finalizing,
  onDelete,
  onFinalize,
  onPreview,
}: {
  projectId: string;
  draft: CanvasDraft;
  /** 所属件（点选作用域与定稿动作的路由锚）。 */
  item: CanvasItem;
  /** 本件是否为选中的作用域（#294 点哪改哪——卡环＋已选中徽记）。 */
  picked: boolean;
  /** 本卡是否该件的定稿稿（finalizedPath 精确匹配——定稿徽记＋不可删）。 */
  finalized: boolean;
  /** 在途稿的内容版本戳（undefined＝已收口的稳定取件）。 */
  rev?: number;
  deleting: boolean;
  finalizing: boolean;
  onDelete: () => void;
  onFinalize: () => void;
  onPreview: () => void;
}) {
  const name = draftDisplayName(draft.path);
  const incoming = !!draft.incoming;
  return (
    <div
      data-draft-card={draft.path}
      className={cn(
        "group relative flex flex-col overflow-hidden rounded-xl border bg-card transition-colors",
        finalized ? "border-green-600/50" : "hover:border-foreground/25",
        picked && "border-primary ring-2 ring-primary/25",
      )}
    >
      <div
        className="relative block w-full overflow-hidden bg-muted/20"
        style={{ aspectRatio: "8 / 5" }}
      >
        {draft.media === "image" ? (
          // eslint-disable-next-line @next/next/no-img-element -- 平台文件服务直出的设计稿，非静态资源（Next Image 不适用）
          <img
            src={rawFileUrl(projectId, draft.path)}
            alt={name}
            className="h-full w-full object-cover"
            data-draft-media={draft.path}
          />
        ) : (
          <DraftFrame projectId={projectId} path={draft.path} title={name} rev={rev} />
        )}
        {picked ? (
          <span className="absolute left-1.5 top-1.5 flex items-center gap-1 rounded-full bg-primary px-2 py-0.5 text-[10px] font-semibold text-primary-foreground">
            <Check className="size-3" strokeWidth={3} /> 已选中
          </span>
        ) : null}
        {finalized ? (
          <span className="absolute left-1.5 top-1.5 flex items-center gap-1 rounded-full bg-green-600 px-2 py-0.5 text-[10px] font-semibold text-white">
            <BadgeCheck className="size-3" /> 定稿
          </span>
        ) : null}
        {incoming ? (
          <span className="absolute bottom-1.5 left-1.5 rounded-full bg-background/85 px-2 py-0.5 text-[10px] text-muted-foreground backdrop-blur">
            正在写…
          </span>
        ) : null}
        {!incoming ? (
          <span
            data-no-drag
            className="absolute right-1.5 top-1.5 flex size-6 items-center justify-center rounded-full bg-background/85 text-muted-foreground opacity-0 backdrop-blur transition-opacity group-hover:opacity-100"
            title={draft.media === "html" ? "点开看大图" : "看大图"}
          >
            <button
              type="button"
              className="flex size-full items-center justify-center"
              onClick={(event) => {
                event.stopPropagation();
                onPreview();
              }}
              aria-label={`预览${name}`}
              data-preview-open={draft.path}
            >
              <Maximize2 className="size-3.5" />
            </button>
          </span>
        ) : null}
      </div>
      <div className="flex items-center gap-1.5 px-2 py-1.5">
        <span className="min-w-0 flex-1 truncate text-xs text-foreground/80">{name}</span>
        {draft.media === "html" ? (
          <span className="shrink-0 font-mono text-[9px] text-muted-foreground/50">1280×800</span>
        ) : null}
        {!finalized && !incoming && item.ord != null ? (
          <span data-no-drag className="shrink-0">
            <FinalizeAction name={name} item={item} disabled={finalizing} onFinalize={onFinalize} />
          </span>
        ) : null}
        {!finalized && !incoming ? (
          <span data-no-drag className="shrink-0">
            <Popover>
              <PopoverTrigger
                onClick={(event) => event.stopPropagation()}
                onPointerDown={(event) => event.stopPropagation()}
                className="rounded p-1 text-muted-foreground/50 opacity-0 transition-colors group-hover:opacity-100 hover:text-destructive"
                aria-label={`删除${name}`}
              >
                <Trash2 className="size-3.5" />
              </PopoverTrigger>
              <PopoverContent className="w-56 p-3" onClick={(event) => event.stopPropagation()}>
                <div className="text-sm font-medium">删除「{name}」？</div>
                <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                  把不要的稿从画布清掉；已定稿与版本不受影响。
                </p>
                <div className="mt-2 flex justify-end gap-2">
                  <PopoverTrigger className="rounded-md px-2.5 py-1 text-xs text-muted-foreground hover:bg-muted">
                    取消
                  </PopoverTrigger>
                  <Button
                    variant="destructive"
                    size="sm"
                    className="h-7 text-xs"
                    disabled={deleting}
                    onClick={onDelete}
                  >
                    删除
                  </Button>
                </div>
              </PopoverContent>
            </Popover>
          </span>
        ) : null}
      </div>
    </div>
  );
}

/* ---------- 定稿动作（显式收口，Popover 确认） ---------- */

function FinalizeAction({
  name,
  item,
  disabled,
  onFinalize,
}: {
  name: string;
  item: CanvasItem;
  disabled: boolean;
  onFinalize: () => void;
}) {
  const [open, setOpen] = React.useState(false);
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        onClick={(event) => event.stopPropagation()}
        onPointerDown={(event) => event.stopPropagation()}
        className="flex items-center gap-1 rounded-md bg-primary px-2 py-0.5 text-[11px] font-medium text-primary-foreground transition-transform active:scale-95"
        aria-label={`定稿${name}`}
        data-finalize-open={item.ord}
      >
        <BadgeCheck className="size-3" /> 定稿
      </PopoverTrigger>
      <PopoverContent
        align="end"
        className="w-64 p-3"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="text-sm font-medium">定稿「{name}」？</div>
        <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
          定稿即收口：这张稿收进版本流，{item.status === "finalized" ? "覆盖之前的定稿选择" : "作为该设计物的定稿"}
          ，并触发后续流程。定稿前可以随便换着挑。
        </p>
        <div className="mt-2.5 flex justify-end gap-2">
          <Button
            variant="ghost"
            size="sm"
            className="h-7 text-xs"
            onClick={() => setOpen(false)}
          >
            再想想
          </Button>
          <Button size="sm" className="h-7 text-xs" disabled={disabled} onClick={onFinalize}>
            定稿这张
          </Button>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/* ---------- 界面类稿：固定画幅帧（iframe live 直渲） ---------- */

/** 容器实宽 → 等比缩放系数（帧内 1280×800 恒定，缩放进容器）。 */
function useFitScale(baseW: number) {
  const ref = React.useRef<HTMLDivElement>(null);
  const [scale, setScale] = React.useState(0.25);
  React.useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width;
      if (width) setScale(width / baseW);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, [baseW]);
  return { ref, scale };
}

/**
 * 界面类稿正身（ADR-0025 渐进呈现、ADR-0027 界面类 v1 零截图）：设计稿是
 * 「帧」不是网站——iframe 按 raw 稿伺服通道取件（同源直链，服务端 CSP 禁脚本
 * ——帧无行为面），内页恒 1280×800 桌面布局、缩放进卡（不随容器响应）。
 * 帧区 pointerEvents 关闭：卡上按压要落进卡层接管拖排（iframe 文档会吞指针，
 * 事件不冒泡回父层）——画布卡是呈现面，可交互预览归点开（#294）。在途稿
 * （rev 在场）随写动作收口换 src 重取件（属性变化即帧内导航——内容刷新不
 * remount 不闪白）；收口后的稿内容不再变，零 rev（稳定取件不刷新）。
 */
function DraftFrame({
  projectId,
  path,
  title,
  rev,
}: {
  projectId: string;
  path: string;
  title: string;
  /** 在途稿的内容版本戳（undefined＝已收口的稳定取件）。 */
  rev?: number;
}) {
  const { ref, scale } = useFitScale(FRAME_W);
  const src =
    rev !== undefined ? `${rawFileUrl(projectId, path)}&v=${rev}` : rawFileUrl(projectId, path);
  return (
    <div ref={ref} className="relative h-full w-full overflow-hidden bg-white">
      <iframe
        title={title}
        src={src}
        data-draft-frame={path}
        className="absolute left-0 top-0 origin-top-left border-0 bg-white"
        style={{ width: FRAME_W, height: FRAME_H, transform: `scale(${scale})`, pointerEvents: "none" }}
        sandbox=""
        tabIndex={-1}
      />
    </div>
  );
}

/* ---------- 空态 ---------- */

function EmptyDesignBoard() {
  return (
    <div className="flex h-full min-h-40 flex-col items-center justify-center gap-2 p-6 text-center">
      <span className="flex size-10 items-center justify-center rounded-xl bg-muted text-muted-foreground">
        <Layers className="size-5" />
      </span>
      <div className="text-sm font-medium">设计稿会在这里长出来</div>
      <div className="text-xs text-muted-foreground">
        出稿开始后一张张到——平面类整图到达，界面类看着它一点点写出来
      </div>
    </div>
  );
}
