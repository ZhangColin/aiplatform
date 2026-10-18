import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { components } from "@/lib/api/schema";

import { PrdPanel } from "./prd-panel";

type PrdReadiness = components["schemas"]["PrdReadinessResponse"];

/**
 * PRD 文档面（#346，spec #320 ⑥ 需求分析页右栏，照原型三新件）：文档头
 * （题块＋ISO 行＋就绪徽标行）＋锚点导航（## 章成 pill，序号＋章名）＋
 * 结构化分节（节自带锚 id）＋ SPEC_PARSER 审计条（静态照原型，票注明示）。
 * 就绪徽标＝#331 维度态投影：核心功能已收敛（无阻碍）／N 项次要指标待确认
 * ／N 项核心流程待补齐。
 */

const SEVEN_CHAPTER_MD = [
  "# 智能企业客户预约与工单调度系统 PRD",
  "",
  "## 需求背景",
  "背景正文。",
  "## 目标用户",
  "用户正文。",
  "## 验收与待确认",
  "暂无。",
].join("\n");

function renderPanel({
  content = SEVEN_CHAPTER_MD,
  readiness,
}: {
  content?: string;
  readiness?: PrdReadiness;
} = {}) {
  return renderToStaticMarkup(<PrdPanel content={content} readiness={readiness} />);
}

describe("PrdPanel · 文档头（照原型）", () => {
  it("题块＋ISO 行逐字（结构化需求规格说明书 (PRD)／ISO/IEC/IEEE 29148:2018）", () => {
    const html = renderPanel();
    expect(html).toContain("结构化需求规格说明书 (PRD)");
    expect(html).toContain("ISO/IEC/IEEE 29148:2018");
  });

  it("无阻碍无缺项：出「核心功能已收敛」徽标（原型逐字）", () => {
    const html = renderPanel({ readiness: { blocking: [], minor: [], dimensions: [] } });
    expect(html).toContain("核心功能已收敛");
  });

  it("次要待定项在场：出「N项次要指标待确认」（原型逐字）；阻碍在场：出「N项核心流程待补齐」", () => {
    const html = renderPanel({
      readiness: {
        blocking: [{ dimension: "主流程", text: "发货方式未确认" }],
        minor: [
          { dimension: "验收", text: "排序偏好未确认" },
          { dimension: "验收", text: "通知渠道未确认" },
        ],
        dimensions: [],
      },
    });
    expect(html).toContain("2项次要指标待确认");
    expect(html).toContain("1项核心流程待补齐");
    expect(html).not.toContain("核心功能已收敛");
  });
});

describe("PrdPanel · 锚点导航与结构化分节", () => {
  it("## 章成锚点 pill（序号＋章名、href 指节锚），节体带锚 id", () => {
    const html = renderPanel();
    expect(html).toContain('href="#prd-sec-1"');
    expect(html).toContain("1. 需求背景");
    expect(html).toContain("3. 验收与待确认");
    expect(html).toContain('id="prd-sec-1"');
    expect(html).toContain('id="prd-sec-3"');
    // 节体正文在
    expect(html).toContain("背景正文。");
  });

  it("无 ## 章头：锚点导航不渲染、正文整体直渲", () => {
    const html = renderPanel({ content: "一段没有章节的需求描述。" });
    expect(html).not.toContain('href="#prd-sec-');
    expect(html).toContain("一段没有章节的需求描述。");
  });
});

describe("PrdPanel · SPEC_PARSER 审计条（静态照原型，票注明示）", () => {
  it("审计条文案逐字（SPEC_PARSER: ACTIVE／收敛规则数／基线语义哈希／已准备生成下一阶段工程原语／核对规则表）", () => {
    const html = renderPanel();
    expect(html).toContain("SPEC_PARSER: ACTIVE");
    expect(html).toContain("收敛规则数");
    expect(html).toContain("基线语义哈希");
    expect(html).toContain("已准备生成下一阶段工程原语");
    expect(html).toContain("核对规则表");
  });
});
