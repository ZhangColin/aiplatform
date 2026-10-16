import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { Badge, badgeVariants } from "./badge";
import { buttonVariants } from "./button";
import { Card } from "./card";
import { Input } from "./input";

/**
 * 基件对齐 DESIGN.md 组件规格（#342 AC）：期望值来自独立正本 DESIGN.md
 * 「Components」段（渐变主钮/玻璃次钮/幽灵钮、AI Running 与 Success/
 * Warning pill、输入框 focus 光晕、卡片）。断言 cva 变体串与 SSR 类名——
 * 钉的是「基件 token 消费」契约，防规格回退。
 */

describe("按钮三态（DESIGN.md Buttons）", () => {
  it("主钮＝电靛→科技蓝 135° 渐变＋内嵌高光＋hover 光晕", () => {
    const v = buttonVariants({ variant: "default" });
    expect(v).toContain("bg-linear-to-br");
    expect(v).toContain("from-electric-indigo");
    expect(v).toContain("to-tech-blue");
    expect(v).toContain("text-primary-foreground");
    expect(v).toContain("shadow-inset-highlight");
    expect(v).toContain("hover:shadow-glow-primary");
  });

  it("玻璃次钮＝85% 白底＋发光描边，hover 换晨雾底/靛描边", () => {
    const v = buttonVariants({ variant: "secondary" });
    expect(v).toContain("bg-secondary");
    expect(v).toContain("text-secondary-foreground");
    expect(v).toContain("border-border");
    expect(v).toContain("hover:bg-accent");
    expect(v).toContain("hover:border-glass-border-hover");
  });

  it("幽灵钮＝透明底/弱墨字，hover 换晨曦底/主墨字", () => {
    const v = buttonVariants({ variant: "ghost" });
    expect(v).toContain("text-muted-foreground");
    expect(v).toContain("hover:bg-muted");
    expect(v).toContain("hover:text-foreground");
  });
});

describe("状态 pill（DESIGN.md Status Badges）", () => {
  it("基形＝全胶囊", () => {
    expect(badgeVariants()).toContain("rounded-full");
  });

  it("AI Running＝靛→青渐变底/靛字/靛描边＋6px 青芯脉冲点", () => {
    const v = badgeVariants({ variant: "ai-running" });
    expect(v).toContain("linear-gradient(135deg");
    expect(v).toContain("rgba(79,70,229,0.08)");
    expect(v).toContain("rgba(6,182,212,0.12)");
    expect(v).toContain("text-electric-indigo");
    expect(v).toContain("border-glass-border-hover");
    expect(v).toContain("before:size-1.5");
    expect(v).toContain("before:bg-mint-teal");
    expect(v).toContain("before:animate-pulse");
  });

  it("Success＝emerald 10% 底/深 emerald 字＋6px emerald 点", () => {
    const v = badgeVariants({ variant: "success" });
    expect(v).toContain("bg-emerald-active/10");
    expect(v).toContain("text-success-deep");
    expect(v).toContain("border-emerald-active/25");
    expect(v).toContain("before:size-1.5");
    expect(v).toContain("before:bg-emerald-active");
  });

  it("Warning＝amber 10% 底/深 amber 字/25% 描边", () => {
    const v = badgeVariants({ variant: "warning" });
    expect(v).toContain("bg-warning/10");
    expect(v).toContain("text-warning-strong");
    expect(v).toContain("border-warning/25");
  });

  it("pill 变体渲染出 dot 伪元素类与文案（SSR）", () => {
    const html = renderToStaticMarkup(
      <Badge variant="ai-running">AI Running</Badge>,
    );
    expect(html).toContain("AI Running");
    expect(html).toContain("before:bg-mint-teal");
  });
});

describe("输入框（DESIGN.md Input Fields）", () => {
  it("玻璃底＋8px 磨砂＋slate 描边；focus＝靛描边＋3px 光晕", () => {
    const html = renderToStaticMarkup(<Input />);
    expect(html).toContain("bg-glass-input");
    expect(html).toContain("backdrop-blur-sm");
    expect(html).toContain("border-input");
    expect(html).toContain("focus-visible:border-ring");
    expect(html).toContain("focus-visible:shadow-focus-halo");
  });
});

describe("卡片（DESIGN.md Cards）", () => {
  it("默认卡＝玻璃卡面/发光描边/16px 圆角/e1 环境影", () => {
    const html = renderToStaticMarkup(<Card>内容</Card>);
    expect(html).toContain("bg-card");
    expect(html).toContain("border-border");
    expect(html).toContain("rounded-xl");
    expect(html).toContain("shadow-e1");
  });

  it("spotlight 卡＝右上青色径向辉光", () => {
    const html = renderToStaticMarkup(
      <Card variant="spotlight">内容</Card>,
    ).replace(/_/g, " "); // 类名内空隙为下划线形，归一后按 DESIGN 字面断言
    expect(html).toContain("radial-gradient(circle at 100% 0%");
    expect(html).toContain("rgba(6,182,212,0.08)");
  });
});
