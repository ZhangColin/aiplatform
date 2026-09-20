import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { GenerationSegmentFact } from "@/lib/projects/detail";
import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

import {
  WorkMessage,
  formatClock,
  formatDuration,
  planCurrentOrd,
  segmentHasFailure,
  segmentWorkParts,
  splitWorkBody,
} from "./work-message";

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

function segment(overrides: Partial<GenerationSegmentFact> = {}): GenerationSegmentFact {
  return { ord: 1, description: "用户能注册登录", status: "pending", ...overrides };
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

  it("收口定格：打字点退场、部件留驻（未终态动作不留时长、不转圈）；无切片上下文不残留「正在做」", () => {
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

  it("定格留驻：标题与冻结时钟保形（story12 结束瞬间无跳变），脉冲点与打字点退场", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          startedAt: 1_758_000_000_000,
          endedAt: 1_758_000_065_000,
          slice: { title: "商品浏览", index: 2, total: 5 },
          parts: [{ kind: "text", id: "1", text: "写好了。" }],
        })}
      />,
    );

    expect(html).toContain("商品浏览（2/5）");
    expect(html).toContain("已运行 01:05"); // 时钟定格在收口信封 ts（#225）
    expect(html).not.toContain("animate-ping"); // 脉冲点退场
    expect(html).not.toContain("正在干活…");
    expect(html).toContain("写好了。");
  });
});

describe("WorkMessage · run 级时钟（#225，ADR-0010 窄修订）", () => {
  it("生长中：startedAt 落锚即渲染「已运行 mm:ss」；缺锚不渲染（不伪造起点）", () => {
    const withAnchor = renderToStaticMarkup(
      <WorkMessage work={work({ startedAt: Date.now() - 65_000 })} />,
    );
    expect(withAnchor).toContain("已运行 01:0");

    const noAnchor = renderToStaticMarkup(<WorkMessage work={work()} />);
    expect(noAnchor).not.toContain("已运行");
  });

  it("formatClock：mm:ss 整分补零；负值防御归零", () => {
    expect(formatClock(0)).toBe("00:00");
    expect(formatClock(5_000)).toBe("00:05");
    expect(formatClock(65_000)).toBe("01:05");
    expect(formatClock(3_840_000)).toBe("64:00");
    expect(formatClock(-3_000)).toBe("00:00");
  });
});

describe("WorkMessage · 计划区（#225：轨道片清单常驻，run-start 切片序驱动当前片）", () => {
  const plan: GenerationSegmentFact[] = [
    segment({ ord: 0, description: "系统初始化", status: "closed" }),
    segment({ ord: 1, description: "用户能注册登录", status: "closed" }),
    segment({ ord: 2, description: "用户能下单支付", status: "pending" }),
    segment({ ord: 3, description: "用户能查看订单", status: "failed" }),
  ];

  it("生长中切片 run：清单常驻（阶段 0＋切片），当前片高亮由 slice.index 驱动（不查表猜在途）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ slice: { title: "用户能下单支付", index: 2, total: 3 } })} plan={plan} />,
    );

    for (const description of ["系统初始化", "用户能注册登录", "用户能下单支付", "用户能查看订单"]) {
      expect(html).toContain(description);
    }
    expect(html).toContain("进行中"); // ● 当前片行（高亮）+ 当前动作行状态字同源
    expect(html.match(/进行中/g)?.length).toBeGreaterThanOrEqual(1);
  });

  it("阶段 0 run（titled 无序号）：标题与 ord 0 片行同源同串 → 计划区以序 0 为当前片", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ slice: { title: "系统初始化" } })} plan={plan} />,
    );

    expect(html).toContain("系统初始化");
    expect(planCurrentOrd({ title: "系统初始化" }, plan)).toBe(0);
  });

  it("更新 run / 零散维护（无切片上下文）：不渲染计划区、不伪造计划（story13）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ slice: { title: "系统更新" } })} plan={plan} />,
    );

    expect(html).not.toContain("用户能注册登录"); // 计划行不出场
    expect(html).toContain("系统更新"); // 头部照常
  });

  it("无现行计划（null）：不渲染计划区", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ slice: { title: "用户能下单支付", index: 2, total: 3 } })} plan={null} />,
    );

    expect(html).not.toContain("系统初始化");
  });

  it("定格：计划区保形（状态归 REST 事实），当前片高亮退场", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          slice: { title: "用户能下单支付", index: 2, total: 3 },
          parts: [{ kind: "text", id: "1", text: "写好了。" }],
        })}
        plan={plan}
      />,
    );

    expect(html).toContain("用户能注册登录");
    expect(html).not.toContain("●"); // ● 是生长指示，收口归状态标
  });

  it("planCurrentOrd：切片序直取（1-based = 片序）；无序号只认阶段 0 同串；无计划 undefined", () => {
    expect(planCurrentOrd({ title: "用户能下单支付", index: 2, total: 3 }, plan)).toBe(2);
    expect(planCurrentOrd({ title: "不匹配的标题" }, plan)).toBeUndefined();
    expect(planCurrentOrd({ title: "系统初始化" }, null)).toBeUndefined();
    expect(planCurrentOrd(undefined, plan)).toBeUndefined();
    expect(planCurrentOrd({ title: "系统初始化" }, [])).toBeUndefined();
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

describe("splitWorkBody · 混合坍缩投影（#225：尾部活动区常驻，更早坍缩）", () => {
  function segments(count: number): WorkPart[] {
    return Array.from({ length: count }, (_, i) =>
      i % 3 === 0
        ? ({ kind: "text", id: `t${i}`, text: `解说${i}。` } as WorkPart)
        : action({ id: `a${i}`, toolCallId: `tc${i}`, label: `动作${i}` }),
    );
  }

  it("长流水：更早段坍缩（计数 = 非失败段数）、尾部 = 最近动作承载段 + ≤2 句前展解说", () => {
    const projected = segmentWorkParts(segments(20));
    const body = splitWorkBody(projected);

    expect(body.collapsedCount).toBeGreaterThan(0);
    expect(body.earlier.filter(segmentHasFailure)).toEqual([]);
    // 尾部有界：动作承载段（或其组）+ ≤2 前展解说 + ≤2 其后解说/自检
    expect(body.tail.length).toBeLessThanOrEqual(5);
    const anchor = body.tail.findIndex(
      (s) => s.kind === "actions" || (s.kind === "single" && s.part.kind === "action"),
    );
    expect(anchor).toBeGreaterThanOrEqual(0); // 尾部含最近动作组/动作行
    // 分区无损：更早区 + 尾部 = 总段数（事件不裁剪、仅呈现坍缩）
    expect(body.earlier.length + body.tail.length).toBe(projected.length);
  });

  it("纯解说（无动作）：尾部取最后两段，其余坍缩", () => {
    const parts: WorkPart[] = Array.from({ length: 5 }, (_, i) => ({
      kind: "text",
      id: `t${i}`,
      text: `解说${i}。`,
    }));
    const body = splitWorkBody(segmentWorkParts(parts));

    expect(body.tail).toHaveLength(2);
    expect(body.earlier).toHaveLength(3);
  });

  it("动作后长解说收尾（收口交接叙事）：尾部解说至多最近 2 句，更早句折进更早区（高度有界）", () => {
    const parts: WorkPart[] = [
      action({ id: "a1", toolCallId: "tc1", label: "动作一" }),
      ...Array.from({ length: 5 }, (_, i) => ({
        kind: "text",
        id: `t${i}`,
        text: `交接${i}。`,
      } as WorkPart)),
    ];
    const body = splitWorkBody(segmentWorkParts(parts));

    expect(body.tail.map((s) => (s.kind === "single" ? s.part.id : ""))).toEqual(["a1", "t3", "t4"]);
    expect(body.earlier).toHaveLength(3); // 超配额的交接句折进更早（完整回看仍在）
  });

  it("失败破例：更早区含失败动作的段不进坍缩行计数（渲染层提为破例面）", () => {
    const parts: WorkPart[] = [
      ...segments(9).map((p) =>
        p.kind === "action" && p.toolCallId === "tc2"
          ? { ...p, state: "failed" as const }
          : p,
      ),
      action({ id: "a-final", toolCallId: "tc-final", label: "收尾动作" }),
    ];
    const projected = segmentWorkParts(parts);
    const body = splitWorkBody(projected);

    const failures = body.earlier.filter(segmentHasFailure);
    expect(failures).toHaveLength(1); // tc2 所在段
    expect(body.collapsedCount).toBe(body.earlier.length - failures.length);
  });

  it("空部件：三区皆空", () => {
    expect(splitWorkBody([])).toEqual({ earlier: [], collapsedCount: 0, tail: [] });
  });
});

describe("WorkMessage · 混合坍缩呈现（#225 story3/4：恒定高度、更早收进一行）", () => {
  function manyParts(): WorkPart[] {
    return [
      { kind: "text", id: "t0", text: "开始实现下单。" },
      ...Array.from({ length: 12 }, (_, i) =>
        action({ id: `a${i}`, toolCallId: `tc${i}`, label: `动作${i}` }),
      ),
      { kind: "text", id: "t1", text: "开始联调。" },
      action({ id: "a-f1", toolCallId: "tc-f1", state: "running", label: "编写【订单页】" }),
      action({ id: "a-f2", toolCallId: "tc-f2", state: "running", label: "执行【起服务】" }),
      { kind: "text", id: "t2", text: "订单页快好了。" },
    ];
  }

  it("长流水生长中：出「更早 N 项」一行；最近动作行（尾组展开）与最近解说常驻可见", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work({ parts: manyParts() })} />);

    expect(html).toContain("更早");
    expect(html).toContain("项");
    // 尾部常驻：正在进行的动作行 + 最近解说（story7 当前动作行常驻 spinner 与状态字）
    expect(html).toContain("编写【订单页】");
    expect(html).toContain("订单页快好了。");
    // 更早动作不逐条播（坍缩控噪）
    expect(html).not.toContain("动作0");
  });

  it("失败破例呈现（story9/10）：最新动作失败 → 滚动行停滚转红；失败组不折叠埋掉", () => {
    const parts: WorkPart[] = [
      action({ id: "a1", toolCallId: "tc1", label: "编写【A】" }),
      action({ id: "a2", toolCallId: "tc2", label: "执行【B】" }),
      action({ id: "a3", toolCallId: "tc3", label: "编写【C】" }),
      action({ id: "a4", toolCallId: "tc4", state: "failed", label: "执行【安装依赖】" }),
    ];
    const html = renderToStaticMarkup(<WorkMessage work={work({ parts })} />);

    expect(html).toContain("执行【安装依赖】"); // 失败动作行不被折叠埋掉（尾组展开）
    expect(html).toContain("没做成");
    expect(html).toContain("刚才的动作没做成"); // 滚动行停滚转红（story10）
    expect(html).not.toContain("正在干活…");
  });

  it("定格：坍缩形态保持（story12——结束瞬间无界面跳变），尾部组回落折叠（正常收工维持折叠）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ frozen: true, parts: manyParts() })} />,
    );

    expect(html).toContain("更早"); // 坍缩行保形
    expect(html).not.toContain("编写【订单页】"); // 尾组回落折叠（完成态）
    expect(html).not.toContain("正在干活…");
  });
});
