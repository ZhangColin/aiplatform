import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { WorkflowPipeline } from "./workflow-pipeline";

/**
 * 流程画板渲染契约（#345，spec #320 ⑥ 首页 P01 照原型）：PHASE 01/02 两段九步 +
 * 步内指示 + 锁版里程碑 + 保障条，话术逐字照原型。
 */
describe("WorkflowPipeline（首页流程画板·文案逐字照原型）", () => {
  const html = renderToStaticMarkup(<WorkflowPipeline />);

  it("区块头逐字：Operational Workflow / 主标 / 副标 / 两枚标签", () => {
    expect(html).toContain("Operational Workflow");
    expect(html).toContain("从需求对话到可交互 Demo，再衔接签约与生产");
    expect(html).toContain("全流程严谨闭环 · 验证认可后付款开工 · 零盲盒交付机制");
    expect(html).toContain("契约驱动");
    expect(html).toContain("1:1高保真Demo");
  });

  it("PHASE 01：两段标题 + 步骤 01–05 逐字", () => {
    expect(html).toContain("PHASE 01");
    expect(html).toContain("阶段一：语义探索与可交互验证");
    expect(html).toContain("纯自然语言对齐 · 毫秒级生成验证");
    expect(html).toContain("需求输入");
    expect(html).toContain("文本 / 脑图 / 表格");
    expect(html).toContain("问答澄清");
    expect(html).toContain("启发式补齐逻辑缺口");
    expect(html).toContain("PRD 确认");
    expect(html).toContain("自动化规范文档生成");
    expect(html).toContain("1 个 Demo");
    expect(html).toContain("真实可点击、全交互体验");
    expect(html).toContain("CORE ARTIFACT");
    expect(html).toContain("反馈迭代");
    expect(html).toContain("反馈循环");
    expect(html).toContain("按反馈毫秒更新Demo");
  });

  it("步内指示 + 锁版里程碑 + PHASE 02 逐字", () => {
    expect(html).toContain("输入直达");
    expect(html).toContain("对齐逻辑");
    expect(html).toContain("输出规格");
    expect(html).toContain("← 按反馈重编译 / 满意则进入签约 →");
    expect(html).toContain("认可Demo后锁定版本 · 开启履约通道");
    expect(html).toContain("PHASE 02");
    expect(html).toContain("阶段二：契约锁定与履约保障");
    expect(html).toContain("法务级电子合同 · 资金存管核验");
    expect(html).toContain("确认版本");
    expect(html).toContain("固化PRD与UI交互拓扑");
    expect(html).toContain("合同签署");
    expect(html).toContain("规范权责与生产周期");
    expect(html).toContain("开工款核验");
    expect(html).toContain("对公打款/网银直连对账");
    expect(html).toContain("STEP 09 · FINAL");
    expect(html).toContain("待安排生产");
    expect(html).toContain("生产集群排期接入");
  });

  it("保障条逐字照原型", () => {
    expect(html).toContain("语义引力核心保障机制");
    expect(html).toContain(
      "合同要求的签署完成且开工付款条件满足后，才正式划入生产排期。在此之前，需求沟通、PRD文档梳理以及 1 个高保真 Demo 的迭代验证均无需承担不可预期的盲盒风险。",
    );
  });
});
