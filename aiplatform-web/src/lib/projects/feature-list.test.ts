import { describe, expect, it } from "vitest";

import { parseFeatureListItems } from "@/lib/projects/feature-list";

/** 功能清单条目解析（#285 切换控件的作用域勾选源）：PRD 是模型独笔 markdown，按章标题锚定、编号条目取全行。 */
describe("parseFeatureListItems", () => {
  const PRD = [
    "# 品牌官网 PRD",
    "",
    "## 需求背景",
    "面向宠物店的一站式官网。",
    "",
    "## 功能清单",
    "1. 首页：展示产品与入口，含轮播（验收：三屏可切换）",
    "2. 订单管理：下单与查看订单（验收：下单后可在列表看到记录）",
    "- 非编号行不计",
    "3、预约表单：留资提交",
    "",
    "## 待定项",
    "暂无",
  ].join("\n");

  it("解析功能清单章的编号条目（含中文顿号编号），越过非编号行", () => {
    expect(parseFeatureListItems(PRD)).toEqual([
      "首页：展示产品与入口，含轮播（验收：三屏可切换）",
      "订单管理：下单与查看订单（验收：下单后可在列表看到记录）",
      "预约表单：留资提交",
    ]);
  });

  it("章节边界＝下一个标题：待定项章里的编号行不进清单", () => {
    const prd = PRD.replace("暂无", "1. 待定条目不该出现");
    expect(parseFeatureListItems(prd)).toHaveLength(3);
  });

  it("无 PRD / 无功能清单章 / 空章 → 空数组（勾选态隐藏、全部页面兜底）", () => {
    expect(parseFeatureListItems(null)).toEqual([]);
    expect(parseFeatureListItems("")).toEqual([]);
    expect(parseFeatureListItems("# PRD\n\n## 需求背景\n只有背景。")).toEqual([]);
    expect(parseFeatureListItems("## 功能清单\n本章还没有条目。")).toEqual([]);
  });

  it("设计形 PRD（设计物清单章）不产出功能清单条目", () => {
    const designPrd = "## 设计物清单\n1. 主视觉海报：用途…";
    expect(parseFeatureListItems(designPrd)).toEqual([]);
  });
});
