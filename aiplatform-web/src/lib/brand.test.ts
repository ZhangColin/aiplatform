import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { BRAND_LOGO, SITE_DESCRIPTION, SITE_NAME } from "./brand";

/**
 * 站点品牌常量契约（#342）：期望值来自独立正本——《语义引力网站设计资料包
 * _v1.0》「品牌信息」章（中文品牌「语义引力」、对外简述建议原文），不是从
 * 实现复算。title/description 与品牌件全部从本模块取值（单一来源）。
 */
describe("站点品牌常量（#342 品牌落位）", () => {
  it("站点名＝「语义引力」（spec #320 ⑥ 品牌落位）", () => {
    expect(SITE_NAME).toBe("语义引力");
  });

  it("对外简述＝资料包「对外简述建议」原文", () => {
    expect(SITE_DESCRIPTION).toBe(
      "把需求聊清楚，形成 PRD，先体验 Demo，再推进正式交付",
    );
  });

  it("logo 两版路径：深色圆底（浅色场合）＋白色透明（深色场合）", () => {
    expect(BRAND_LOGO).toEqual({
      dark: "/brand/logo-dark-round.svg",
      white: "/brand/logo-white-transparent.png",
    });
  });

  it("站点 title/description 消费品牌常量（layout 不得另落字面量）", () => {
    const layout = readFileSync(
      new URL("../app/layout.tsx", import.meta.url),
      "utf8",
    );
    expect(layout).toContain("SITE_NAME");
    expect(layout).toContain("SITE_DESCRIPTION");
  });
});
