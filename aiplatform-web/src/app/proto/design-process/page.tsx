"use client";

/**
 * ============================================================================
 * 原 型 —— 设计过程体验（#278）：项目页壳（一次性，走查完即归档，勿当生产代码）
 * ============================================================================
 * 验证：多稿呈现与挑选的动作感 / 渐进呈现观感（设计稿 tab 上一点点画出来）/
 * 定稿后动线（下载·喂系统）/ 对话区＋成果区分工 / 圈注×按稿对齐 / 附件物料回显。
 * 三变体（?variant=A|B|C，←/→ 切换）：A 分组阵列流（基线）/ B 聚焦主舞台＋缩略带 /
 * C 代际画廊。双场景：①毛巾设计图（设计即交付）②首屏设计稿→系统（设计服务系统）。
 * 用法：pnpm dev → http://localhost:3333/proto/design-process → 底条播放场景，
 * 停在「轮到你了」就走查者亲手操作。
 * ============================================================================
 */

import * as React from "react";
import {
  ChevronLeft,
  ChevronRight,
  FileText,
  Info,
  Monitor,
  Palette,
  PanelRightOpen,
  Play,
  Plus,
  ReceiptText,
  RotateCcw,
  X,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@/components/ui/resizable";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { cn } from "@/lib/utils";

import { ChatColumn } from "./chat-column";
import { DesignTab, PreviewModal } from "./design-tab";
import { OrderPane, DocPane } from "./order-pane";
import { SystemPane } from "./system-pane";
import { useDesignEngine } from "./use-design-engine";
import { coffeeHTML, svgUrl, TOWEL_DRAFTS } from "./media";

const VARIANTS = [
  { key: "A", label: "A — 分组阵列流（基线）" },
  { key: "B", label: "B — 聚焦主舞台＋缩略带" },
  { key: "C", label: "C — 轻画布（空间并置）" },
] as const;

const NOTES = [
  "验证点：多稿挑选的动作感；渐进呈现（占位→到达 / 界面类一点点写出来）；定稿后动线（下单→支付门→下载 / 定稿→自动按稿搭系统→圈注）；对话区解说、成果区长稿的分工；上传参考图的物料回显。",
  "界面类稿＝固定画幅 frame（1280×800 帧，stitch screen / replit design frame 同构）——设计稿是「帧」不是「网站」，不随窗口响应；与「系统」tab 的真预览（设备切换、活页面）正是设计/构建的分界呈现。",
  "变体裁决（用户拍板）：C 全系统画布为正选——一次设计的各页面共置一块板、代际左→右并置、卡片可拖动排列、滚轮缩放（0.3–1.6 指向光标，多轮改稿后缩小看全局/放大操作单卡）、悬卡可删（定稿那张不可删）；A/B 留作对照。「点哪改哪」全链生效：点画布上任意一张＝选中作用域，改稿对那张的设计物出新一代（代次自增、旧稿全保留），首屏定稿后仍可点它的稿回头改。",
  "选中＝编辑作用域＋发散幅度三档（微调/探索/大胆，stitch REFINE/EXPLORE/REIMAGINE 同构）：选中任意稿后发送框挂「就这版改」chip；不选直接说＝对最近的未收口件出新代。",
  "播放会停在「轮到你了」（琥珀提示条）：照提示亲手操作——点选稿卡、定稿、发改稿意见（试试选中一张后选「大胆」再发）、下单、支付、圈注。中途乱点也没关系，状态可重播。",
  "真件复用：直播卡（WorkMessage）、发送框（Composer）、预览工具条（PreviewToolbar）都是生产组件喂假数据——手感即真。设计收尾卡/报价卡为设计语义自绘。",
];

export default function DesignProcessProto() {
  const engine = useDesignEngine();
  const { state, commit } = engine;

  /* 变体：URL search param（避免 useSearchParams 的预渲染边界）＋键盘 ←/→ */
  const [variant, setVariant] = React.useState<string>("C"); /* C 已拍板正选；A/B 留对照 */
  React.useEffect(() => {
    const read = () => {
      const v = new URLSearchParams(window.location.search).get("variant");
      if (v && VARIANTS.some((x) => x.key === v)) setVariant(v);
    };
    read();
    window.addEventListener("popstate", read);
    return () => window.removeEventListener("popstate", read);
  }, []);
  const cycleVariant = (dir: 1 | -1) => {
    const i = VARIANTS.findIndex((v) => v.key === variant);
    const next = VARIANTS[(i + dir + VARIANTS.length) % VARIANTS.length].key;
    setVariant(next);
    const url = new URL(window.location.href);
    url.searchParams.set("variant", next);
    window.history.replaceState(null, "", url);
  };
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (el && (el.tagName === "INPUT" || el.tagName === "TEXTAREA" || el.isContentEditable)) return;
      if (e.key === "ArrowLeft") cycleVariant(-1);
      if (e.key === "ArrowRight") cycleVariant(1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  /* 点开预览 / 查看当时 */
  const [preview, setPreview] = React.useState<string | null>(null);
  const [viewVersion, setViewVersion] = React.useState<number | null>(null);
  const versionEntry = state.versions.find((v) => v.n === viewVersion);

  const projectName = state.scenario === "towel" ? "毛巾设计图 · 棉品工作室" : "巷角咖啡官网（系统＋设计）";

  const tabs = [
    { id: "system", label: "系统", icon: <Monitor className="size-3.5" />, on: true },
    { id: "docs", label: "文档", icon: <FileText className="size-3.5" />, on: true },
    state.designTabOn ? { id: "design", label: "设计稿", icon: <Palette className="size-3.5" />, on: true } : null,
    state.orderTabOn ? { id: "order", label: "订单", icon: <ReceiptText className="size-3.5" />, on: true, dot: true } : null,
  ].filter(Boolean) as { id: string; label: string; icon: React.ReactNode; on: boolean; dot?: boolean }[];
  const addable = [
    !state.designTabOn ? { id: "design", label: "设计稿", blurb: "候选稿分组阵列 · 挑选与定稿" } : null,
    !state.orderTabOn ? { id: "order", label: "订单", blurb: "下单、支付与资产下载" } : null,
  ].filter(Boolean) as { id: string; label: string; blurb: string }[];

  const activeTab = tabs.some((t) => t.id === state.activeTab) ? state.activeTab : tabs[0].id;

  return (
    <div className="flex h-svh">
      <ResizablePanelGroup orientation="horizontal" className="min-w-0 flex-1">
        {/* 对话主角列 */}
        <ResizablePanel defaultSize={state.outputsOpen ? 46 : 100} minSize={30} className="flex flex-col">
          <header className="flex h-12 shrink-0 items-center gap-2 border-b px-4">
            <span className="truncate text-sm font-medium">{projectName}</span>
            <div className="ml-auto flex items-center gap-2">
              {state.runActive ? <LivePill /> : null}
              {state.items.length > 0 &&
              state.items.every((i) => i.status === "done") &&
              state.order === "none" &&
              !state.runActive ? (
                <Button size="sm" className="h-7 text-xs transition-transform active:scale-95" onClick={() => engine.onOrderPlace()}>
                  确认下单
                </Button>
              ) : null}
              {!state.outputsOpen ? (
                <Button size="sm" variant="outline" className="h-7 text-xs transition-transform active:scale-95" onClick={() => commit({ t: "outputs-open" })}>
                  <PanelRightOpen className="size-3.5" /> 成果
                </Button>
              ) : null}
            </div>
          </header>
          <ChatColumn engine={engine} onViewVersion={setViewVersion} />
        </ResizablePanel>

        {/* 呼出式成果区：tab 条即标题条 */}
        {state.outputsOpen ? (
          <>
            <ResizableHandle withHandle className="w-2 border-0 bg-transparent" />
            <ResizablePanel defaultSize={54} minSize={34} className="proto-ws-in">
              <style>{`@keyframes protoWsIn { from { transform: translateX(24px); opacity: 0; } to { transform: none; opacity: 1; } } .proto-ws-in { animation: protoWsIn .22s ease-out; }`}</style>
              <div className="flex h-full min-h-0 flex-col border-l bg-background">
                <div className="flex h-11 shrink-0 items-center gap-0.5 overflow-x-auto border-b pl-2 pr-1">
                  {tabs.map((t) => (
                    <button
                      key={t.id}
                      onClick={() => commit({ t: "tab", id: t.id })}
                      className={cn(
                        "relative flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[13px] transition-colors",
                        activeTab === t.id ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/50",
                      )}
                    >
                      {t.icon} {t.label}
                      {t.dot ? <span className="absolute right-1 top-1 size-1.5 rounded-full bg-amber-500" title="有新进展" /> : null}
                    </button>
                  ))}
                  {addable.length > 0 ? (
                    <DropdownMenu>
                      <DropdownMenuTrigger className="flex shrink-0 items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted">
                        <Plus className="size-3.5" /> 新标签页
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="start" className="w-60">
                        {addable.map((p) => (
                          <DropdownMenuItem
                            key={p.id}
                            onClick={() => {
                              if (p.id === "design") commit({ t: "design-tab-on" });
                              else if (p.id === "order") commit({ t: "order-tab-on" });
                            }}
                          >
                            <span>
                              <span className="block text-[13px]">{p.label}</span>
                              <span className="block text-[11px] text-muted-foreground">{p.blurb}</span>
                            </span>
                          </DropdownMenuItem>
                        ))}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  ) : null}
                  <span className="flex-1" />
                  <span className="px-1 text-[10px] text-muted-foreground/50">{VARIANTS.find((v) => v.key === variant)?.key} 变体</span>
                </div>
                <div className="flex min-h-0 flex-1 flex-col">
                  {activeTab === "system" ? <SystemPane state={state} commit={commit} /> : null}
                  {activeTab === "docs" ? <DocPane state={state} /> : null}
                  {activeTab === "design" ? (
                    <DesignTab
                      variant={variant}
                      state={state}
                      onPick={(id) => commit({ t: "pick", id })}
                      onFinalize={engine.onFinalize}
                      onPreview={setPreview}
                      onDelete={(id) => commit({ t: "draft-delete", id })}
                    />
                  ) : null}
                  {activeTab === "order" ? <OrderPane engine={engine} /> : null}
                </div>
              </div>
            </ResizablePanel>
          </>
        ) : null}
      </ResizablePanelGroup>

      {/* 原型控制条：右上角可折叠（默认收起——不压系统预览底部工具条与画布控件） */}
      <ProtoBar
        engine={engine}
        variant={variant}
        cycleVariant={cycleVariant}
      />

      {/* 点开预览（界面类可交互 / 平面类大图；下载条挂支付门） */}
      {preview ? (
        <PreviewModal
          draftId={preview}
          stage={state.drafts.find((d) => d.id === preview)?.stage ?? 4}
          paid={state.order === "paid"}
          onClose={() => setPreview(null)}
        />
      ) : null}

      {/* 查看当时 */}
      {versionEntry ? (
        <div className="fixed inset-0 z-40 flex flex-col bg-black/50 backdrop-blur-sm" onClick={() => setViewVersion(null)}>
          <div
            className="mx-auto mt-10 flex max-h-[78vh] w-[min(920px,92vw)] flex-col overflow-hidden rounded-2xl border bg-background shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex h-11 shrink-0 items-center gap-2 border-b px-4">
              <Badge variant="outline">版本 {versionEntry.n}</Badge>
              <span className="text-sm font-medium">{versionEntry.label}（当时快照）</span>
              <span className="flex-1" />
              <Button variant="ghost" size="icon" className="size-8" onClick={() => setViewVersion(null)} aria-label="关闭">
                <X className="size-4" />
              </Button>
            </div>
            <div className="flex min-h-0 flex-1 items-center justify-center bg-muted/30 p-6">
              {versionEntry.media && TOWEL_DRAFTS[versionEntry.media] ? (
                // eslint-disable-next-line @next/next/no-img-element -- 原型本地 data url
                <img
                  src={svgUrl(TOWEL_DRAFTS[versionEntry.media])}
                  alt="当时定稿"
                  className="max-h-full rounded-lg shadow-lg"
                />
              ) : versionEntry.draftId ? (
                <iframe
                  title="当时快照"
                  srcDoc={coffeeHTML(versionEntry.draftId, 4)}
                  className="h-full w-full max-w-2xl rounded-xl border bg-white shadow"
                  sandbox="allow-same-origin"
                />
              ) : null}
            </div>
            <div className="shrink-0 border-t px-4 py-2 text-center text-xs text-muted-foreground">只逛不换——「当时」的样子</div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

/** 原型控制条：右上角徽章＋展开面板（收起态零遮挡——底部中央留给预览工具条）。 */
function ProtoBar({
  engine,
  variant,
  cycleVariant,
}: {
  engine: ReturnType<typeof useDesignEngine>;
  variant: string;
  cycleVariant: (dir: 1 | -1) => void;
}) {
  const [open, setOpen] = React.useState(false);
  return (
    <div className="fixed right-3 top-3 z-50 flex flex-col items-end gap-1.5">
      {open ? (
        <div className="flex flex-col items-end gap-1.5 rounded-2xl border bg-zinc-900 p-2 text-zinc-100 shadow-xl">
          <div className="flex items-center gap-1">
            <span className="rounded bg-amber-500/20 px-1.5 py-0.5 text-[10px] font-bold text-amber-400">原型</span>
            <Popover>
              <PopoverTrigger className="flex items-center gap-1 rounded-full px-2 py-1 text-xs hover:bg-zinc-700">
                设计说明 <Info className="size-3 text-zinc-400" />
              </PopoverTrigger>
              <PopoverContent className="w-105 text-[13px] leading-relaxed">
                <ul className="list-disc space-y-1.5 pl-4">
                  {NOTES.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              </PopoverContent>
            </Popover>
            <button className="rounded-full p-1 hover:bg-zinc-700" onClick={() => setOpen(false)} aria-label="收起原型条">
              <X className="size-3.5" />
            </button>
          </div>
          <div className="flex items-center gap-1">
            <button className="rounded-full p-1 hover:bg-zinc-700" onClick={() => cycleVariant(-1)} aria-label="上一变体">
              <ChevronLeft className="size-4" />
            </button>
            <span className="min-w-40 text-center text-xs">{VARIANTS.find((v) => v.key === variant)?.label}</span>
            <button className="rounded-full p-1 hover:bg-zinc-700" onClick={() => cycleVariant(1)} aria-label="下一变体">
              <ChevronRight className="size-4" />
            </button>
          </div>
          <div className="flex items-center gap-1">
            <select
              className="rounded bg-zinc-800 px-1.5 py-1 text-xs"
              value={engine.scenarioIdx}
              onChange={(e) => engine.selectScenario(Number(e.target.value))}
            >
              {["① 毛巾设计图（设计即交付）", "② 首屏设计稿 → 系统"].map((name, i) => (
                <option key={name} value={i}>{name}</option>
              ))}
            </select>
            <Button
              size="sm"
              className="h-7 px-2.5 text-xs"
              disabled={engine.playing}
              onClick={() => engine.play(engine.scenarioIdx)}
            >
              {engine.state.chat.length > 2 ? <RotateCcw className="size-3.5" /> : <Play className="size-3.5" />}
              {engine.state.chat.length > 2 ? "重播" : "播放"}
            </Button>
            <button
              onClick={() => engine.setSpeed(engine.speed === 1 ? 2 : engine.speed === 2 ? 4 : 1)}
              title="播放速度：只影响「播放/重播」的演示脚本快慢，不影响你亲手操作"
              className="rounded border border-zinc-700 px-2 py-1 text-[11px] text-zinc-300 transition-colors hover:bg-zinc-700"
            >
              播放速度 {engine.speed}×
            </button>
          </div>
        </div>
      ) : null}
      <button
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 rounded-full border bg-zinc-900 px-2.5 py-1 text-[11px] font-bold text-amber-400 shadow-lg"
      >
        <Play className="size-3" /> 原型
      </button>
    </div>
  );
}

/** 顶栏 LIVE 计时（纯摆件）。 */
function LivePill() {
  const [sec, setSec] = React.useState(0);
  React.useEffect(() => {
    const t = setInterval(() => setSec((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <span className="flex items-center gap-2 rounded-full border border-red-500/40 bg-red-500/10 px-2.5 py-1">
      <span className="relative flex size-2">
        <span className="absolute inline-flex size-full animate-ping rounded-full bg-red-500 opacity-60" />
        <span className="relative inline-flex size-2 rounded-full bg-red-500" />
      </span>
      <span className="text-xs font-semibold text-red-600">LIVE</span>
      <span className="font-mono text-xs tabular-nums text-red-600">
        {Math.floor(sec / 60)}:{String(sec % 60).padStart(2, "0")}
      </span>
    </span>
  );
}
