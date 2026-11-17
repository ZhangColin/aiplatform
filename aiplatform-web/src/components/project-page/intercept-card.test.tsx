import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ChatMessage } from "@/lib/store/chat";

import { InterceptCard } from "./intercept-card";

type InterceptMessage = Extract<ChatMessage, { kind: "intercept" }>;

// 请求面 mock 掉（无 QueryClient 可挂——呈现面断言归本文件，动作路由归 hooks 测试）
vi.mock("@/hooks/use-intercept", () => ({
  useConfirmIntercept: () => ({ isPending: false, mutate: vi.fn() }),
}));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: undefined }), // 未登录面（「我的账户」呈现需先登录提示）
}));

function message(overrides: Partial<InterceptMessage> = {}): InterceptMessage {
  return {
    kind: "intercept",
    id: "i1",
    settled: false,
    card: {
      interceptId: "ic-1",
      lane: "OPINION",
      prompt: "把主色调改成绿色",
      sourceSelected: false,
    },
    ...overrides,
  };
}

function render(card: InterceptMessage) {
  return renderToStaticMarkup(<InterceptCard projectId="p1" message={card} />);
}

/** 确认钮的标签串（属性序不固定——disabled 可能在 data-intercept-confirm 之前）。 */
function confirmTag(html: string): string {
  const match = html.match(/<button[^>]*data-intercept-confirm="true"[^>]*>/);
  expect(match, "确认钮在场").toBeTruthy();
  return match?.[0] ?? "";
}

describe("InterceptCard · 试用拦截卡（#533 计费②门②）", () => {
  it("源未定：卡内引用原文＋随卡首次选源两项＋未选源确认禁用＋充值引导出口", () => {
    const html = render(message());

    expect(html).toContain("继续迭代需确认付费");
    expect(html).toContain("把主色调改成绿色"); // 被拦原文随卡引用（不重建用户气泡）
    expect(html).toContain("首次付费请选择");
    expect(html).toContain("本项目余额");
    expect(html).toContain("我的账户");
    expect(html).toContain("需先登录"); // 未登录面提示（访客选账户源的先导指引）
    expect(html).toContain("先去充值");
    expect(html).toContain("data-intercept-confirm");
    expect(confirmTag(html)).toMatch(/\sdisabled[\s=/>]/); // 未选源不可确认（属性形——类名 disabled: 变体前缀不算）
  });

  it("源已选定（后台已充项目池＝免登录直确认）：显源池与余额、无选源区、确认可用", () => {
    const html = render(message({
      card: {
        interceptId: "ic-1",
        lane: "OPINION",
        prompt: "把字号调大",
        sourceSelected: true,
        deductionSource: 2,
        sourcePoolBalance: 10000,
      },
    }));

    expect(html).toContain("本项目余额");
    expect(html).toContain("¥100.00"); // 余额快照（分→元）随卡呈现
    expect(html).not.toContain("首次付费请选择"); // 选定后不呈现选源区
    expect(confirmTag(html)).not.toMatch(/\sdisabled[\s=/>]/); // 直接确认可用（免登录动线）
  });

  it("确认落定：卡转只读回执——操作区退场、受理事实呈现", () => {
    const html = render(message({ settled: true }));

    expect(html).toContain("已确认扣减");
    expect(html).toContain("已进入处理队列");
    expect(html).not.toContain("data-intercept-confirm"); // 确认钮退场
    expect(html).not.toContain("先去充值");
    expect(html).toContain("data-intercept-dismiss"); // 回执可关闭
  });

  it("兜底车道文案：引用称「这条消息」（意见称「你的修改意见」）", () => {
    const html = render(message({
      card: {
        interceptId: "ic-1",
        lane: "FALLBACK",
        prompt: "你好呀",
        sourceSelected: false,
      },
    }));

    expect(html).toContain("这条消息");
    expect(html).not.toContain("你的修改意见");
  });
});
