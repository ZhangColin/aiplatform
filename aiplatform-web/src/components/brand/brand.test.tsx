import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { BrandHeader } from "./brand-header";
import { BrandLogo } from "./brand-logo";
import { OsMark } from "./os-mark";

/**
 * 品牌件渲染契约（#342）：期望值来自独立正本——spec #320 ⑥ 品牌落位
 * （站点名「语义引力」、logo 两版、品牌头「语义引力＋DELIVERY PIPELINE」、
 * 「OS」徽标＝视觉装饰不进品牌正本）。SSR 断言，沿用仓内组件测试模式。
 */
describe("BrandLogo（logo 两版）", () => {
  it("缺省＝深色圆底（浅色场合·导航位），alt＝站名", () => {
    const html = renderToStaticMarkup(<BrandLogo />);
    expect(html).toContain('src="/brand/logo-dark-round.svg"');
    expect(html).toContain('alt="语义引力"');
  });

  it('tone="white"＝白色透明版（深色场合留存）', () => {
    const html = renderToStaticMarkup(<BrandLogo tone="white" />);
    expect(html).toContain('src="/brand/logo-white-transparent.png"');
  });
});

describe("BrandHeader（品牌头＝语义引力＋DELIVERY PIPELINE）", () => {
  it("logo ＋ 站名 ＋ 管线标三件齐", () => {
    const html = renderToStaticMarkup(<BrandHeader />);
    expect(html).toContain('src="/brand/logo-dark-round.svg"');
    expect(html).toContain("语义引力");
    expect(html).toContain("DELIVERY PIPELINE");
  });
});

describe("OsMark（「OS」徽标＝视觉装饰）", () => {
  it("呈现 OS 字样且对读屏隐藏（装饰不进品牌正本）", () => {
    const html = renderToStaticMarkup(<OsMark />);
    expect(html).toContain("OS");
    expect(html).toContain('aria-hidden="true"');
  });
});
