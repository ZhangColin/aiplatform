import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

/**
 * 视觉 token 基座契约（#342）：期望值逐字来自独立正本
 * docs/stitch_prd/luminous_electric_ai/DESIGN.md（spec #320 ⑥：原型 html
 * 数值偏移不取）。防的是 token 删除/漂移与语义映射断裂——谁改 token 不对
 * DESIGN.md 就红。文件缺失＝基座断裂，readFileSync 直接红（先例：圈注
 * 契约缝 annotation.contract.test.ts）。
 */

const tokens = readFileSync(new URL("./tokens.css", import.meta.url), "utf8");
const globals = readFileSync(new URL("./globals.css", import.meta.url), "utf8");

/** 声明表：--name: value; keepFirst＝同名取首声明（:root 先于 .dark、mobile
    档先于媒体查询覆盖档），否则同名后值覆盖前值。 */
function declarations(css: string, keepFirst = false): Map<string, string> {
  const map = new Map<string, string>();
  for (const m of css.matchAll(/--([a-z0-9-]+)\s*:\s*([^;{}]+);/gi)) {
    const key = m[1].toLowerCase();
    if (keepFirst && map.has(key)) continue;
    map.set(key, m[2].trim());
  }
  return map;
}

const norm = (v: string) => v.replace(/\s+/g, "").toLowerCase();

function expectToken(
  map: Map<string, string>,
  name: string,
  expected: string,
) {
  expect(map.has(name), `缺 token --${name}`).toBe(true);
  expect(norm(map.get(name)!), `--${name} ≠ DESIGN.md 正本值`).toBe(
    norm(expected),
  );
}

const T = declarations(tokens);
const G = declarations(globals);
// 语义映射/栅格断言取首声明（浅色 :root 档、mobile 档）
const G_LIGHT = declarations(globals, true);
const T_MOBILE = declarations(tokens, true);

describe("色板（DESIGN.md frontmatter 逐名对齐）", () => {
  it("主色/信号色", () => {
    expectToken(T, "color-electric-indigo", "#4F46E5");
    expectToken(T, "color-tech-blue", "#2563EB");
    expectToken(T, "color-electric-blue-light", "#3B82F6");
    expectToken(T, "color-mint-teal", "#06B6D4");
    expectToken(T, "color-emerald-active", "#10B981");
  });

  it("画布与表面四级、描边、墨色四级", () => {
    expectToken(T, "color-canvas-base", "#F8FAFC");
    expectToken(T, "color-surface-dawn", "#F0F4F8");
    expectToken(T, "color-surface-mist", "#EFF6FF");
    expectToken(T, "color-surface-violet-tint", "#F5F3FF");
    expectToken(T, "color-subtle-blue", "#E0E7FF");
    expectToken(T, "color-slate-soft", "#E2E8F0");
    expectToken(T, "color-ink-primary", "#0F172A");
    expectToken(T, "color-ink-secondary", "#1E293B");
    expectToken(T, "color-ink-muted", "#475569");
    expectToken(T, "color-ink-faint", "#64748B");
  });

  it("玻璃表面与遮罩（Elevation 段）", () => {
    expectToken(T, "color-glass-panel", "rgba(255, 255, 255, 0.75)");
    expectToken(T, "color-glass-card", "rgba(255, 255, 255, 0.8)");
    expectToken(T, "color-glass-input", "rgba(255, 255, 255, 0.85)");
    expectToken(T, "color-glass-float", "rgba(255, 255, 255, 0.9)");
    expectToken(T, "color-glass-modal", "rgba(255, 255, 255, 0.94)");
    expectToken(T, "color-scrim", "rgba(15, 23, 42, 0.2)");
  });
});

describe("圆角（控件 8 / 卡片 16 / 外框 24 / 胶囊）", () => {
  it("四档圆角 token", () => {
    expectToken(T, "radius-sm", "0.25rem");
    expectToken(T, "radius-lg", "0.5rem"); // 控件 8px（基件 rounded-lg 消费面）
    expectToken(T, "radius-xl", "1rem"); // 卡片 16px
    expectToken(T, "radius-2xl", "1.5rem"); // 外框 24px
    expectToken(G, "radius", "0.5rem");
  });
});

describe("elevation 四级与交互光晕（DESIGN.md Elevation/Components 段）", () => {
  it("L1 磨砂卡 / L2 悬停浮层 / L3 命令框投影", () => {
    expectToken(
      T,
      "shadow-e1",
      "0 2px 8px -2px rgba(79, 70, 229, 0.05), 0 1px 3px rgba(15, 23, 42, 0.04)",
    );
    expectToken(
      T,
      "shadow-e2",
      "0 8px 20px -4px rgba(79, 70, 229, 0.08), 0 4px 6px -2px rgba(15, 23, 42, 0.03)",
    );
    expectToken(
      T,
      "shadow-e3",
      "0 20px 30px -8px rgba(15, 23, 42, 0.08), 0 10px 14px -6px rgba(79, 70, 229, 0.06)",
    );
  });

  it("主钮 hover 光晕 / 输入 focus 光晕 / 主钮内嵌高光", () => {
    expectToken(
      T,
      "shadow-glow-primary",
      "0 4px 14px 0 rgba(79, 70, 229, 0.35)",
    );
    expectToken(
      T,
      "shadow-focus-halo",
      "0 0 0 3px rgba(79, 70, 229, 0.15)",
    );
    expectToken(
      T,
      "shadow-inset-highlight",
      "inset 0 1px 0 rgba(255, 255, 255, 0.25)",
    );
  });
});

describe("字号阶（Geist＋JetBrains Mono，tabular-nums）", () => {
  it("标题四档（含移动变体）", () => {
    expectToken(T, "text-headline-xl", "36px");
    expectToken(T, "text-headline-xl--line-height", "44px");
    expectToken(T, "text-headline-xl--letter-spacing", "-0.03em");
    expectToken(T, "text-headline-xl-mobile", "28px");
    expectToken(T, "text-headline-lg", "24px");
    expectToken(T, "text-headline-lg-mobile", "20px");
    expectToken(T, "text-headline-md", "18px");
    expectToken(T, "text-headline-sm", "15px");
  });

  it("正文/标签/代码阶", () => {
    expectToken(T, "text-body-lg", "16px");
    expectToken(T, "text-body-md", "14px");
    expectToken(T, "text-body-sm", "13px");
    expectToken(T, "text-label-md", "13px");
    expectToken(T, "text-label-sm", "11px");
    expectToken(T, "text-code-md", "13px");
    expectToken(T, "text-code-sm", "11px");
  });

  it("字体接线：mono＝JetBrains Mono，sans 带PingFang SC CJK 回退", () => {
    expectToken(G, "font-mono", "var(--font-jetbrains-mono)");
    expect(norm(G.get("font-sans") ?? "")).toContain("pingfangsc");
  });

  it("tabular-nums 全局生效（DESIGN：数字一律表格数字）", () => {
    expect(globals).toContain("tabular-nums");
  });
});

describe("栅格与间距（4/8 基数＋12 列断点）", () => {
  it("沟槽/页缘 token 三档（mobile/tablet/desktop）", () => {
    expectToken(T_MOBILE, "grid-gutter", "1rem");
    expectToken(T_MOBILE, "grid-page-margin", "1rem");
    // tablet（≥48rem）：gutter 16px、margin 24px；desktop（≥80rem）：24px、32px
    const flat = norm(tokens);
    expect(flat).toContain("@media(min-width:48rem)");
    expect(flat).toContain("@media(min-width:80rem)");
    expect(flat).toContain("--grid-gutter:1.5rem");
    expect(flat).toContain("--grid-page-margin:1.5rem");
    expect(flat).toContain("--grid-page-margin:2rem");
  });
});

describe("语义映射（兼容层：既有基件继续消费语义变量）", () => {
  it("浅色档语义变量＝DESIGN.md 取值（globals :root）", () => {
    expectToken(G_LIGHT, "background", "var(--color-canvas-base)");
    expectToken(G_LIGHT, "foreground", "var(--color-ink-primary)");
    expectToken(G_LIGHT, "card", "var(--color-glass-card)");
    expectToken(G_LIGHT, "popover", "var(--color-glass-modal)");
    expectToken(G_LIGHT, "primary", "var(--color-electric-indigo)");
    expectToken(G_LIGHT, "primary-foreground", "#ffffff");
    expectToken(G_LIGHT, "secondary", "rgba(255, 255, 255, 0.85)"); // 玻璃次钮
    expectToken(G_LIGHT, "secondary-foreground", "var(--color-ink-primary)");
    expectToken(G_LIGHT, "muted", "var(--color-surface-dawn)");
    expectToken(G_LIGHT, "muted-foreground", "var(--color-ink-muted)");
    expectToken(G_LIGHT, "accent", "var(--color-surface-mist)");
    expectToken(G_LIGHT, "border", "var(--color-subtle-blue)");
    expectToken(G_LIGHT, "input", "var(--color-slate-soft)");
    expectToken(G_LIGHT, "ring", "var(--color-electric-indigo)");
    expectToken(G_LIGHT, "destructive", "var(--color-error)");
  });

  it("L0 画布径向光晕挂 body（DESIGN Level 0）", () => {
    expect(norm(globals)).toContain(
      "background-image:radial-gradient(ellipseattop,var(--color-surface-mist)0%,transparent60%)",
    );
  });
});
