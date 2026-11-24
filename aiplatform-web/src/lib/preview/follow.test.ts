import { describe, expect, it } from "vitest";

import type { WorkPart } from "@/lib/store/work-message";

import { followedPreviewRoute, previewRouteOfWritePath } from "./follow";

/**
 * 预览跟随的纯函数（#618，ADR-0057「write_file 路径纯前端映射路由、仅路由变化才
 * 导航」）：App Router 文件路径 → 应用路由的表驱动契约。正本口径——剥路由组
 * （`(group)`）、动态段/拦截段/平行路由/私有目录不切页（无确定 URL）、API 路由
 * 与布局/样式/组件/库等非页面文件不切页、同页反复重写的路径映射同一路由。
 *
 * 期望值来自 Next.js App Router 的路由约定（文件系统 → URL 的公开映射），不是从
 * 实现反推。
 */
describe("previewRouteOfWritePath · App Router 路径 → 应用路由", () => {
  it.each([
    // ── 页面：映射到路由 ──────────────────────────────────────────────
    ["根页", "src/app/page.tsx", "/"],
    ["根页（jsx）", "src/app/page.jsx", "/"],
    ["一根级页", "src/app/about/page.tsx", "/about"],
    ["多级页", "src/app/dashboard/orders/page.tsx", "/dashboard/orders"],
    ["无 src 前缀", "app/about/page.tsx", "/about"],
    ["工作区绝对路径", "/workspace/src/app/about/page.tsx", "/about"],
    ["工作区绝对路径（无 src）", "/workspace/app/about/page.tsx", "/about"],
    ["反斜杠路径", "src\\app\\about\\page.tsx", "/about"],
    ["点前缀", "./src/app/about/page.tsx", "/about"],
    ["路由组剥离", "src/app/(marketing)/about/page.tsx", "/about"],
    ["根路由组", "src/app/(marketing)/page.tsx", "/"],
    ["多级路由组", "src/app/(marketing)/(shop)/cart/page.tsx", "/cart"],
    ["分组与实体段混排", "src/app/(shop)/cart/(steps)/checkout/page.tsx", "/cart/checkout"],

    // ── 非页面文件：不切页 ────────────────────────────────────────────
    ["布局", "src/app/layout.tsx", undefined],
    ["嵌套布局", "src/app/about/layout.tsx", undefined],
    ["样式", "src/app/globals.css", undefined],
    ["loading", "src/app/about/loading.tsx", undefined],
    ["error", "src/app/about/error.tsx", undefined],
    ["not-found", "src/app/not-found.tsx", undefined],
    ["template", "src/app/about/template.tsx", undefined],
    ["页面测试文件", "src/app/about/page.test.tsx", undefined],
    ["API 路由", "src/app/api/orders/route.ts", undefined],
    ["组件目录", "src/components/order-table.tsx", undefined],
    ["lib 目录", "src/lib/db.ts", undefined],
    ["hooks 目录", "src/hooks/use-orders.ts", undefined],
    ["样式目录", "src/styles/theme.css", undefined],
    ["public 资源", "public/logo.svg", undefined],
    ["非应用目录的 page.tsx", "src/components/page.tsx", undefined],

    // ── 无确定 URL：动态段 / 拦截段 / 平行路由 / 私有目录 ─────────────
    ["动态段", "src/app/blog/[slug]/page.tsx", undefined],
    ["catch-all 段", "src/app/docs/[...slug]/page.tsx", undefined],
    ["可选 catch-all 段", "src/app/docs/[[...slug]]/page.tsx", undefined],
    ["拦截段", "src/app/feed/(.)photo/page.tsx", undefined],
    ["平行路由", "src/app/@modal/(.)photo/page.tsx", undefined],
    ["私有目录", "src/app/_components/page.tsx", undefined],

    // ── 边界输入 ──────────────────────────────────────────────────────
    ["空路径", "", undefined],
    ["纯空白", "   ", undefined],
    ["非路径文本", "readme.md", undefined],
    ["只有 app 目录", "src/app", undefined],
  ])("%s：%s", (_name, path, expected) => {
    expect(previewRouteOfWritePath(path)).toBe(expected);
  });
});

describe("followedPreviewRoute · 工作消息部件 → 当前应跟随的页面", () => {
  function action(overrides: Partial<Extract<WorkPart, { kind: "action" }>>): WorkPart {
    return {
      kind: "action",
      id: "e1",
      toolCallId: "tc-1",
      toolName: "write_file",
      state: "completed",
      label: "编写【page】",
      ...overrides,
    };
  }

  it("取最新一条可映射页面路径的路由（写到哪页跟哪页）", () => {
    const parts: WorkPart[] = [
      action({ id: "e1", toolCallId: "tc-1", path: "src/app/page.tsx" }),
      action({ id: "e2", toolCallId: "tc-2", path: "src/app/about/page.tsx" }),
    ];

    expect(followedPreviewRoute(parts)).toBe("/about");
  });

  it("同一页面反复重写：路径相同不改变跟随路由（不跳）", () => {
    const parts: WorkPart[] = [
      action({ id: "e1", toolCallId: "tc-1", path: "src/app/about/page.tsx" }),
      action({ id: "e2", toolCallId: "tc-1", state: "running", path: "src/app/about/page.tsx" }),
      action({ id: "e3", toolCallId: "tc-2", path: "src/app/about/page.tsx" }),
    ];

    expect(followedPreviewRoute(parts)).toBe("/about");
  });

  it("组件／样式／库／布局／API 等非页面写入不切页：保持最近一个页面路由", () => {
    const parts: WorkPart[] = [
      action({ id: "e1", toolCallId: "tc-1", path: "src/app/cart/page.tsx" }),
      action({ id: "e2", toolCallId: "tc-2", path: "src/components/cart-table.tsx" }),
      action({ id: "e3", toolCallId: "tc-3", path: "src/app/globals.css" }),
      action({ id: "e4", toolCallId: "tc-4", path: "src/lib/db.ts" }),
      action({ id: "e5", toolCallId: "tc-5", path: "src/app/layout.tsx" }),
      action({ id: "e6", toolCallId: "tc-6", path: "src/app/api/orders/route.ts" }),
    ];

    expect(followedPreviewRoute(parts)).toBe("/cart");
  });

  it("参数在途（无 path 的 started）与信号部件不参与", () => {
    const parts: WorkPart[] = [
      action({ id: "e1", toolCallId: "tc-0", path: "src/app/orders/page.tsx" }),
      action({ id: "e2", toolCallId: "tc-1", state: "started" }),
      { kind: "signal", id: "e3", signal: "derailed" },
    ];

    expect(followedPreviewRoute(parts)).toBe("/orders");
  });

  it("没有页面写入时无跟随目标", () => {
    expect(followedPreviewRoute([])).toBeUndefined();
    expect(
      followedPreviewRoute([action({ id: "e1", toolCallId: "tc-1", path: "src/components/x.tsx" })]),
    ).toBeUndefined();
  });
});
