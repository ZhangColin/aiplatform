"use client";

/**
 * ============================================================================
 * 原 型 —— 设计过程体验（#278）：系统 tab（一次性，勿当生产代码）
 * ============================================================================
 * 浏览器条（地址框/设备切换/刷新/新窗口＝摆件）＋舞台：
 * 咖啡场景＝按定稿长大的分阶段页面（buildStage 同步构建 run）；
 * 毛巾场景＝空态（设计主线无系统，「转系统开发」出口叙事在收尾卡侧）。
 * 底部浮动工具条用真件 PreviewToolbar（选择/圈选）——圈选拖框→留话→
 * 意见落对话区（圈注只长系统预览面；对稿意见走设计会话改稿——#277 口径）。
 * ============================================================================
 */

import * as React from "react";
import { ExternalLink, Lock, Monitor, RefreshCw, Smartphone } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { cn } from "@/lib/utils";
import { PreviewToolbar } from "@/components/project-page/preview-toolbar";
import type { AnnotationKind } from "@/lib/preview/annotation";

import { coffeeHTML } from "./media";
import type { DesignState, Ev } from "./engine";

export function SystemPane({
  state,
  commit,
}: {
  state: DesignState;
  commit: (ev: Ev) => void;
}) {
  const [device, setDevice] = React.useState<"desktop" | "mobile">("desktop");
  const built = state.scenario === "coffee" && state.builtDraft && state.buildStage > 0;

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/* 浏览器条 */}
      <div className="flex h-10 shrink-0 items-center gap-2 border-b bg-muted/40 px-3">
        <button title="刷新（原型摆件）" className="rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground">
          <RefreshCw className="size-3.5" />
        </button>
        <div className="mx-auto flex w-full max-w-md items-center gap-1.5 rounded-full border bg-background px-3 py-1 text-xs text-muted-foreground">
          <Lock className="size-3" /> 巷角咖啡 · 预览
        </div>
        <ToggleGroup
          value={[device]}
          onValueChange={(v) => v.length && setDevice(v[0] as "desktop" | "mobile")}
          className="gap-0"
        >
          <ToggleGroupItem value="desktop" aria-label="桌面预览" className="h-7 px-2 data-pressed:bg-background data-pressed:shadow-sm">
            <Monitor className="size-3.5" />
          </ToggleGroupItem>
          <ToggleGroupItem value="mobile" aria-label="手机预览" className="h-7 px-2 data-pressed:bg-background data-pressed:shadow-sm">
            <Smartphone className="size-3.5" />
          </ToggleGroupItem>
        </ToggleGroup>
        <button title="在新窗口打开（原型摆件）" className="rounded p-1 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground">
          <ExternalLink className="size-3.5" />
        </button>
      </div>
      {/* 舞台 */}
      <Stage state={state} commit={commit} built={!!built} device={device} />
    </div>
  );
}

function Stage({
  state,
  commit,
  built,
  device,
}: {
  state: DesignState;
  commit: (ev: Ev) => void;
  built: boolean;
  device: "desktop" | "mobile";
}) {
  const areaRef = React.useRef<HTMLDivElement>(null);
  const [drag, setDrag] = React.useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null);
  const drawing = state.annotateMode === "circle" && drag !== null;

  const pct = (e: React.MouseEvent) => {
    const r = areaRef.current!.getBoundingClientRect();
    return {
      x: ((e.clientX - r.left) / r.width) * 100,
      y: ((e.clientY - r.top) / r.height) * 100,
    };
  };

  const onMouseDown = (e: React.MouseEvent) => {
    if (state.annotateMode !== "circle" || !built) return;
    const p = pct(e);
    setDrag({ x0: p.x, y0: p.y, x1: p.x, y1: p.y });
  };
  const onMouseMove = (e: React.MouseEvent) => {
    if (!drag) return;
    const p = pct(e);
    setDrag({ ...drag, x1: p.x, y1: p.y });
  };
  const onMouseUp = () => {
    if (!drag) return;
    const w = Math.abs(drag.x1 - drag.x0);
    const h = Math.abs(drag.y1 - drag.y0);
    if (w > 3 && h > 3) {
      commit({
        t: "anno-add",
        x: Math.min(drag.x0, drag.x1),
        y: Math.min(drag.y0, drag.y1),
        w,
        h,
      });
    }
    setDrag(null);
  };

  const body = built ? (
    <iframe
      key={`${state.builtDraft}-${state.buildStage}-${device}`}
      title="系统预览"
      srcDoc={coffeeHTML(state.builtDraft!, state.buildStage)}
      className="h-full w-full border-0 bg-white"
      sandbox="allow-same-origin"
    />
  ) : state.scenario === "coffee" ? (
    <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
      <span className="flex size-10 items-center justify-center rounded-xl bg-muted text-muted-foreground">
        <Monitor className="size-5" />
      </span>
      <div className="text-sm font-semibold">系统还没开始搭</div>
      <div className="max-w-xs text-xs leading-relaxed text-muted-foreground">
        这个项目先做设计——首屏设计稿定稿后，系统会自动按定稿那张搭起来，在这里一点点长出来。
      </div>
    </div>
  ) : (
    <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
      <span className="flex size-10 items-center justify-center rounded-xl bg-muted text-muted-foreground">
        <Monitor className="size-5" />
      </span>
      <div className="text-sm font-semibold">这个项目只做设计</div>
      <div className="max-w-xs text-xs leading-relaxed text-muted-foreground">
        没有在做系统。想要能用的系统？设计定稿后在对话里说一声「转系统开发」，就按定稿的图接着做。
      </div>
    </div>
  );

  return (
    <div
      className={cn(
        "relative min-h-0 flex-1 overflow-hidden bg-zinc-100",
        state.annotateMode === "circle" && built && "cursor-crosshair",
      )}
      onMouseDown={onMouseDown}
      onMouseMove={onMouseMove}
      onMouseUp={onMouseUp}
      onMouseLeave={onMouseUp}
    >
      <div
        ref={areaRef}
        className={cn(
          "h-full overflow-y-auto",
          device === "mobile" && "flex justify-center p-4",
        )}
      >
        <div className={cn("h-full", device === "mobile" && "w-[390px] overflow-hidden rounded-2xl border bg-white shadow-sm")}>
          {body}
        </div>
      </div>

      {/* 构建中角标 */}
      {built && state.buildStage < 4 ? (
        <div className="absolute left-3 top-3 rounded-full border bg-background/90 px-2.5 py-1 text-[11px] text-muted-foreground shadow-sm backdrop-blur">
          按定稿搭系统中…（{state.buildStage}/4）
        </div>
      ) : built ? (
        <div className="absolute left-3 top-3 flex items-center gap-1.5 rounded-full border border-green-600/30 bg-green-500/10 px-2.5 py-1 text-[11px] text-green-700 shadow-sm">
          按「定稿」搭好 · 可圈注对稿
        </div>
      ) : null}

      {/* 标注态事件陷阱层：圈选时盖住 iframe——鼠标事件留在舞台层（iframe 会吞事件） */}
      {state.annotateMode && built ? <div className="absolute inset-0 z-[5]" /> : null}

      {/* 圈注框们 */}
      {state.annotations.map((a, i) => (
        <div
          key={a.id}
          className="absolute"
          style={{ left: `${a.x}%`, top: `${a.y}%`, width: `${a.w}%`, height: `${a.h}%` }}
        >
          <div
            className={cn(
              "h-full w-full rounded-md border-2 border-dashed",
              a.sent ? "border-muted-foreground/40" : "border-primary animate-pulse",
            )}
          />
          <span
            className={cn(
              "absolute -left-2 -top-2 flex size-5 items-center justify-center rounded-full text-[10px] font-bold text-white shadow",
              a.sent ? "bg-muted-foreground" : "bg-primary",
            )}
          >
            {i + 1}
          </span>
          {!a.sent ? <AnnoInput id={a.id} commit={commit} /> : null}
          {a.sent && a.text ? (
            <span className="absolute -bottom-1 left-1 max-w-48 -translate-y-full rounded-lg border bg-background px-2 py-1 text-[11px] shadow-sm">
              {a.text}
            </span>
          ) : null}
        </div>
      ))}

      {/* 拖拽中的框 */}
      {drawing && drag ? (
        <div
          className="pointer-events-none absolute rounded-md border-2 border-dashed border-primary bg-primary/10"
          style={{
            left: `${Math.min(drag.x0, drag.x1)}%`,
            top: `${Math.min(drag.y0, drag.y1)}%`,
            width: `${Math.abs(drag.x1 - drag.x0)}%`,
            height: `${Math.abs(drag.y1 - drag.y0)}%`,
          }}
        />
      ) : null}

      {/* 真件工具条（选择/圈选）——只在系统搭好后启用 */}
      {built ? (
        <PreviewToolbar
          activeTool={state.annotateMode}
          onToolToggle={(tool: AnnotationKind) =>
            commit({ t: "annotate-mode", mode: state.annotateMode === tool ? null : tool })
          }
          onExit={() => commit({ t: "annotate-mode", mode: null })}
        />
      ) : null}
      {state.annotateMode === "select" ? (
        <div className="pointer-events-none absolute inset-x-0 bottom-16 z-10 flex justify-center">
          <Badge variant="outline" className="border-amber-500/40 bg-amber-500/10 text-amber-700">
            点选模式（原型演示）：点页面上的东西圈定为谈话对象
          </Badge>
        </div>
      ) : null}
    </div>
  );
}

/** 圈注留话小输入（回车即随意见发进对话区）。 */
function AnnoInput({ id, commit }: { id: string; commit: (ev: Ev) => void }) {
  const [v, setV] = React.useState("");
  return (
    <div
      className="absolute -bottom-1 left-1 w-56 -translate-y-full rounded-lg border bg-background p-1.5 shadow-lg"
      onMouseDown={(e) => e.stopPropagation()}
    >
      <input
        autoFocus
        value={v}
        onChange={(e) => setV(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && v.trim()) {
            commit({ t: "anno-text", id, text: v.trim() });
            commit({ t: "anno-send", id });
          }
        }}
        placeholder="这里跟定稿不一样……"
        className="w-full bg-transparent text-[13px] outline-none placeholder:text-muted-foreground/60"
      />
      <div className="px-0.5 pt-0.5 text-[10px] text-muted-foreground">回车发出——意见进对话区，按定稿改</div>
    </div>
  );
}
