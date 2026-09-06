import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { SidebarProvider } from "@/components/ui/sidebar";

import { ProjectPageShell } from "./project-page-shell";

// 项目页壳（#79 对话主角式定稿）：对话居中 + 呼出式成果区（resizable 双槽、
// 收起态顶栏出「成果」键）+ <lg 双页签退化。只断言结构，栏宽 / 槽位内容归场景插槽。
describe("ProjectPageShell", () => {
  it("成果区滑出（outputsOpen）：resizable 双槽——对话列 + 成果列都在", () => {
    const html = renderToStaticMarkup(
      <SidebarProvider>
        <ProjectPageShell
          header={<span>项目甲</span>}
          chat={<span>对话区</span>}
          outputs={<span>成果区</span>}
          outputsOpen
          onOutputsOpen={() => {}}
          mobileTabs={["对话", "成果"]}
        />
      </SidebarProvider>,
    );

    // 双槽：resizable 面板组恰两个 panel（对话列 / 成果列）+ 拖拽分隔条
    expect(html).toContain('data-slot="resizable-panel-group"');
    expect(html.match(/data-slot="resizable-panel"/g)).toHaveLength(2);
    expect(html).toContain('data-slot="resizable-handle"');
    // 滑出态不出「成果」呼出键
    expect(html).not.toContain('aria-label="展开成果区"');
    // <lg 退化双页签 + 两槽内容都在
    expect(html).toContain("对话区");
    expect(html).toContain("成果区");
  });

  it("成果区收起（outputsOpen=false）：单槽满宽 + 顶栏出「成果」呼出键", () => {
    const html = renderToStaticMarkup(
      <SidebarProvider>
        <ProjectPageShell
          header={<span>项目甲</span>}
          chat={<span>对话区</span>}
          outputs={<span>成果区</span>}
          outputsOpen={false}
          onOutputsOpen={() => {}}
          mobileTabs={["对话", "成果"]}
        />
      </SidebarProvider>,
    );

    // lg 单槽（对话列独占，无分隔条）
    expect(html.match(/data-slot="resizable-panel"/g)).toHaveLength(1);
    expect(html).not.toContain('data-slot="resizable-handle"');
    expect(html).toContain("对话区");
    // 顶栏「成果」呼出键（收起态唯一入口）
    expect(html).toContain('aria-label="展开成果区"');
  });

  it("闲聊期（outputs 缺省）：单槽满宽、无 resizable / 页签 / 呼出键（#19 尚无产物）", () => {
    const html = renderToStaticMarkup(
      <SidebarProvider>
        <ProjectPageShell
          header={<span>项目甲</span>}
          chat={<span>对话区</span>}
          mobileTabs={["对话"]}
        />
      </SidebarProvider>,
    );

    expect(html).not.toContain('data-slot="resizable-panel-group"');
    expect(html).not.toContain('data-slot="tabs-trigger"');
    expect(html).not.toContain('aria-label="展开成果区"');
    expect(html).toContain("对话区");
  });
});
