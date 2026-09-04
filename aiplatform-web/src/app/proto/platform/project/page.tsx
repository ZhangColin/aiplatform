"use client";

/**
 * ============================================================================
 * 原 型 —— 平台整体 UI（#72）：项目页，三个设计方向，?variant= 切换
 * ============================================================================
 * 壳各不相同（对话与工作区的关系不同）；范式内容与过程呈现共享。
 * ============================================================================
 */

import * as React from "react";
import { useSearchParams } from "next/navigation";
import {
  ChevronLeft,
  ChevronsLeft,
  CircleUser,
  FolderOpen,
  Home,
  MessageSquareText,
  MessagesSquare,
  PanelRightOpen,
  Plus,
  X,
} from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

import { Brand, ChatMessages, Composer, LivePill } from "../../_shared/chat-parts";
import { PARADIGMS, type Paradigm } from "../../_shared/paradigms";
import type { Ev, RunState } from "../../_shared/run-engine";
import { ProtoSwitcher, VARIANTS } from "../../_shared/switcher";
import { useRunEngine, type RunEngine } from "../../_shared/use-run-engine";

export default function PlatformProject() {
  return (
    <React.Suspense>
      <PlatformProjectInner />
    </React.Suspense>
  );
}

function PlatformProjectInner() {
  const variant = useSearchParams().get("variant") ?? VARIANTS[0].key;
  const engine = useRunEngine("preview");
  return (
    <>
      {variant === "chat-first" ? (
        <ShellB engine={engine} />
      ) : variant === "nav-rail" ? (
        <ShellC engine={engine} />
      ) : (
        <ShellA engine={engine} />
      )}
      <ProtoSwitcher current={variant} engine={engine} />
    </>
  );
}

/* ================= 共享：工作区 tab 簇（「+」挂载新面 = 扩展故事） ================= */

function WorkspaceTabs({
  state,
  onDispatch,
  openTabs,
  setOpenTabs,
  active,
  setActive,
}: {
  state: RunState;
  onDispatch: (ev: Ev) => void;
  openTabs: string[];
  setOpenTabs: (tabs: string[]) => void;
  active: string;
  setActive: (id: string) => void;
}) {
  const addable = PARADIGMS.filter((p) => !openTabs.includes(p.id));
  const activeParadigm = PARADIGMS.find((p: Paradigm) => p.id === active);
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex shrink-0 items-center gap-0.5 border-b px-2 pt-1.5">
        {openTabs.map((id) => {
          const p = PARADIGMS.find((p: Paradigm) => p.id === id)!;
          return (
            <span
              key={id}
              className={cn(
                "group flex items-center gap-1.5 rounded-t-md border-b-2 px-3 py-1.5 text-[13px]",
                active === id
                  ? "border-primary bg-muted/50 font-medium"
                  : "border-transparent text-muted-foreground hover:bg-muted/40",
              )}
            >
              <button className="flex items-center gap-1.5" onClick={() => setActive(id)}>
                {p.icon} {p.label}
              </button>
              {openTabs.length > 1 ? (
                <button
                  className="rounded p-0.5 opacity-0 transition-opacity group-hover:opacity-100 hover:bg-muted"
                  onClick={() => {
                    const next = openTabs.filter((t) => t !== id);
                    setOpenTabs(next);
                    if (active === id) setActive(next[0]);
                  }}
                  aria-label={`关闭${p.label}`}
                >
                  <X className="size-3" />
                </button>
              ) : null}
            </span>
          );
        })}
        <DropdownMenu>
          <DropdownMenuTrigger className="ml-1 flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-muted">
            <Plus className="size-3.5" /> 新标签页
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-64">
            {addable.length === 0 ? (
              <div className="px-2 py-1.5 text-xs text-muted-foreground">能挂的都挂上了</div>
            ) : (
              addable.map((p: Paradigm) => (
                <DropdownMenuItem
                  key={p.id}
                  onSelect={() => {
                    setOpenTabs([...openTabs, p.id]);
                    setActive(p.id);
                  }}
                >
                  <span className="flex items-start gap-2">
                    <span className="mt-0.5">{p.icon}</span>
                    <span>
                      <span className="block text-[13px]">{p.label}</span>
                      <span className="block text-[11px] text-muted-foreground">{p.blurb}</span>
                    </span>
                  </span>
                </DropdownMenuItem>
              ))
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        {activeParadigm?.render({ state, onDispatch })}
      </div>
    </div>
  );
}

/** 「查看当时」被点 → 确保预览面打开并激活。 */
function useJumpToPreviewOnViewing(state: RunState, ensure: () => void) {
  React.useEffect(() => {
    if (state.viewing !== null) ensure();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state.viewing]);
}

/* ================= A · 工作台双栏 ================= */

function ShellA({ engine }: { engine: RunEngine }) {
  const { state } = engine;
  const [chatOpen, setChatOpen] = React.useState(true);
  const [openTabs, setOpenTabs] = React.useState<string[]>(
    PARADIGMS.filter((p: Paradigm) => p.defaultOn).map((p: Paradigm) => p.id),
  );
  const [active, setActive] = React.useState("preview");
  useJumpToPreviewOnViewing(state, () => {
    setOpenTabs((tabs) => (tabs.includes("preview") ? tabs : ["preview", ...tabs]));
    setActive("preview");
  });

  return (
    <div className="flex h-svh flex-col">
      <header className="flex h-12 shrink-0 items-center gap-2 border-b px-3">
        <Brand />
        <span className="text-muted-foreground/40">/</span>
        <span className="text-xs text-muted-foreground">巷口花店小程序</span>
        <div className="ml-auto flex items-center gap-2">
          {state.runActive ? <LivePill /> : null}
          <CircleUser className="size-5 text-muted-foreground" />
        </div>
      </header>
      <div className="flex min-h-0 flex-1">
        {chatOpen ? (
          <div className="flex w-[400px] shrink-0 flex-col border-r">
            <div className="flex h-9 shrink-0 items-center border-b px-3">
              <span className="text-xs font-semibold text-muted-foreground">对话</span>
              <button
                className="ml-auto rounded p-1 text-muted-foreground hover:bg-muted"
                onClick={() => setChatOpen(false)}
                aria-label="收起对话"
              >
                <ChevronsLeft className="size-4" />
              </button>
            </div>
            <ChatMessages state={state} onAnswer={engine.onAnswer} onDispatch={engine.commit} onRetry={engine.onRetry} />
            <div className="shrink-0 border-t p-3"><Composer /></div>
          </div>
        ) : (
          <button
            className="flex w-9 shrink-0 flex-col items-center gap-2 border-r py-3 text-muted-foreground hover:bg-muted/40"
            onClick={() => setChatOpen(true)}
            aria-label="展开对话"
          >
            <MessagesSquare className="size-4" />
            <span className="text-[11px] [writing-mode:vertical-rl]">对话</span>
            {state.runActive ? <span className="size-1.5 rounded-full bg-red-500" /> : null}
          </button>
        )}
        <WorkspaceTabs
          state={state}
          onDispatch={engine.commit}
          openTabs={openTabs}
          setOpenTabs={setOpenTabs}
          active={active}
          setActive={setActive}
        />
      </div>
    </div>
  );
}

/* ================= B · 对话主角（工作区呼出式） ================= */

function ShellB({ engine }: { engine: RunEngine }) {
  const { state } = engine;
  const [wsOpen, setWsOpen] = React.useState(false);
  const [openTabs, setOpenTabs] = React.useState<string[]>(
    PARADIGMS.filter((p: Paradigm) => p.defaultOn).map((p: Paradigm) => p.id),
  );
  const [active, setActive] = React.useState("preview");
  /* 有成果长出来时工作区自动滑出一次 */
  const autoOpened = React.useRef(false);
  React.useEffect(() => {
    if (!autoOpened.current && state.previewStage > 0) {
      autoOpened.current = true;
      setWsOpen(true);
    }
    if (state.previewStage === 0) autoOpened.current = false;
  }, [state.previewStage]);
  useJumpToPreviewOnViewing(state, () => {
    setWsOpen(true);
    setOpenTabs((tabs) => (tabs.includes("preview") ? tabs : ["preview", ...tabs]));
    setActive("preview");
  });

  return (
    <div className="flex h-svh">
      {/*  slim 侧栏：跨项目导航  */}
      <aside className="flex w-14 shrink-0 flex-col items-center gap-1 border-r bg-muted/30 py-3">
        <span className="flex size-7 items-center justify-center rounded-md bg-primary text-xs font-bold text-primary-foreground">AI</span>
        <button className="mt-2 rounded-lg p-2 text-muted-foreground hover:bg-muted" aria-label="新建项目"><Plus className="size-4" /></button>
        <button className="rounded-lg p-2 text-muted-foreground hover:bg-muted" aria-label="首页"><Home className="size-4" /></button>
        <button className="rounded-lg p-2 text-muted-foreground hover:bg-muted" aria-label="我的项目"><FolderOpen className="size-4" /></button>
        <button className="mt-auto rounded-lg p-2 text-muted-foreground hover:bg-muted" aria-label="我的账号"><CircleUser className="size-4" /></button>
      </aside>
      {/* 对话主角列 */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-2 border-b px-4">
          <span className="truncate text-sm font-medium">巷口花店小程序</span>
          <div className="ml-auto flex items-center gap-2">
            {state.runActive ? <LivePill /> : null}
            {!wsOpen ? (
              <Button size="sm" variant="outline" className="h-7 text-xs" onClick={() => setWsOpen(true)}>
                <PanelRightOpen className="size-3.5" /> 工作区
              </Button>
            ) : null}
          </div>
        </header>
        <div className="mx-auto flex w-full max-w-2xl min-h-0 flex-1 flex-col">
          <ChatMessages state={state} onAnswer={engine.onAnswer} onDispatch={engine.commit} onRetry={engine.onRetry} />
          <div className="shrink-0 p-4 pt-2"><Composer /></div>
        </div>
      </div>
      {/* 呼出式工作区 */}
      {wsOpen ? (
        <div className="flex w-[52%] shrink-0 flex-col border-l">
          <div className="flex h-9 shrink-0 items-center border-b px-3">
            <span className="text-xs font-semibold text-muted-foreground">工作区</span>
            <button className="ml-auto rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => setWsOpen(false)} aria-label="收起工作区">
              <X className="size-4" />
            </button>
          </div>
          <WorkspaceTabs
            state={state}
            onDispatch={engine.commit}
            openTabs={openTabs}
            setOpenTabs={setOpenTabs}
            active={active}
            setActive={setActive}
          />
        </div>
      ) : null}
    </div>
  );
}

/* ================= C · 导航栏多页（每个面一整页 + 对话浮窗） ================= */

function ShellC({ engine }: { engine: RunEngine }) {
  const { state } = engine;
  const [page, setPage] = React.useState("chat"); // 'chat' | paradigm id
  const [dockOpen, setDockOpen] = React.useState(false);
  useJumpToPreviewOnViewing(state, () => setPage("preview"));

  const chatPage = page === "chat";
  const paradigm = PARADIGMS.find((p: Paradigm) => p.id === page);

  return (
    <div className="flex h-svh">
      {/* 左导航栏：所有注册面都是导航项（扩展 = 导航项） */}
      <aside className="flex w-52 shrink-0 flex-col border-r p-2.5">
        <div className="px-1.5 py-1"><Brand /></div>
        <button className="mt-2 flex w-full items-center gap-2 rounded-md border px-2.5 py-2 text-left text-sm hover:bg-muted/60">
          <span>🌷</span>
          <span className="min-w-0 flex-1 truncate font-medium">巷口花店小程序</span>
          <ChevronLeft className="size-3.5 -rotate-90 text-muted-foreground" />
        </button>
        <nav className="mt-3 space-y-0.5 overflow-y-auto">
          <button
            onClick={() => setPage("chat")}
            className={cn("flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-sm", chatPage ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60")}
          >
            <MessageSquareText className="size-4" /> 对话
            {state.runActive ? <span className="ml-auto size-1.5 rounded-full bg-red-500" /> : null}
          </button>
          {PARADIGMS.map((p: Paradigm) => (
            <button
              key={p.id}
              onClick={() => setPage(p.id)}
              className={cn("flex w-full items-center gap-2.5 rounded-md px-2.5 py-2 text-sm", page === p.id ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/60")}
            >
              <span className="[&_svg]:size-4">{p.icon}</span> {p.label}
            </button>
          ))}
        </nav>
        <div className="mt-auto flex items-center gap-2 rounded-md px-2.5 py-2 text-sm text-muted-foreground">
          <CircleUser className="size-4" /> 我的账号
        </div>
      </aside>
      {/* 内容页 */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-12 shrink-0 items-center gap-2 border-b px-4">
          <span className="text-sm font-medium">{chatPage ? "对话" : paradigm?.label}</span>
          <span className="text-xs text-muted-foreground">{paradigm?.blurb}</span>
          <div className="ml-auto">{state.runActive ? <LivePill /> : null}</div>
        </header>
        {chatPage ? (
          <div className="mx-auto flex w-full max-w-2xl min-h-0 flex-1 flex-col">
            <ChatMessages state={state} onAnswer={engine.onAnswer} onDispatch={engine.commit} onRetry={engine.onRetry} />
            <div className="shrink-0 p-4 pt-2"><Composer /></div>
          </div>
        ) : (
          <div className="flex min-h-0 flex-1 flex-col">
            {paradigm?.render({ state, onDispatch: engine.commit })}
          </div>
        )}
      </div>
      {/* 对话浮窗：任何页面都能看着过程 / 随时提意见 */}
      {!chatPage && dockOpen ? (
        <div className="flex w-[380px] shrink-0 flex-col border-l">
          <div className="flex h-9 shrink-0 items-center border-b px-3">
            <span className="text-xs font-semibold text-muted-foreground">对话</span>
            <button className="ml-auto rounded p-1 text-muted-foreground hover:bg-muted" onClick={() => setDockOpen(false)} aria-label="收起对话">
              <X className="size-4" />
            </button>
          </div>
          <ChatMessages state={state} onAnswer={engine.onAnswer} onDispatch={engine.commit} onRetry={engine.onRetry} />
          <div className="shrink-0 border-t p-3"><Composer /></div>
        </div>
      ) : null}
      {!chatPage && !dockOpen ? (
        <button
          className="fixed bottom-20 right-5 z-40 flex items-center gap-2 rounded-full border bg-background px-3.5 py-2 text-sm shadow-lg hover:bg-muted/60"
          onClick={() => setDockOpen(true)}
        >
          <MessagesSquare className="size-4" /> 对话
          {state.runActive ? <span className="size-1.5 rounded-full bg-red-500" /> : null}
        </button>
      ) : null}
    </div>
  );
}
