"use client";

/**
 * ============================================================================
 * 原 型 —— 预览新窗口页（#72）：预览「在新窗口打开」的落地页
 * ============================================================================
 * 只渲染用户系统本身（?stage=N），浅色锁定——预览内容是用户的系统，
 * 不随平台 Light/Dark 主题翻转。
 * ============================================================================
 */

import * as React from "react";
import { useSearchParams } from "next/navigation";

import { ShopPreview } from "../../_shared/paradigms";

export default function PreviewWindow() {
  return (
    <React.Suspense>
      <PreviewWindowInner />
    </React.Suspense>
  );
}

function PreviewWindowInner() {
  const stage = Number(useSearchParams().get("stage") ?? "3");
  return (
    <div className="min-h-svh bg-white text-zinc-900">
      {stage <= 1 ? (
        <div className="space-y-3 p-4">
          <div className="h-9 animate-pulse rounded-md bg-zinc-200" />
          <div className="h-28 animate-pulse rounded-md bg-zinc-200" />
        </div>
      ) : (
        <ShopPreview stage={stage} />
      )}
    </div>
  );
}
