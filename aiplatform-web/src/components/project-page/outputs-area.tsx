"use client";

import { PanelRightClose, Plus, X } from "lucide-react";
import { useState } from "react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

import { LiveRail } from "./live-panel";
import { PARADIGMS, paradigmOf, type ParadigmCtx } from "./paradigms";

/**
 * 成果区（#72 决议呼出式 / #79 落地）：tab 簇即标题条（UI 面不标区名）——
 * 已挂范式 tab +「+ 新标签页」（按范式注册表加挂）+ 收起键，一行即标题；
 * 主体 = 激活范式 + 直播侧栏（跨范式常驻——直播是 run 的面，run 结束即逝归
 * LiveRail 自管）。平铺无圆角：与对话列同墙同地。tab 状态归装配层
 * （useOutputsTabs）：自动切换（生成→系统、下单→订单、「去看看」→文档）与
 * 手动切换同一入口。
 */

/** 成果区 tab 簇状态（装配层持有；自动/手动切换同一入口）。 */
export type OutputsTabs = {
  /** 已挂载范式 id（注册表序）。 */
  openTabs: string[];
  /** 激活范式 id。 */
  activeTab: string;
  /** 挂载（若无）并激活某范式——「+ 新标签页」与自动切换共用。 */
  mount: (id: string) => void;
  /** 仅切换激活（tab 点选）。 */
  activate: (id: string) => void;
  /** 关闭某范式 tab；关的是激活范式则回退剩余首个，最后一面不可关。 */
  close: (id: string) => void;
};

/** tab 簇一体状态（单一 state 对象保证挂载/关闭对 openTabs+activeTab 的原子调整）。 */
type TabsState = { openTabs: string[]; activeTab: string };

/** tab 簇状态（默认挂载 = 注册表 defaultOn；激活缺省主舞台「系统」）。 */
export function useOutputsTabs(): OutputsTabs {
  const [state, setState] = useState<TabsState>(() => ({
    openTabs: PARADIGMS.filter((p) => p.defaultOn).map((p) => p.id),
    activeTab: PARADIGMS[0].id,
  }));

  function mount(id: string) {
    setState(({ openTabs }) => ({
      openTabs: openTabs.includes(id) ? openTabs : [...openTabs, id],
      activeTab: id,
    }));
  }

  function close(id: string) {
    setState(({ openTabs, activeTab }) => {
      const next = openTabs.filter((t) => t !== id);
      if (next.length === 0) return { openTabs, activeTab }; // 至少留一面
      return { openTabs: next, activeTab: activeTab === id ? next[0] : activeTab };
    });
  }

  return {
    openTabs: state.openTabs,
    activeTab: state.activeTab,
    mount,
    activate: (id) => setState((s) => ({ ...s, activeTab: id })),
    close,
  };
}

export function OutputsArea({
  tabs,
  ctx,
  onClose,
}: {
  /** tab 簇状态（装配层的 useOutputsTabs 返回值）。 */
  tabs: OutputsTabs;
  /** 范式渲染上下文（项目事实与回调）。 */
  ctx: ParadigmCtx;
  /** 收起成果区（呼出式的收回侧）。 */
  onClose: () => void;
}) {
  const addable = PARADIGMS.filter((p) => !tabs.openTabs.includes(p.id));
  const active = paradigmOf(tabs.activeTab);

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* tab 条即标题条：tab 簇 +「+ 新标签页」+ 收起 */}
      <div
        className="flex h-11 shrink-0 items-center gap-0.5 overflow-x-auto border-b pl-2 pr-1"
        role="tablist"
        aria-label="成果区"
      >
        {tabs.openTabs.map((id) => {
          const p = paradigmOf(id)!;
          const activeNow = tabs.activeTab === id;
          return (
            <span
              key={id}
              className={cn(
                "group flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[13px] transition-colors",
                activeNow ? "bg-muted font-medium" : "text-muted-foreground hover:bg-muted/50",
              )}
            >
              <button
                type="button"
                role="tab"
                aria-selected={activeNow}
                className="flex items-center gap-1.5"
                onClick={() => tabs.activate(id)}
              >
                {p.icon} {p.label}
              </button>
              {tabs.openTabs.length > 1 ? (
                <button
                  type="button"
                  className="rounded p-0.5 opacity-0 transition-opacity hover:bg-background group-hover:opacity-100"
                  onClick={() => tabs.close(id)}
                  aria-label={`关闭${p.label}`}
                >
                  <X className="size-3" />
                </button>
              ) : null}
            </span>
          );
        })}
        <DropdownMenu>
          <DropdownMenuTrigger
            className="flex shrink-0 items-center gap-1 rounded-lg px-2 py-1.5 text-xs text-muted-foreground transition-colors hover:bg-muted"
            aria-label="新标签页"
          >
            <Plus className="size-3.5" /> 新标签页
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-64">
            {addable.length === 0 ? (
              <div className="px-2 py-1.5 text-xs text-muted-foreground">能挂的都挂上了</div>
            ) : (
              addable.map((p) => (
                <DropdownMenuItem key={p.id} onClick={() => tabs.mount(p.id)}>
                  <span className="flex items-start gap-2">
                    <span className="mt-0.5">{p.icon}</span>
                    <span>
                      <span className="block text-[13px]">{p.label}</span>
                      <span className="block text-xs text-muted-foreground">{p.blurb}</span>
                    </span>
                  </span>
                </DropdownMenuItem>
              ))
            )}
          </DropdownMenuContent>
        </DropdownMenu>
        <span className="flex-1" />
        {/* 收起键只对 lg 呼出式布局有意义（<lg 走双页签，收起无可见效果） */}
        <button
          type="button"
          className="hidden rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground lg:flex"
          onClick={onClose}
          aria-label="收起成果区"
          title="收起成果区"
        >
          <PanelRightClose className="size-4" />
        </button>
      </div>

      {/* 主体：激活范式 + 直播侧栏（跨范式常驻） */}
      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        <div className="flex min-h-0 flex-1 flex-col">{active?.render(ctx)}</div>
        <LiveRail projectId={ctx.projectId} />
      </div>
    </div>
  );
}
