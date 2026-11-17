import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { ForkCard } from "./fork-card";

// 请求面 mock 掉（无 QueryClient 可挂——呈现面断言归本文件，点火动作归
// interaction 测试）
vi.mock("@/hooks/use-resume-generation", () => ({
  useResumeGeneration: () => ({ mutate: vi.fn(), isPending: false }),
}));

function render(onSeePrd?: () => void) {
  return renderToStaticMarkup(<ForkCard projectId="p1" onSeePrd={onSeePrd} />);
}

/**
 * 岔路口三选卡（#542 访谈②，ADR-0048/0049）：自然收口后的首建点火卡——三选
 * 呈现（两个付费选项＝#544 引导流落位前的占位禁用态）、「立即生成」与「查看
 * 需求文档」入口在场。呈现条件本身归 GET /build-gate 读面（服务端单源，
 * command-area 消费——本文件测卡内容）。
 */
describe("ForkCard · 岔路口三选卡（#542）", () => {
  it("出三选：两个付费选项（占位禁用）＋立即生成（可点火）", () => {
    const html = render();

    // 三选齐在场（AC：试用态自然收口出三选卡；文案随 #515 决议初稿）
    expect(html).toContain("继续讨论（由我的账户付费）");
    expect(html).toContain("继续讨论（由本项目付费）");
    expect(html).toContain("立即生成");
    expect(html).toContain("按当前 PRD 立即生成系统先体验，想修改再付费继续");
    // 付费两选＝#544 引导流落位前的占位态（aria-disabled，无点火动作）
    expect(html).toContain('data-fork-choice="继续讨论（由我的账户付费）" aria-disabled="true"');
    expect(html).toContain('data-fork-choice="继续讨论（由本项目付费）" aria-disabled="true"');
    expect(html).toContain("付费续用引导即将上线");
    // 立即生成＝可点火动作（无占位禁用）
    expect(html).toContain('data-fork-choice="立即生成"');
    expect(html).not.toContain('data-fork-choice="立即生成" aria-disabled');
    expect(html).toContain("data-fork-generate");
  });

  it("PRD 可先回看（卡面带查看入口；无回调不渲染）", () => {
    const html = render(() => {});
    expect(html).toContain("查看 PRD");

    const bare = renderToStaticMarkup(<ForkCard projectId="p1" />);
    expect(bare).not.toContain("查看 PRD");
  });
});
