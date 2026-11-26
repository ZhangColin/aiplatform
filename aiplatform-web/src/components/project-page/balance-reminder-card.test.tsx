import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { ChatMessage } from "@/lib/store/chat";

import { BalanceReminderCard } from "./balance-reminder-card";

type BalanceReminderMessage = Extract<ChatMessage, { kind: "balance-reminder" }>;

// 请求面 mock 掉（无 QueryClient 可挂——呈现面断言归本文件；充值出口归 checkout
// store 既有测试面）
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: undefined }), // 未登录面（充值先经 M01 登录引导）
}));

function message(card: Partial<BalanceReminderMessage["card"]> = {}): BalanceReminderMessage {
  return {
    kind: "balance-reminder",
    id: "b1",
    card: {
      holdId: "bh-1",
      lane: "OPINION",
      prompt: "把报表加个导出",
      sourcePoolBalance: -500,
      ...card,
    },
  };
}

function render(msg: BalanceReminderMessage) {
  return renderToStaticMarkup(<BalanceReminderCard projectId="p1" message={msg} />);
}

describe("BalanceReminderCard · 余额提醒卡（#537 计费③段间硬门）", () => {
  it("有留存卡：显欠额形（-¥5.00）＋引用被拦原文＋充值引导＋到账自动继续文案＋可关闭", () => {
    const html = render(message());

    expect(html).toContain("源池余额不足，处理已暂停");
    expect(html).toContain("-¥5.00"); // 负额显欠费形（分→元）
    expect(html).toContain("已欠费");
    expect(html).toContain("把报表加个导出"); // 被拦原文随卡引用（不重建用户气泡）
    expect(html).toContain("被拦下的修改意见");
    expect(html).toContain("自动继续，无需重新发送");
    expect(html).toContain("data-balance-reminder-recharge");
    expect(html).toContain("去充值");
    expect(html).toContain("data-balance-reminder-dismiss");
  });

  it("run 场卡（无留存无原文）：只报暂停事实——被暂停的系统任务文案、无引用块", () => {
    const html = render(message({ holdId: undefined, lane: undefined, prompt: undefined }));

    expect(html).toContain("被暂停的系统任务");
    expect(html).not.toContain("blockquote"); // 无原文不呈现引用块
    expect(html).toContain("data-balance-reminder-recharge"); // 充值引导照常在
  });

  it("车道文案分岔：咨询称「咨询」、意见称「修改意见」", () => {
    expect(render(message({ lane: "INQUIRY", prompt: "地址是什么" })))
      .toContain("被拦下的咨询");
    expect(render(message({ lane: "OPINION", prompt: "改大气些" })))
      .toContain("被拦下的修改意见");
  });
});
