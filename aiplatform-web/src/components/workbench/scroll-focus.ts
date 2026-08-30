"use client";

import { useEffect, useRef, type RefObject } from "react";

import type { ChatFocus } from "./chat-mode";

/**
 * 深链聚焦「只滚一次」（issue #44/#49）：focus 命中时滚动对应卡到视口中心——
 * wait → `[data-wait-id]`（对话模式等待卡 / 顾问对话等待胶囊）、gate →
 * `[data-focus-gate]`（门卡）。focus 归一为原始字符串 key，滚成一次后 ref 去重，
 * 不依赖 focus 对象 / 数据引用稳定性——轮询 / 流式重渲染不会反复回中；目标尚未
 * 挂载（数据未到）时每次渲染后重试（一次 querySelector，代价可忽略），故 effect
 * 不设依赖数组。
 */
export function useScrollFocusOnce(
  focus: ChatFocus | undefined,
  containerRef: RefObject<HTMLElement | null>,
  { enabled = true }: { enabled?: boolean } = {},
) {
  const focusKey =
    focus?.kind === "wait" ? `wait:${focus.waitId}` : focus?.kind === "gate" ? "gate" : null;
  const scrolledKey = useRef<string | null>(null);

  useEffect(() => {
    if (!focusKey || !enabled || scrolledKey.current === focusKey) return;
    const selector =
      focus?.kind === "wait"
        ? `[data-wait-id="${CSS.escape(focus.waitId)}"]`
        : '[data-focus-gate="true"]';
    const el = containerRef.current?.querySelector(selector);
    if (!el) return; // 目标卡尚未挂载（数据未到），留待下次渲染再试
    scrolledKey.current = focusKey;
    el.scrollIntoView({ block: "center", behavior: "smooth" });
  });
}
