import { describe, expect, it } from "vitest";

import { splitPrdSections } from "@/lib/projects/prd-sections";

/**
 * PRD 锚点分节提取（#346，spec #320 ⑥ 需求分析页右栏「锚点导航＋结构化
 * 分节」）：PRD 正文＝主智能体按固定七章节模板写出的 markdown（prd-writing
 * 技能），节界＝`## ` 二级章头（### 子节不独立成锚）。返回值供锚点导航
 * （序号＋章名 pill）与分节渲染（每节自带锚 id）同源消费。
 */
describe("splitPrdSections（## 章头分节）", () => {
  it("标准七章节 markdown：逐章成节、序号依序、首章前内容归 preamble", () => {
    const md = [
      "# 宠物医院预约管理系统 PRD",
      "",
      "导言段。",
      "",
      "## 需求背景",
      "背景正文。",
      "## 目标用户",
      "用户正文。",
      "### 子节不独立成锚",
      "子节内容随母节。",
      "## 验收与待确认",
      "暂无。",
    ].join("\n");

    const { preamble, sections } = splitPrdSections(md);

    expect(preamble).toBe("# 宠物医院预约管理系统 PRD\n\n导言段。");
    expect(sections.map((s) => [s.id, s.title])).toEqual([
      ["prd-sec-1", "需求背景"],
      ["prd-sec-2", "目标用户"],
      ["prd-sec-3", "验收与待确认"],
    ]);
    // 节体含其下子节原文（### 不拆出）
    expect(sections[1].body).toBe("用户正文。\n### 子节不独立成锚\n子节内容随母节。");
    expect(sections[2].body).toBe("暂无。");
  });

  it("无 ## 章头：整体归 preamble、节表为空（锚点导航不渲染）", () => {
    const { preamble, sections } = splitPrdSections("# 只有标题\n\n一段正文。");
    expect(preamble).toBe("# 只有标题\n\n一段正文。");
    expect(sections).toEqual([]);
  });

  it("## 在代码围栏内不算章头（防误切）", () => {
    const md = ["## 需求背景", "```", "## 假章头", "```", "正文。"].join("\n");
    const { sections } = splitPrdSections(md);
    expect(sections.map((s) => s.title)).toEqual(["需求背景"]);
    expect(sections[0].body).toBe("```\n## 假章头\n```\n正文。");
  });

  it("空串/空白：preamble 为空、节表为空", () => {
    expect(splitPrdSections("")).toEqual({ preamble: "", sections: [] });
    expect(splitPrdSections("   \n  ")).toEqual({ preamble: "", sections: [] });
  });
});
