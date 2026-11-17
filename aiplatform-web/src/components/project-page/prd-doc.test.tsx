import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { PrdDoc } from "./prd-doc";

// 请求面 mock 掉（无 QueryClient 可挂）：门读面经 hoisted 状态分岔——确认按钮
// 呈现条件（付费态∧PRD 在∧首建未派发）归 GET /build-gate 服务端单源，本文件
// 只测「读面为真时按钮在场、为假时缺席」的接线。
const gateState = vi.hoisted(() => ({ confirmable: false }));
vi.mock("@/hooks/use-build-gate", () => ({
  useBuildGate: () => ({ data: { prdConfirmable: gateState.confirmable } }),
  useConfirmPrdGeneration: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/hooks/use-prd", () => ({
  usePrd: () => ({ data: { content: "# PRD\n\n## 功能清单", updatedAt: "2026-11-17T09:00:00Z" }, isPending: false }),
}));

/**
 * PRD 文档面（#542 付费轨首建确认门恢复，ADR-0049——原动作形态回归）：确认
 * 按钮随门读面在场（确认即派发首个构建、点击幂等、确认不写版本行——版本表
 * 构建基准序零改动）；试用轨（岔路口点火）与已点火项目无按钮。
 */
describe("PrdDoc · 付费轨确认按钮呈现条件（#542）", () => {
  it("门读面为真：确认按钮在场（确认 PRD，开始生成）", () => {
    gateState.confirmable = true;

    const html = renderToStaticMarkup(<PrdDoc projectId="p1" />);

    expect(html).toContain("确认 PRD，开始生成");
    expect(html).toContain("data-prd-confirm");
    expect(html).toContain("确认后平台按这份 PRD 开始生成系统");
  });

  it("门读面为假：无确认按钮（试用轨岔路口点火/首建已派发）", () => {
    gateState.confirmable = false;

    const html = renderToStaticMarkup(<PrdDoc projectId="p1" />);

    expect(html).not.toContain("data-prd-confirm");
    expect(html).not.toContain("确认 PRD，开始生成");
  });
});
