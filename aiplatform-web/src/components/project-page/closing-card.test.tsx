import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { WorkClosing } from "@/lib/store/work-message";

import { ClosingCard } from "./closing-card";

function closing(overrides: Partial<WorkClosing> = {}): WorkClosing {
  return {
    summary: "修订了需求文档，并更新了系统",
    prdChanged: true,
    prdNote: "配送范围改为全国",
    systemChanged: true,
    systemNote: "下单页新增配送范围说明",
    files: [
      { path: "/src/App.jsx", added: 4, removed: 0 },
      { path: "/src/pages/Orders.jsx", added: 12, removed: 3 },
    ],
    durationMs: 183_420,
    ...overrides,
  };
}

/**
 * 收尾卡四要素（#88 AC①）：摘要 / 判定行（服务端权威值直译，非前端推导）/
 * 变更清单（文件级 +N −M）/ 轮末统计（时长 + 文件数 + 变更行数 + 检查通过）
 * ——SSR 断言（形态轻、事件→状态归桥接测试，Testing Decisions 口径）。
 */
describe("ClosingCard · 四要素（#88 定格收口）", () => {
  it("四要素齐：摘要、判定行（带原因）、变更清单、轮末统计", () => {
    const html = renderToStaticMarkup(<ClosingCard closing={closing()} />);

    expect(html).toContain("本轮完成");
    expect(html).toContain("修订了需求文档，并更新了系统"); // 摘要（合并叙事）
    expect(html).toContain("需求文档：");
    expect(html).toContain("已修订");
    expect(html).toContain("配送范围改为全国"); // 判定行·PRD 原因（权威值）
    expect(html).toContain("系统：");
    expect(html).toContain("已更新");
    expect(html).toContain("下单页新增配送范围说明"); // 判定行·系统原因
    expect(html).toContain("/src/pages/Orders.jsx"); // 变更清单
    expect(html).toContain("+12");
    expect(html).toContain("−3");
    expect(html).toContain("2"); // 文件数（无独立断言面，随清单断言兜住）
    expect(html).toContain("3 分 03 秒"); // 轮末统计·时长（183420ms）
    expect(html).toContain("+16"); // 变更行数（4 + 12）
    expect(html).toContain("检查通过"); // 自检终值计入统计（closing ⟺ 核验通过）
  });

  it("系统无需改动轮：判定行如实呈现原因、空清单不出清单区与文件统计", () => {
    const html = renderToStaticMarkup(
      <ClosingCard
        closing={closing({
          summary: "本轮系统无需改动",
          prdChanged: false,
          prdNote: undefined,
          systemChanged: false,
          systemNote: "页面上没有写死配送范围，都以文档为准",
          files: [],
        })}
      />,
    );

    expect(html).toContain("本轮系统无需改动");
    expect(html).toContain("未修订");
    expect(html).toContain("无需改动");
    expect(html).toContain("页面上没有写死配送范围，都以文档为准");
    expect(html).not.toContain("个文件");
    expect(html).not.toContain("查看全部");
    expect(html).toContain("用时"); // 时长统计恒在
  });

  it("生成轮：摘要「首次生成了系统」、判定行无原因注脚（说明缺省）", () => {
    const html = renderToStaticMarkup(
      <ClosingCard
        closing={closing({
          summary: "首次生成了系统",
          prdChanged: false,
          prdNote: undefined,
          systemChanged: true,
          systemNote: undefined,
          files: [{ path: "/src/App.jsx", added: 40, removed: 0 }],
        })}
      />,
    );

    expect(html).toContain("首次生成了系统");
    expect(html).toContain("已更新");
    expect(html).toContain("+40");
  });

  it("长清单默认收五条，「查看全部 N 个文件」收进折叠", () => {
    const files = Array.from({ length: 7 }, (_, index) => ({
      path: `/src/File${index + 1}.jsx`,
      added: 1,
      removed: 0,
    }));
    const html = renderToStaticMarkup(<ClosingCard closing={closing({ files })} />);

    expect(html).toContain("/src/File5.jsx");
    expect(html).not.toContain("/src/File6.jsx"); // 五条之外收进折叠（SSR 默认收起）
    expect(html).toContain("查看全部 7 个文件");
  });
});
