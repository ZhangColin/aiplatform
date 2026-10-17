import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { SiteFooter } from "./site-footer";

/**
 * 首页合规条 + footer 渲染契约（#345，spec #320 ⑥ 首页 P01 照原型）：SECURITY
 * AUDITED 合规条 + 站点 footer 话术逐字照原型；链接占位（href="#"）。合规条与
 * footer 同件（首页底部一体呈现）。
 */
describe("SiteFooter（首页合规条 + footer·文案逐字照原型）", () => {
  const html = renderToStaticMarkup(<SiteFooter />);

  it("合规条逐字：SECURITY AUDITED + 隔离/加密说明 + 四链接占位", () => {
    expect(html).toContain("SECURITY AUDITED");
    expect(html).toContain("企业级私有化数据隔离 · 全链路传输加密");
    expect(html).toContain("服务协议");
    expect(html).toContain("隐私政策");
    expect(html).toContain("免责说明");
    expect(html).toContain("交付规范标准 (SLA)");
  });

  it("footer 品牌行 + 版权 + 三链接占位", () => {
    expect(html).toContain("语义引力 SEMANTIC GRAVITY");
    expect(html).toContain("· AI NATIVE DELIVERY OS");
    expect(html).toContain(
      "© 2025 语义引力 (Semantic Gravity) AI Native Delivery Machine. All rights reserved.",
    );
    expect(html).toContain("安全与合规");
    expect(html).toContain("工程规范");
    expect(html).toContain("API 状态");
  });

  it("链接占位（参数走配置）：合规/页脚链接 href 均为占位 #", () => {
    const anchors = html.match(/<a\b[^>]*href="([^"]*)"/g) ?? [];
    expect(anchors.length).toBeGreaterThanOrEqual(7);
    expect(anchors.every((a) => a.includes('href="#"'))).toBe(true);
  });
});
