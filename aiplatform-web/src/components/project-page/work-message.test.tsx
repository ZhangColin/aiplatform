import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

import { WorkMessage, formatDuration, segmentWorkParts } from "./work-message";

function work(overrides: Partial<WorkSnapshot> = {}): WorkSnapshot {
  return {
    runId: "run-1",
    frozen: false,
    parts: [],
    ...overrides,
  };
}

function action(overrides: Partial<Extract<WorkPart, { kind: "action" }>> = {}) {
  return {
    kind: "action",
    id: "run-1:4",
    toolCallId: "tc-1",
    toolName: "write_file",
    state: "completed",
    label: "编写【订单管理】",
    ...overrides,
  } satisfies Extract<WorkPart, { kind: "action" }>;
}

describe("WorkMessage · 生长中的工作消息（#81：部件结构与状态呈现）", () => {
  it("run 开始即出现：空部件的生长中消息出「正在做」头部与打字点，无部件行", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work()} />);

    expect(html).toContain("正在做");
    expect(html).toContain("正在干活…");
  });

  it("部件序列呈现：解说段 + 动作卡对象短语（无步骤分组、无过程耗时）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "run-1:3", text: "正在编写订单管理页面。" },
            action({ state: "running" }),
          ],
        })}
      />,
    );

    expect(html).toContain("正在编写订单管理页面。");
    expect(html).toContain("编写【订单管理】");
    expect(html).toContain("进行中");
    expect(html).not.toContain("步"); // 无步骤分组头（#115）
  });

  it("动作卡三态：进行中转圈、完成打勾、失败「没做成」——均无时长数字（#115）", () => {
    const running = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "started" })] })} />,
    );
    expect(running).toContain("进行中");
    expect(running).not.toContain("秒");

    const done = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1" })] })} />,
    );
    expect(done).toContain("编写【订单管理】");
    expect(done).not.toContain("秒");

    const failed = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "failed" })] })} />,
    );
    expect(failed).toContain("没做成");
    expect(failed).not.toContain("秒");
  });

  it("收口定格：头部与打字点退场、部件留驻（未终态动作不留时长、不转圈）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          parts: [
            { kind: "text", id: "run-1:2", text: "订单管理完成" },
            action({ id: "a1", toolCallId: "t1", state: "running" }),
          ],
        })}
      />,
    );

    expect(html).not.toContain("正在做");
    expect(html).not.toContain("正在干活…");
    expect(html).toContain("订单管理完成");
    // 未终态动作定格：如实留「进行中」字样、不转圈、不带时长数字
    expect(html).toContain("进行中");
    expect(html).not.toContain("animate-spin");
    expect(html).not.toContain("秒");
  });

  it("定格且无部件（起跑即死）：不渲染空壳", () => {
    expect(renderToStaticMarkup(<WorkMessage work={work({ frozen: true })} />)).toBe("");
  });
});

describe("WorkMessage · 头部标题（#118 切片标题与进度）", () => {
  it("生成轨道切片：头部「{切片标题}（{index}/{total}）」——取代「正在做」内部视角", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ slice: { title: "商品浏览", index: 2, total: 5 } })} />,
    );

    expect(html).toContain("商品浏览（2/5）");
    expect(html).not.toContain("正在做");
  });

  it("阶段 0 / 更新 run：头部只出用户语言标题（无「（n/N）」进度）", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work({ slice: { title: "系统更新" } })} />);

    expect(html).toContain("系统更新");
    expect(html).not.toContain("（");
  });

  it("无 slice（run-start 被淘汰的补建路径 / 主智能体轮）：回落「正在做」", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work()} />);

    expect(html).toContain("正在做");
  });

  it("定格留驻：头部退场（标题是生长指示，收尾卡叙事承接切片标题）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({ frozen: true, slice: { title: "商品浏览", index: 2, total: 5 }, parts: [{ kind: "text", id: "1", text: "写好了。" }] })}
      />,
    );

    expect(html).not.toContain("商品浏览（2/5）");
    expect(html).toContain("写好了。");
  });
});

describe("WorkMessage · 自检播报行（#85：「正在检查系统 → ✅/❌」）", () => {
  function checkPart(overrides: Partial<Extract<WorkPart, { kind: "check" }>> = {}) {
    return {
      kind: "check",
      id: "run-1:8",
      state: "checking",
      ...overrides,
    } satisfies Extract<WorkPart, { kind: "check" }>;
  }

  it("核验中：「正在检查系统」+ 进行中转圈", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [checkPart()] })} />,
    );

    expect(html).toContain("正在检查系统");
    expect(html).toContain("animate-spin"); // Spinner 在转
  });

  it("核验通过 / 未过：原位换 ✅「检查通过」/ ❌「检查未过」，转圈退场", () => {
    const passed = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [checkPart({ state: "passed" })] })} />,
    );
    expect(passed).toContain("检查通过");
    expect(passed).not.toContain("正在检查系统");
    expect(passed).not.toContain("animate-spin");

    const failed = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [checkPart({ state: "failed" })] })} />,
    );
    expect(failed).toContain("检查未过");
    expect(failed).not.toContain("animate-spin");
  });

  it("定格截断的「检查中」（run 未进核验即终态的防御面）：转圈退场、字样如实留驻", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ frozen: true, parts: [checkPart()] })} />,
    );

    expect(html).toContain("正在检查系统");
    expect(html).not.toContain("animate-spin");
  });
});

describe("WorkMessage · 定格收口（#117：原地定格留驻，收尾卡归 chat store 对话流）", () => {
  it("定格空壳（run 零部件的退化态）：不渲染空壳（收尾卡长在对话流）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ frozen: true, parts: [] })} />,
    );

    expect(html).not.toContain("正在做");
    expect(html).not.toContain("编写【订单管理】");
    expect(html).not.toContain("本轮完成"); // 卡本体归 chat store（CommandArea 渲染）
  });

  it("失败定格（run-failed）：流水留驻、不出收尾卡", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ frozen: true, parts: [action()] })} />,
    );

    expect(html).not.toContain("本轮完成");
    expect(html).toContain("编写【订单管理】");
  });
});

describe("收尾卡「用时」格式（用户语言，整秒）", () => {
  it("formatDuration：<60 秒「N 秒」，跨分「M 分 SS 秒」", () => {
    expect(formatDuration(4_300)).toBe("4 秒");
    expect(formatDuration(63_000)).toBe("1 分 03 秒");
    expect(formatDuration(-5_000)).toBe("0 秒"); // 时钟回拨防御
  });
});

describe("segmentWorkParts · 动作组折叠投影（#116：纯呈现聚合，事件模型不动）", () => {
  it("连续动作聚合为一组；非动作部件各自成段、把动作组切开（解说 ↔ 动作组交替）", () => {
    const segments = segmentWorkParts([
      { kind: "text", id: "1", text: "开始写。" },
      action({ id: "2", toolCallId: "t1", label: "编写【A】" }),
      action({ id: "3", toolCallId: "t2", label: "执行【B】" }),
      { kind: "text", id: "4", text: "写好了。" },
      action({ id: "5", toolCallId: "t3", label: "编写【C】" }),
    ]);

    expect(segments.map((s) => s.kind)).toEqual(["single", "actions", "single", "single"]);
    const group = segments[1];
    expect(group.kind).toBe("actions");
    if (group.kind === "actions") {
      expect(group.actions.map((a) => a.toolCallId)).toEqual(["t1", "t2"]);
    }
  });

  it("单动作不聚合（无折叠语义）：回落 single 段", () => {
    const segments = segmentWorkParts([action({ id: "1", toolCallId: "t1" })]);

    expect(segments).toHaveLength(1);
    expect(segments[0].kind).toBe("single");
  });

  it("自检部件同样切开动作组（非动作部件均独段，不参与聚合）", () => {
    const segments = segmentWorkParts([
      action({ id: "1", toolCallId: "t1" }),
      { kind: "check", id: "2", state: "checking" },
      action({ id: "3", toolCallId: "t2" }),
    ]);

    expect(segments.map((s) => s.kind)).toEqual(["single", "single", "single"]);
  });

  it("空部件序列 → 无段", () => {
    expect(segmentWorkParts([])).toEqual([]);
  });
});

describe("WorkMessage · 动作组折叠行（#116）", () => {
  it("连续动作默认折叠为一行（「N 个动作」），单条动作标签不播（控噪）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
            action({ id: "a2", toolCallId: "t2", toolName: "execute", label: "执行【B】" }),
          ],
        })}
      />,
    );

    expect(html).toContain("2 个动作");
    expect(html).not.toContain("编写【A】");
    expect(html).not.toContain("执行【B】");
  });

  it("解说段 ↔ 动作组交替：文本段不参与聚合、照常竖流", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "t1", text: "开始写订单页。" },
            action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
            action({ id: "a2", toolCallId: "t2", label: "执行【B】" }),
            { kind: "text", id: "t2", text: "写好了。" },
          ],
        })}
      />,
    );

    expect(html).toContain("开始写订单页。");
    expect(html).toContain("2 个动作");
    expect(html).toContain("写好了。");
  });

  it("单动作不折叠：直接单行状态卡（无「个动作」）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1" })] })} />,
    );

    expect(html).toContain("编写【订单管理】");
    expect(html).not.toContain("个动作");
  });

  it("自检播报行不受聚合影响：动作组折叠 + 自检行照常竖流", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            action({ id: "a1", toolCallId: "t1", label: "编写【A】" }),
            action({ id: "a2", toolCallId: "t2", label: "执行【B】" }),
            { kind: "check", id: "c1", state: "checking" },
          ],
        })}
      />,
    );

    expect(html).toContain("2 个动作");
    expect(html).toContain("正在检查系统");
  });
});
