import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { HomeMetrics } from "./home-metrics";

/**
 * 指标卡渲染契约（#345，spec #320 ⑥ 首页 P01 照原型）：四张指标卡「数值+单位+
 * 标题+说明」逐字照原型。
 */
describe("HomeMetrics（首页指标卡×4·文案逐字照原型）", () => {
  const html = renderToStaticMarkup(<HomeMetrics />);

  it("指标准确：15 min / 1:1 仿真 / 100 % / 0 门槛", () => {
    expect(html).toContain("15");
    expect(html).toContain("min");
    expect(html).toContain("1:1");
    expect(html).toContain("仿真");
    expect(html).toContain("100");
    expect(html).toContain("%");
    expect(html).toContain("0");
    expect(html).toContain("门槛");
  });

  it("标题与说明逐字照原型", () => {
    expect(html).toContain("PRD 快速对齐生成");
    expect(html).toContain("多轮自然语言提问澄清，自动结构化生成标准化软件规格书。");
    expect(html).toContain("真实可操作 DEMO 验证");
    expect(html).toContain("拒绝静态原型画饼，直接体验完整的前后端交互与业务链路。");
    expect(html).toContain("认可后启动付款开工");
    expect(html).toContain("PRD 与 Demo 双重确认，契约严格锁定生产交付边界与周期。");
    expect(html).toContain("纯自然语言即可发起");
    expect(html).toContain("无需专业技术背景或编写文档，对话即可将业务灵感转化为系统工程。");
  });
});
