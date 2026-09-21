import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { GenerationSegmentFact } from "@/lib/projects/detail";
import type { WorkPart, WorkSnapshot } from "@/lib/store/work-message";

import {
  WorkMessage,
  formatClock,
  formatDuration,
  planCurrentOrd,
  presentWorkParts,
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

  it("动作行两态：进行中转圈、失败「没做成」——完成无痕不产生静态条目（#230）；均无时长数字（#115）", () => {
    const running = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "started" })] })} />,
    );
    expect(running).toContain("进行中");
    expect(running).not.toContain("秒");

    const failed = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "failed" })] })} />,
    );
    expect(failed).toContain("没做成");
    expect(failed).not.toContain("秒");

    // 成功无痕：completed 动作沉没——label 不出场、无完成勾（改了什么归收尾卡变更清单）
    const done = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "run-1:2", text: "订单管理完成" },
            action({ id: "a1", toolCallId: "t1" }),
          ],
        })}
      />,
    );
    expect(done).toContain("订单管理完成");
    expect(done).not.toContain("编写【订单管理】");
    expect(done).not.toContain("lucide-check");
  });

  it("收口定格：打字点退场、叙事留驻；定格截断的未终态动作随收口沉没——无「进行中」残骸（#230）", () => {
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
    // run 已收口即无「进行中」：截断动作沉没（结果归收尾卡变更清单），不留转圈不带时长
    expect(html).not.toContain("编写【订单管理】");
    expect(html).not.toContain("进行中");
    expect(html).not.toContain("animate-spin");
    expect(html).not.toContain("秒");
  });

  it("定格且无部件（起跑即死）：不渲染空壳", () => {
    expect(renderToStaticMarkup(<WorkMessage work={work({ frozen: true })} />)).toBe("");
  });
});

describe("WorkMessage · 失败留痕（#229：失败红行＝命令原值＋错误/stderr 首行）", () => {
  it("失败动作携 error：红行渲染 label 与 error（没做成＋为什么）——排障不进容器即可初判", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            action({
              id: "a1",
              toolCallId: "t1",
              toolName: "execute",
              state: "failed",
              label: "npm test",
              error: "npm err! code ELIFECYCLE",
            }),
          ],
        })}
      />,
    );

    expect(html).toContain("npm test"); // 命令原值（哪条命令失败）
    expect(html).toContain("npm err! code ELIFECYCLE"); // 错误首行（为什么失败）
    expect(html).toContain("没做成");
  });

  it("失败动作无 error（结果文本为空/首行空白）：不渲染错误副行——既有失败形态保持", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "failed" })] })}
      />,
    );

    expect(html).toContain("没做成");
    expect(html).not.toContain("text-destructive/90"); // 错误副行不出现（副行专属样式类）
  });

  it("成功/进行中动作不渲染错误副行（error 仅失败留痕）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({ parts: [action({ id: "a1", toolCallId: "t1", state: "running" })] })}
      />,
    );

    expect(html).not.toContain("text-destructive/90");
  });
});

describe("WorkMessage · 动作图标封闭表（#226：表键与服务端播报名册字面一致；#230 改口径——图标用于当前动作行/失败红行）", () => {
  it("write_file / edit_file → 文件码图标；execute → 终端图标——命令动作不落兜底锤子", () => {
    const write = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ toolName: "write_file", state: "running" })] })} />,
    );
    expect(write).toContain("lucide-file-code-corner");

    const edit = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ toolName: "edit_file", state: "running" })] })} />,
    );
    expect(edit).toContain("lucide-file-code-corner");

    const execute = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ toolName: "execute", state: "running" })] })} />,
    );
    expect(execute).toContain("lucide-square-terminal");
    expect(execute).not.toContain("lucide-hammer");
  });

  it("表外工具落兜底锤子（封闭表外唯一出口）", () => {
    const unknown = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ toolName: "read_file", state: "running" })] })} />,
    );
    expect(unknown).toContain("lucide-hammer");
  });
});

describe("WorkMessage · 命令原值滚动行（#228：当前动作行动态化）", () => {
  it("execute 动作行逐字渲染命令原值（label 来自服务端剥壳截断后的命令原文）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            action({
              toolName: "execute",
              state: "running",
              label: "npm test --filter auth",
            }),
          ],
        })}
      />,
    );

    expect(html).toContain("npm test --filter auth"); // 逐字渲染，不改写
    expect(html).toContain("lucide-square-terminal"); // #226 终端图标不破
  });

  it("动作行标签单行截断样式（truncate）：长命令不换行撑高，卡片宽度恒定（#225 story8）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            action({
              toolName: "execute",
              state: "running",
              label: "npm run build --configuration production --output-path dist/apps/web",
            }),
          ],
        })}
      />,
    );

    // 标签行带 truncate（nowrap + ellipsis）——单行优雅截断
    expect(html).toContain("min-w-0 flex-1 truncate");
  });

  it("写文件类 label 语义不动：「编写【文件名】」照常渲染", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ parts: [action({ state: "running" })] })} />,
    );

    expect(html).toContain("编写【订单管理】");
  });
});

describe("WorkMessage · 成功无痕（#230：动作组退役，静态面＝叙事＋失败痕）", () => {
  it("成功动作不产生静态条目：解说照常竖流，命令/写文件成功后皆沉没——无「N 个动作」组", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "t0", text: "开始实现下单。" },
            action({ id: "a1", toolCallId: "tc1", label: "编写【列表页】" }),
            action({ id: "a2", toolCallId: "tc2", toolName: "execute", label: "npm install" }),
            { kind: "text", id: "t1", text: "依赖与页面就绪。" },
          ],
        })}
      />,
    );

    expect(html).toContain("开始实现下单。");
    expect(html).toContain("依赖与页面就绪。");
    expect(html).not.toContain("编写【列表页】"); // 写文件成功沉没
    expect(html).not.toContain("npm install"); // 命令成功沉没
    expect(html).not.toContain("个动作"); // 动作组随 #230 退役
    expect(html).not.toContain("更早"); // 解说两句内无坍缩
  });

  it("进行中动作常驻当前动作行（story7）；并发时显示最近发起的一条（story15 直播行＝「现在」）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "t0", text: "开始联调。" },
            action({ id: "a1", toolCallId: "tc1", state: "running", label: "编写【订单页】" }),
            action({
              id: "a2",
              toolCallId: "tc2",
              toolName: "execute",
              state: "running",
              label: "npm run dev",
            }),
          ],
        })}
      />,
    );

    expect(html).toContain("npm run dev"); // 最近发起的动作为当前动作行
    expect(html).toContain("进行中");
    expect(html).not.toContain("编写【订单页】"); // 被更新动作取代的并发动作沉没
  });

  it("失败红行不埋进坍缩（#225 破例语义承接）：更早区失败痕常驻展开，坍缩行只数解说段", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "t0", text: "开始。" },
            { kind: "text", id: "t1", text: "装依赖。" },
            action({
              id: "a1",
              toolCallId: "tc1",
              toolName: "execute",
              state: "failed",
              label: "npm install",
              error: "npm err! code ELIFECYCLE",
            }),
            { kind: "text", id: "t2", text: "换个镜像源重装。" },
            action({
              id: "a2",
              toolCallId: "tc2",
              toolName: "execute",
              state: "running",
              label: "npm install --registry=https://registry.npmmirror.com",
            }),
          ],
        })}
      />,
    );

    // 失败红行常驻（label＋error，承接 #229 T2 形态），未展开也可见
    expect(html).toContain("npm install");
    expect(html).toContain("npm err! code ELIFECYCLE");
    expect(html).toContain("没做成");
    // 坍缩行只数解说段（两句更早解说），失败痕不进计数
    expect(html).toContain("更早 2 项");
  });

  it("定格形态（run-finish 后）无成功动作残骸（story16）：成功/截断动作沉没，失败红行与叙事留驻", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          parts: [
            { kind: "text", id: "t0", text: "开始实现下单。" },
            action({ id: "a1", toolCallId: "tc1", label: "编写【列表页】" }),
            action({
              id: "a2",
              toolCallId: "tc2",
              toolName: "execute",
              state: "failed",
              label: "npm test",
              error: "1 test failed",
            }),
            action({ id: "a3", toolCallId: "tc3", state: "running", label: "编写【订单页】" }),
            { kind: "text", id: "t1", text: "收尾。" },
          ],
        })}
      />,
    );

    expect(html).not.toContain("编写【列表页】"); // 成功动作沉没
    expect(html).not.toContain("编写【订单页】"); // 定格截断的未终态动作沉没
    expect(html).not.toContain("进行中");
    expect(html).toContain("npm test"); // 失败红行留驻
    expect(html).toContain("1 test failed");
    expect(html).toContain("开始实现下单。");
    expect(html).toContain("收尾。");
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

  it("失败定格（run-failed）：失败红行留驻、不出收尾卡；成功动作无痕（#230）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          parts: [
            action({ id: "a0", toolCallId: "t0" }),
            action({ id: "a1", toolCallId: "t1", state: "failed", label: "执行【起服务】" }),
          ],
        })}
      />,
    );

    expect(html).not.toContain("本轮完成");
    expect(html).not.toContain("编写【订单管理】"); // 成功动作沉没
    expect(html).toContain("执行【起服务】"); // 失败红行留驻
    expect(html).toContain("没做成");
  });
});

describe("收尾卡「用时」格式（用户语言，整秒）", () => {
  it("formatDuration：<60 秒「N 秒」，跨分「M 分 SS 秒」", () => {
    expect(formatDuration(4_300)).toBe("4 秒");
    expect(formatDuration(63_000)).toBe("1 分 03 秒");
    expect(formatDuration(-5_000)).toBe("0 秒"); // 时钟回拨防御
  });
});

describe("presentWorkParts · 成功无痕投影（#230：滤除只在呈现层，store 部件流水不动）", () => {
  it("completed 动作沉没；解说/自检全保留；failed 留红行", () => {
    const parts: WorkPart[] = [
      { kind: "text", id: "1", text: "开始写。" },
      action({ id: "2", toolCallId: "t1", label: "编写【A】" }),
      action({ id: "3", toolCallId: "t2", toolName: "execute", state: "failed", label: "npm test" }),
      { kind: "check", id: "4", state: "checking" },
    ];

    expect(presentWorkParts(parts, false).map((p) => p.id)).toEqual(["1", "3", "4"]);
  });

  it("末位动作锚定当前动作行：started/running 的末位动作保留（生长中）；被更新动作取代的进行中动作沉没", () => {
    const parts: WorkPart[] = [
      action({ id: "1", toolCallId: "t1", state: "running", label: "编写【A】" }),
      action({ id: "2", toolCallId: "t2", state: "running", label: "npm test" }),
    ];

    expect(presentWorkParts(parts, false).map((p) => p.id)).toEqual(["2"]);
  });

  it("终态动作不夺走直播行：末位动作已收尾、更早动作仍在跑 → 当前动作行锚定仍在跑的动作（#227 决定 5）", () => {
    const parts: WorkPart[] = [
      action({ id: "1", toolCallId: "t1", state: "running", label: "npm run dev" }),
      action({ id: "2", toolCallId: "t2", label: "npm test" }),
    ];

    expect(presentWorkParts(parts, false).map((p) => p.id)).toEqual(["1"]);
  });

  it("定格（run-finish / run-failed 后）：未终态动作随收口沉没，失败红行留驻", () => {
    const parts: WorkPart[] = [
      action({ id: "1", toolCallId: "t1", label: "编写【A】" }),
      action({ id: "2", toolCallId: "t2", state: "failed", label: "npm test" }),
      action({ id: "3", toolCallId: "t3", state: "running", label: "编写【B】" }),
    ];

    expect(presentWorkParts(parts, true).map((p) => p.id)).toEqual(["2"]);
  });

  it("末位动作 completed：无当前动作行（纯叙事静态面）", () => {
    const parts: WorkPart[] = [
      { kind: "text", id: "1", text: "写好了。" },
      action({ id: "2", toolCallId: "t1" }),
    ];

    expect(presentWorkParts(parts, false).map((p) => p.id)).toEqual(["1"]);
  });

  it("空部件 → 空呈现", () => {
    expect(presentWorkParts([], false)).toEqual([]);
  });
});

describe("splitWorkBody · 混合坍缩投影（#225 尾部活动区常驻；#230 组退役后以部件为段）", () => {
  it("长解说流水：更早段坍缩（计数＝解说段数）、尾部 = 当前动作行 + ≤2 句前展解说；分区无损", () => {
    const parts: WorkPart[] = [
      ...Array.from({ length: 6 }, (_, i) => ({ kind: "text", id: `t${i}`, text: `解说${i}。` } as WorkPart)),
      action({ id: "a1", toolCallId: "tc1", state: "running", label: "npm test" }),
    ];
    const present = presentWorkParts(parts, false);
    const body = splitWorkBody(present);

    expect(body.collapsedCount).toBeGreaterThan(0);
    // 坍缩区组成＝解说段（呈现序列无成功动作残骸）
    expect(body.earlier.every((p) => p.kind === "text")).toBe(true);
    expect(body.tail).toHaveLength(3); // ≤2 前展解说 + 当前动作行
    expect(body.tail.at(-1)?.kind).toBe("action"); // 尾部含当前动作行
    // 分区无损：更早区 + 尾部 = 呈现序列（呈现层不再裁剪，仅坍缩控噪）
    expect(body.earlier.length + body.tail.length).toBe(present.length);
  });

  it("纯解说（成功无痕后动作全沉没的常态）：尾部取最后两段，其余坍缩", () => {
    const parts: WorkPart[] = [
      ...Array.from({ length: 5 }, (_, i) => ({ kind: "text", id: `t${i}`, text: `解说${i}。` } as WorkPart)),
      action({ id: "a1", toolCallId: "tc1" }),
    ];
    const body = splitWorkBody(presentWorkParts(parts, false));

    expect(body.tail.map((p) => p.id)).toEqual(["t3", "t4"]);
    expect(body.earlier).toHaveLength(3);
  });

  it("动作后长解说收尾（收口交接叙事）：尾部解说至多最近 2 句，更早句折进更早区（高度有界）", () => {
    const parts: WorkPart[] = [
      action({ id: "a1", toolCallId: "tc1", state: "running", label: "npm test" }),
      ...Array.from({ length: 5 }, (_, i) => ({ kind: "text", id: `t${i}`, text: `交接${i}。` } as WorkPart)),
    ];
    const body = splitWorkBody(presentWorkParts(parts, false));

    expect(body.tail.map((p) => p.id)).toEqual(["a1", "t3", "t4"]);
    expect(body.earlier).toHaveLength(3); // 超配额的交接句折进更早（完整回看仍在）
  });

  it("失败破例：更早区失败红行不进坍缩行计数（渲染层提为破例面）", () => {
    const parts: WorkPart[] = [
      { kind: "text", id: "t0", text: "解说0。" },
      { kind: "text", id: "t1", text: "解说1。" },
      action({ id: "a1", toolCallId: "tc1", state: "failed", label: "npm test" }),
      { kind: "text", id: "t2", text: "解说2。" },
      action({ id: "a2", toolCallId: "tc2", state: "running", label: "npm install" }),
    ];
    const body = splitWorkBody(presentWorkParts(parts, false));

    const failures = body.earlier.filter(
      (p): p is Extract<WorkPart, { kind: "action" }> =>
        p.kind === "action" && p.state === "failed",
    );
    expect(failures).toHaveLength(1); // 失败红行在更早区（其后有新动作）
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
      { kind: "text", id: "t1", text: "先装依赖。" },
      { kind: "text", id: "t2", text: "开始联调。" },
      action({ id: "a-f1", toolCallId: "tc-f1", state: "running", label: "编写【订单页】" }),
      action({ id: "a-f2", toolCallId: "tc-f2", state: "running", label: "执行【起服务】" }),
      { kind: "text", id: "t3", text: "订单页快好了。" },
    ];
  }

  it("长流水生长中：出「更早 N 项」一行；当前动作行（末位动作）与最近解说常驻可见，成功动作无痕", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work({ parts: manyParts() })} />);

    expect(html).toContain("更早");
    expect(html).toContain("项");
    // 尾部常驻：当前动作行（story7 常驻 spinner 与状态字）+ 最近解说
    expect(html).toContain("执行【起服务】");
    expect(html).toContain("订单页快好了。");
    // 成功动作无痕（#230）；被更新动作取代的并发进行中动作沉没（story15）
    expect(html).not.toContain("动作0");
    expect(html).not.toContain("编写【订单页】");
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
