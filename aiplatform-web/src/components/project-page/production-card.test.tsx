import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { ProductionCard } from "./production-card";
import type { DeliveryStatus } from "@/lib/orders/production";

// 生产状态卡（#338 最小呈现）：前台六态（后端四源纯推导投影）＋承接字段
// 仅真实值显示（AC67：排期未定显示待安排、空字段不渲染）。静态渲染钉呈现面
// （轮询与挂载归 hooks/order-panel 测试）。
function status(overrides: Partial<DeliveryStatus> = {}): DeliveryStatus {
  return {
    orderId: "900",
    orderStatus: 3,
    orderStatusName: "已支付",
    state: 4,
    stateName: "待安排生产",
    needsUserSupplement: false,
    handoff: null,
    ...overrides,
  };
}

describe("ProductionCard · 生产状态卡（#338）", () => {
  it("六态名呈现：待安排生产（开工条件满足）＋排期确定后更新提示", () => {
    const html = renderToStaticMarkup(<ProductionCard status={status()} />);

    expect(html).toContain("生产承接");
    expect(html).toContain("待安排生产");
    expect(html).toContain("开工条件已满足");
    expect(html).toContain("平台正在安排生产");
  });

  it("生产中：进展持续更新提示", () => {
    const html = renderToStaticMarkup(
      <ProductionCard status={status({ state: 5, stateName: "生产中" })} />,
    );

    expect(html).toContain("生产中");
    expect(html).toContain("生产进行中，进展会在这里持续更新");
  });

  it("承接字段仅真实值显示：录入段呈现、空段不渲染、预计开始仅真实值", () => {
    const html = renderToStaticMarkup(
      <ProductionCard
        status={status({
          handoff: {
            status: 1,
            statusName: "待安排生产",
            contact: "李经理（微信 ligoods）",
            nextStep: undefined,
            progressNote: "已完成技术方案拆解，本周进入开发",
            estimatedStartAt: null,
          },
        })}
      />,
    );

    expect(html).toContain("交付联系人");
    expect(html).toContain("李经理（微信 ligoods）");
    expect(html).toContain("进展更新");
    expect(html).toContain("已完成技术方案拆解");
    // 空段不渲染（下一步未录入）；无预计开始时间不虚构日期
    expect(html).not.toContain("下一步");
    expect(html).not.toContain("预计开始");
  });

  it("预计开始时间有真实值才显示（相对时间呈现）", () => {
    const html = renderToStaticMarkup(
      <ProductionCard
        status={status({
          handoff: {
            status: 1,
            statusName: "待安排生产",
            estimatedStartAt: "2026-10-20T09:00:00Z",
          },
        })}
      />,
    );

    expect(html).toContain("预计开始");
  });

  it("「需用户补充」盖帽态：横幅提示＋处理入口在对话区", () => {
    const html = renderToStaticMarkup(
      <ProductionCard
        status={status({
          state: 6,
          stateName: "需用户补充",
          needsUserSupplement: true,
          handoff: { status: 2, statusName: "生产中", progressNote: "等待用户补充资料" },
        })}
      />,
    );

    expect(html).toContain("需用户补充");
    expect(html).toContain("需要您提供一些资料或确认一些事项");
    expect(html).toContain("对话区");
    expect(html).toContain("等待用户补充资料"); // 承接字段照报（双行呈现）
  });
});
