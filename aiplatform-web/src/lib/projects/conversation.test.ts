import { describe, expect, it } from "vitest";

import type { ConversationEntryResponse } from "./conversation";
import { toHydratedEntries } from "./conversation";

/**
 * 对话史读口转换（#89 水合载荷）：回归锚 2026-09-10「回访对话区空白」——后端
 * Long id 经全局 Long→String 序列化为 JSON 字符串（"146"，防 JS 精度丢失），
 * 旧守卫 `typeof entry.id === "number"` 恒 false 把全部条目丢弃 → 水合恒空。
 * 本文件 fixture 用真实接口形状（id 为 string，自 8888 实测抓取）锁定契约。
 */
describe("toHydratedEntries · id 序列化为 string（2026-09-10 回归锚）", () => {
  it("id 为字符串（真实后端形状）时条目保留、kind 正确收窄", () => {
    const raw = [
      {
        id: "146",
        kind: 1,
        runId: "355998830671363001",
        text: "帮我做一个个人用的 TodoList",
        question: null,
        closing: null,
        attachments: null,
        answered: false,
        at: "2026-09-09T16:52:15.372429",
      },
      {
        id: "147",
        kind: 2,
        runId: "355998830671363001",
        text: "欢迎！收到你的想法——",
        question: null,
        closing: null,
        attachments: null,
        answered: false,
        at: "2026-09-09T16:52:21.861053",
      },
    ] as unknown as ConversationEntryResponse[];

    const hydrated = toHydratedEntries(raw);

    expect(hydrated).toHaveLength(2);
    expect(hydrated[0]).toMatchObject({ id: "146", kind: "user" });
    expect(hydrated[1]).toMatchObject({ id: "147", kind: "agent" });
  });

  it("未知 kind code 条目弃守（契约演进容错面不变）", () => {
    const raw: ConversationEntryResponse[] = [
      { id: "999", kind: 42, runId: "r1", answered: false },
    ];

    expect(toHydratedEntries(raw)).toHaveLength(0);
  });

  it("报价卡 kind=7 收窄为 quote，载荷（事件 + 订单引用）透传（#203）", () => {
    const raw = [
      {
        id: "200",
        kind: 7,
        kindName: "报价卡",
        runId: null,
        text: null,
        question: null,
        closing: null,
        attachments: null,
        quote: { orderId: "901", event: "quoted" },
        answered: false,
        at: "2026-09-17T10:00:00.000000",
      },
    ] as unknown as ConversationEntryResponse[];

    const hydrated = toHydratedEntries(raw);

    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]).toMatchObject({
      id: "200",
      kind: "quote",
      quote: { orderId: "901", event: "quoted" },
    });
  });
});
