import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import { ForkCard } from "./fork-card";

// 请求面 mock 掉（无 QueryClient 可挂——呈现面断言归本文件，引导流编排归
// interaction 测试）
vi.mock("@/hooks/use-start-generation", () => ({
  useStartGeneration: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/hooks/use-continue-discussion", () => ({
  useContinueDiscussion: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: null }),
}));

function render(onSeePrd?: () => void) {
  return renderToStaticMarkup(<ForkCard projectId="p1" onSeePrd={onSeePrd} />);
}

/**
 * 岔路口三选卡（#542 访谈②＋#544 访谈③引导流落位，ADR-0048/0049）：自然收口后
 * 的首建点火卡——三选齐在场：「继续讨论（由我的账户付费）」／「继续讨论（由本
 * 项目付费）」＝付费轨引导入口（#544 起可点火——M01 登录＋按池判定＋到账翻转）；
 * 「立即生成」＝触发首个构建派发。呈现条件本身归 GET /build-gate 读面（服务端
 * 单源，command-area 消费——本文件测卡内容）。
 */
describe("ForkCard · 岔路口三选卡（#542/#544）", () => {
  it("出三选：两个付费引导入口（可点火）＋立即生成（可点火）", () => {
    const html = render();

    // 三选齐在场（AC：试用态自然收口出三选卡；文案随 #515 决议初稿）
    expect(html).toContain("继续讨论（由我的账户付费）");
    expect(html).toContain("继续讨论（由本项目付费）");
    expect(html).toContain("立即生成");
    expect(html).toContain("按当前 PRD 立即生成系统先体验，想修改再付费继续");
    // 付费两选＝#544 引导流入口（可点火、无占位禁用文案）
    expect(html).toContain('data-testid="fork-continue-account"');
    expect(html).toContain('data-testid="fork-continue-project"');
    expect(html).not.toContain("付费续用引导即将上线");
    expect(html).not.toContain("aria-disabled");
    // 立即生成＝可点火动作
    expect(html).toContain('data-fork-choice="立即生成"');
    expect(html).toContain("data-fork-generate");
  });

  it("PRD 可先回看（卡面带查看入口；无回调不渲染）", () => {
    const html = render(() => {});
    expect(html).toContain("查看 PRD");

    const bare = renderToStaticMarkup(<ForkCard projectId="p1" />);
    expect(bare).not.toContain("查看 PRD");
  });
});
