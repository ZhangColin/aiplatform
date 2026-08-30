import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { queryKeys } from "@/lib/api/keys";
import type { ProjectDetail } from "@/lib/main-chain/project";

import { DocPanel } from "./doc-panel";

// 文档面板（issue #54，spec 0002 §4）：交付前 = 真实 PRD（`GET …/prd` 当前版
// markdown + 更新时间；未产出 = 引导占位）；已交付 = 交付说明 + 源码包（回归锁）。
// SSR 断言走 query cache 播种（useProjectJourney / usePrd 读 react-query）。
function render(detail: ProjectDetail, prd: { content: string; updatedAt: string } | null) {
  const qc = new QueryClient();
  qc.setQueryData(queryKeys.projects.detail("p1"), detail);
  qc.setQueryData(queryKeys.documents.prd("p1"), prd);
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <DocPanel projectId="p1" />
    </QueryClientProvider>,
  );
}

/** 交付前口径：当前段非终段（stages 空 = current 缺席，journey 推导不判终态）。 */
const activeDetail: ProjectDetail = {
  id: "p1",
  name: "宠物医院预约官网",
  stage: "REQUIREMENT",
  stageLabel: "需求梳理",
  stages: [],
  gate: null,
};

/** 已交付口径：当前段命中终段（terminal）。 */
const deliveredDetail: ProjectDetail = {
  ...activeDetail,
  stage: "CLOSED",
  stages: [{ name: "CLOSED", label: "交付", terminal: true }],
};

const prdData = {
  projectId: "p1",
  content: "# 宠物医院预约官网 PRD\n\n## 功能清单\n\n- 在线预约挂号\n- 医生排班查询\n",
  updatedAt: "2026-08-26T02:15:33Z",
};

describe("DocPanel · PRD 数据态（issue #54）", () => {
  it("PRD 产出：渲染 markdown 内容 + 标出更新时间", () => {
    const html = render(activeDetail, prdData);
    // markdown 结构化渲染（标题 / 列表），非原文转写
    expect(html).toContain("宠物医院预约官网 PRD");
    expect(html).toContain("在线预约挂号");
    expect(html).toContain("更新于");
    // 占位口径不再出现
    expect(html).not.toContain("会出现在这里");
  });

  it("PRD 未产出（null）：维持引导占位口径", () => {
    const html = render(activeDetail, null);
    expect(html).toContain("整理好的 PRD 会出现在这里，确认后才开始制作");
    expect(html).not.toContain("更新于");
  });

  it("已交付：交付说明 + 源码包分支不变（无回归）", () => {
    const html = render(deliveredDetail, prdData);
    expect(html).toContain("交付说明");
    expect(html).toContain("下载源码包");
    // PRD 内容不进交付分支
    expect(html).not.toContain("在线预约挂号");
  });
});
