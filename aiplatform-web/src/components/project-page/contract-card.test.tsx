import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { ContractCard } from "./contract-card";
import type { ContractDetail } from "@/lib/orders/contract";

// 合同预览卡（#336 最小呈现）：预览字段集（双方主体/项目范围/交付物/验收
// 标准/费用/付款计划/交期约定/合同版本）＋六态名＋双方签署位＋「需更新」
// 标志。静态渲染钉呈现面（交互与轮询归 hooks/交互测试）。
function contract(overrides: Partial<ContractDetail> = {}): ContractDetail {
  return {
    id: "c1",
    contractNo: "SG-3ukqz0c0",
    status: 3,
    statusName: "签署进行中",
    version: 1,
    needsUpdate: false,
    partyA: "张三",
    partyB: "语义引力（北京）科技有限公司",
    projectScope: "管理后台＋小程序全量交付",
    paymentPlan: "开工款 50%，验收 50%",
    scopeDeliverables: "交付清单：管理后台＋小程序",
    scopeAcceptance: "验收方式：按 PRD v3 验收",
    scopeTimeline: "工期口径：4 周",
    amount: 128000,
    currency: "CNY",
    prdVersionNo: 3,
    demoVersionNo: 2,
    partyASigned: true,
    partyBSigned: false,
    createdAt: "2026-10-17T01:00:00Z",
    ...overrides,
  };
}

describe("ContractCard · 合同预览卡（#336）", () => {
  it("预览字段集全呈现：编号/版本/六态名/签署位/八段字段", () => {
    const html = renderToStaticMarkup(<ContractCard contract={contract()} />);

    expect(html).toContain("SG-3ukqz0c0");
    expect(html).toContain("版本 v1");
    expect(html).toContain("签署进行中");
    expect(html).toContain("甲方 已签");
    expect(html).toContain("乙方 未签");
    expect(html).toContain("张三");
    expect(html).toContain("语义引力（北京）科技有限公司");
    expect(html).toContain("交付清单：管理后台＋小程序");
    expect(html).toContain("验收方式：按 PRD v3 验收");
    expect(html).toContain("¥1,280"); // 冻结金额（分 → 元呈现）
    expect(html).toContain("开工款 50%，验收 50%");
    expect(html).toContain("工期口径：4 周");
    expect(html).not.toContain("需更新");
  });

  it("「需更新」标志：范围或报价变动后提示重新核对", () => {
    const html = renderToStaticMarkup(
      <ContractCard contract={contract({ needsUpdate: true })} />,
    );

    expect(html).toContain("范围或报价有更新");
    expect(html).toContain("正在重新准备合同");
  });

  it("未录入字段占位（准备中合同内容由后台录入）", () => {
    const html = renderToStaticMarkup(
      <ContractCard
        contract={contract({
          status: 1,
          statusName: "准备中",
          partyA: undefined,
          partyB: undefined,
          projectScope: undefined,
          paymentPlan: undefined,
          partyASigned: false,
          partyBSigned: false,
        })}
      />,
    );

    expect(html).toContain("准备中");
    expect(html).toContain("待录入");
    expect(html).toContain("甲方 未签");
    expect(html).toContain("乙方 未签");
    expect(html).toContain("合同由后台人工推进");
  });

  it("双方均签：完成徽标呈现", () => {
    const html = renderToStaticMarkup(
      <ContractCard
        contract={contract({
          status: 4,
          statusName: "签署完成",
          partyBSigned: true,
        })}
      />,
    );

    expect(html).toContain("双方均已签署");
  });
});
