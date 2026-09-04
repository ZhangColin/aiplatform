"use client";

/* eslint-disable react-hooks/set-state-in-effect -- 原型：effect 派生态（滑出工作区/跳预览）是刻意的 */

/**
 * ============================================================================
 * 原 型 —— 平台整体 UI（#72）：项目页（对话主角式，已定方向）
 * ============================================================================
 * 对话居中当主角；工作区平时收起、有成果自动滑出（slide-in）。
 * 工作区标题与 tab 条合一：tab 簇即标题，「+ 新标签页」挂范式。
 * ============================================================================
 */

import * as React from "react";
import { PanelRightOpen, Plus, X } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from "@/components/ui/resizable";
import { cn } from "@/lib/utils";

import { ChatMessages, Composer, LivePill } from "../../_shared/chat-parts";
import { PARADIGMS, type Paradigm } from "../../_shared/paradigms";
import type { Ev, RunState } from "../../_shared/run-engine";
import { ProtoSidebar } from "../../_shared/sidebar";
import { ProtoBar } from "../../_shared/switcher";
import { useRunEngine } from "../../_shared/use-run-engine";

export default function PlatformProject() {
  const engine = useRunEngine("preview");
  return (
    <div className="flex h-svh">
      <ProtoSidebar
        defaultCollapsed
        active="project"
        homeHref="/proto/platform"
        projectHref={() => "/proto/platform/project"}
      />
      <ProjectBody engine={engine} />
      <ProtoBar engine={engine} />
    </div>
  );
}

function ProjectBody({ engine }: { engine: ReturnType<typeof useRunEngine> }) {
  const { state } = engine;
  const [wsOpen, setWsOpen] = React.useState(false);
  const [openTabs, setOpenTabs] = React.useState<string[]>(
    PARADIGMS.filter((p: Paradigm) => p.defaultOn).map((p) => p.id),
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

  /* 「查看当时」被点 → 工作区打开并切到预览 */
  React.useEffect(() => {
    if (state.viewing !== null) {
      setWsOpen(true);
      setOpenTabs((tabs) => (tabs.includes("preview") ? tabs : ["preview", ...tabs]));
      setActive("preview");
    }
  }, [state.viewing]);

  return (
    <ResizablePanelGroup orientation="horizontal" className="min-w-0 flex-1">
      {/* 对话主角列（宽度可拖） */}
      <ResizablePanel defaultSize={wsOpen ? 46 : 100} minSize={30} className="flex flex-col">
        <header className="flex h-12 shrink-0 items-center gap-2 border-b px-4">
          <span className="truncate text-sm font-medium">巷口花店小程序</span>
          <div className="ml-auto flex items-center gap-2">
            {state.runActive ? <LivePill /> : null}
            {!wsOpen ? (
              <Button size="sm" variant="outline" className="h-7 text-xs transition-transform active:scale-95" onClick={() => setWsOpen(true)} title="展开成果面板">
                <PanelRightOpen className="size-3.5" /> 成果
              </Button>
            ) : null}
          </div>
        </header>
        <div className="mx-auto flex min-h-0 w-full max-w-3xl flex-1 flex-col">
          <ChatMessages state={state} onAnswer={engine.onAnswer} onDispatch={engine.commit} onRetry={engine.onRetry} />
          {/* 发送框：底部渐变淡出，消息滚到下面也不突兀 */}
          <div className="shrink-0 bg-gradient-to-t from-background via-background to-transparent p-4 pt-6">
            <Composer />
          </div>
        </div>
      </ResizablePanel>
      {/* 呼出式工作区：tab 条即标题条（滑入动效 = 状态切换反馈）；拖拽分隔条调宽。
          平铺无圆角——与对话列同墙同地，四角圆润反而割裂。 */}
      {wsOpen ? (
        <>
          <ResizableHandle withHandle className="w-2 border-0 bg-transparent" />
          <ResizablePanel defaultSize={54} minSize={34} className="proto-ws-in">
            <style>{`@keyframes protoWsIn { from { transform: translateX(24px); opacity: 0; } to { transform: none; opacity: 1; } } .proto-ws-in { animation: protoWsIn .22s ease-out; }`}</style>
            <div className="flex h-full min-h-0 flex-col border-l bg-background">
              <WorkspaceBar
                state={state}
                onDispatch={engine.commit}
                openTabs={openTabs}
                setOpenTabs={setOpenTabs}
                active={active}
                setActive={setActive}
                onClose={() => setWsOpen(false)}
              />
            </div>
          </ResizablePanel>
        </>
      ) : null}
    </ResizablePanelGroup>
  );
}

/** 工作区条：tab 簇 + 「+ 新标签页」+ 关闭，一行即标题。 */
function WorkspaceBar({
  state,
  onDispatch,
  openTabs,
  setOpenTabs,
  active,
  setActive,
  onClose,
}: {
  state: RunState;
  onDispatch: (ev: Ev) => void;
  openTabs: string[];
  setOpenTabs: (tabs: string[]) => void;
  active: string;
  setActive: (id: string) => void;
  onClose: () => void;
}) {
  const addable = PARADIGMS.filter((p: Paradigm) => !openTabs.includes(p.id));
  const activeParadigm = PARADIGMS.find((p: Paradigm) => p.id === active);
  return (
    <>
      <div className="flex h-11 shrink-0 items-center gap-0.5 overflow-x-auto border-b pl-2 pr-1">
        {openTabs.map((id) => {
          const p = PARADIGMS.find((p: Paradigm) => p.id === id)!;
          return (
            <span
              key={id}
              className={cn(
                "group flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[13px] transition-colors",
                active === id ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/50",
              )}
            >
              <button className="flex items-center gap-1.5" onClick={() => setActive(id)}>
                {p.icon} {p.label}
              </button>
              {openTabs.length > 1 ? (
                <button
                  className="rounded p-0.5 opacity-0 transition-opacity hover:bg-background group-hover:opacity-100"
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
          <DropdownMenuTrigger className="flex shrink-0 items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted">
            <Plus className="size-3.5" /> 新标签页
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-64">
            {addable.length === 0 ? (
              <div className="px-2 py-1.5 text-xs text-muted-foreground">能挂的都挂上了</div>
            ) : (
              addable.map((p: Paradigm) => (
                <DropdownMenuItem
                  key={p.id}
                  onClick={() => {
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
        <span className="flex-1" />
        <button
          className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          onClick={onClose}
          aria-label="收起工作区"
        >
          <X className="size-4" />
        </button>
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        {activeParadigm?.render({ state, onDispatch })}
      </div>
    </>
  );
}
