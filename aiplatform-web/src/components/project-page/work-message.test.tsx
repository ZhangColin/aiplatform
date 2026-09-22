import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { GenerationSegmentFact } from "@/lib/projects/detail";
import type { WorkPart, WorkPlanStep, WorkSnapshot } from "@/lib/store/work-message";

import {
  WorkMessage,
  activityOf,
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

describe("WorkMessage · 常驻活性行（#235：直播行＝唯一实时状态行，全程常驻不消失）", () => {
  it("动作在跑：活性行＝命令原值 label（单行截断）——恰好一条，无独立「正在干活」字样行", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "t0", text: "开始联调。" },
            action({ id: "a1", toolCallId: "t1", toolName: "execute", state: "running", label: "npm run dev" }),
          ],
        })}
      />,
    );

    expect(html).toContain("npm run dev");
    expect(html.match(/npm run dev/g)).toHaveLength(1); // 唯一实时状态行——直播行不重复占位
    expect(html).toContain("进行中");
    expect(html.match(/进行中/g)).toHaveLength(1);
    expect(html).not.toContain("正在干活"); // 独立字样行退役
    expect(html).not.toContain("animate-pulse"); // 动作在跑不出打字点
    expect(html).toContain("min-w-0 flex-1 truncate"); // 单行截断不回归（#228）
  });

  it("动作间隙（agent 思考中）：活性行＝无字打字点活动指示——行在、无字", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "t0", text: "开始联调。" },
            action({ id: "a1", toolCallId: "t1", toolName: "execute", label: "npm run dev" }), // completed 成功无痕
          ],
        })}
      />,
    );

    expect(html).toContain("animate-pulse"); // 打字点活动指示
    expect(html).not.toContain("正在干活"); // 无字——字样退役
    expect(html).not.toContain("进行中");
    expect(html).not.toContain("npm run dev"); // 完成的动作沉没（成功无痕），不是「行消失」
  });

  it("动作起灭正文不动：同一动作 进行中→完成 两帧间，解说段与活性行位不增不减", () => {
    const parts = (state: "running" | "completed"): WorkPart[] => [
      { kind: "text", id: "t0", text: "先装依赖。" },
      action({ id: "a1", toolCallId: "t1", toolName: "execute", state, label: "pnpm install" }),
      { kind: "text", id: "t1", text: "依赖就绪。" },
    ];

    const running = renderToStaticMarkup(<WorkMessage work={work({ parts: parts("running") })} />);
    const done = renderToStaticMarkup(<WorkMessage work={work({ parts: parts("completed") })} />);

    for (const html of [running, done]) {
      expect(html).toContain("先装依赖。");
      expect(html).toContain("依赖就绪。"); // 正文两帧一致——直播行不进正文、起灭不推挤
    }
    // 活性行恒在：在跑帧＝label，完成帧＝打字点（间隙），全程恰好一条
    expect(running).toContain("pnpm install");
    expect(running).not.toContain("animate-pulse");
    expect(done).not.toContain("pnpm install");
    expect(done).toContain("animate-pulse");
  });

  it("动作失败：活性行＝红字变体「刚才的动作没做成，正在处理」（语义沿用）——打字点退场", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            action({
              id: "a1",
              toolCallId: "t1",
              toolName: "execute",
              state: "failed",
              label: "npm install",
              error: "npm err! 404",
            }),
          ],
        })}
      />,
    );

    expect(html.match(/刚才的动作没做成，正在处理/g)).toHaveLength(1); // 红字变体唯一一条
    expect(html).not.toContain("正在干活");
    expect(html).not.toContain("animate-pulse");
    // 失败红行（label＋error 留痕，#229）不回归
    expect(html).toContain("npm install");
    expect(html).toContain("npm err! 404");
    expect(html).toContain("没做成");
  });
});

describe("WorkMessage · 脱轨留痕（#240：机器语法吞段的人话信号，活性行脱轨变体——静态面无痕）", () => {
  const DERAILED_PHRASE = "刚才模型发射异常，正在调整";

  function signal(overrides: Partial<Extract<WorkPart, { kind: "signal" }>> = {}) {
    return {
      kind: "signal",
      id: "run-1:9",
      signal: "derailed",
      ...overrides,
    } satisfies Extract<WorkPart, { kind: "signal" }>;
  }

  it("activityOf：末位信号（其后无动作接管）＝脱轨变体——纯解说轮 / 吞段在最后动作之后皆然", () => {
    expect(activityOf([{ kind: "text", id: "t0", text: "开始。" }, signal()])).toEqual({
      kind: "derailed",
    });
    // 动作已完成（成功无痕不占活性行）+ 其后脱轨：脱轨是动作世界的最新事实
    expect(activityOf([action({ id: "a1" }), signal()])).toEqual({ kind: "derailed" });
  });

  it("activityOf：脱轨后被真实动作接管——在跑动作优先（run 恢复推进的直接证据）", () => {
    expect(
      activityOf([
        signal(),
        action({ id: "a2", toolCallId: "t2", state: "running", label: "pnpm test" }),
      ]),
    ).toEqual({ kind: "action", part: expect.objectContaining({ toolCallId: "t2" }) });
    // 接管动作已完成：脱轨信号已被消化（不滞留旧事故），间隙回落打字点
    expect(
      activityOf([
        signal(),
        action({ id: "a2", toolCallId: "t2", state: "completed" }),
      ]),
    ).toEqual({ kind: "idle" });
  });

  it("activityOf：失败破例维持 #235 最高位——末位动作失败压过更早的脱轨信号", () => {
    expect(
      activityOf([
        signal(),
        action({ id: "a2", toolCallId: "t2", state: "failed" }),
      ]),
    ).toEqual({ kind: "failed" });
  });

  it("脱轨变体活性行：琥珀色一句定型文案，恰好一条——不出命令滚动行、不转圈、无「进行中」", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          parts: [
            { kind: "text", id: "t0", text: "先跑一遍自测。" },
            signal({ id: "run-1:5" }),
          ],
        })}
      />,
    );

    expect(html.match(new RegExp(DERAILED_PHRASE, "g"))).toHaveLength(1); // 恰好一条
    expect(html).toContain("text-amber-600"); // 脱轨变体琥珀色（与失败红字区分）
    expect(html).not.toContain("进行中"); // 不伪造「在执行」
    expect(html).not.toContain("animate-pulse"); // 打字点退场（脱轨 ≠ 间隙思考）
    expect(html).not.toContain("DSML"); // 界面任何位置不见机器语法原文
  });

  it("静态面无痕：信号部件不进正文（不产生解说段/动作行/坍缩计数）——活性行是唯一呈现位", () => {
    const parts: WorkPart[] = [
      { kind: "text", id: "t0", text: "第一句。" },
      signal({ id: "run-1:5" }),
      { kind: "text", id: "t1", text: "第二句。" },
    ];
    expect(presentWorkParts(parts)).toEqual(parts.filter((part) => part.kind !== "signal"));

    const html = renderToStaticMarkup(<WorkMessage work={work({ parts })} />);
    expect(html).toContain("第一句。");
    expect(html).toContain("第二句。");
    expect(html).not.toContain("更早"); // 信号不占坍缩计数
    expect(html).toContain(DERAILED_PHRASE); // 活性行照常呈现
  });

  it("定格保留末行（#235 口径同款）：脱轨行静态留驻至收尾卡入流，随后沉没", () => {
    const frozen = work({
      frozen: true,
      parts: [{ kind: "text", id: "t0", text: "收口中。" }, signal({ id: "run-1:7" })],
    });
    // 收尾卡未入流：末行保留（衔接窗无跳变）
    expect(renderToStaticMarkup(<WorkMessage work={frozen} />)).toContain(DERAILED_PHRASE);
    // 收尾卡入流：活性行随定格沉没——终形无脱轨残骸（静态面本就无痕）
    expect(
      renderToStaticMarkup(<WorkMessage work={frozen} closingArrived />),
    ).not.toContain(DERAILED_PHRASE);
  });
});

describe("WorkMessage · 生长中的工作消息（#81：部件结构与状态呈现）", () => {
  it("run 开始即出现：空部件的生长中消息出「正在做」头部与无字打字点（#235 间隙活性指示），无部件行", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work()} />);

    expect(html).toContain("正在做");
    expect(html).toContain("animate-pulse"); // 打字点（无字）
    expect(html).not.toContain("正在干活"); // 独立字样行退役（#235）
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

  it("收口保留末行（#235）：定格瞬间活性行不闪空——末行静态保留（不转圈、无「进行中」、打字点不跳），叙事留驻", () => {
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

    expect(html).not.toContain("正在做"); // 定格：头部脉冲与「正在做」回落退场
    expect(html).toContain("订单管理完成"); // 叙事留驻
    expect(html).toContain("编写【订单管理】"); // 活性行保留末行（run 收口 → 收尾卡入流的衔接窗）
    expect(html).not.toContain("进行中"); // 静态末行：定格卡不自称在跑
    expect(html).not.toContain("animate-spin");
    expect(html).not.toContain("animate-pulse");
  });

  it("收尾卡入流后活性行随定格沉没（#235）：截断的未终态动作无「进行中」残骸——终形＝叙事＋失败痕（#230 story16 沉没锚修订）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        closingArrived
        work={work({
          frozen: true,
          parts: [
            { kind: "text", id: "run-1:2", text: "订单管理完成" },
            action({ id: "a1", toolCallId: "t1", state: "running" }),
          ],
        })}
      />,
    );

    expect(html).toContain("订单管理完成");
    // 收尾卡接力完成：截断动作沉没（结果归收尾卡变更清单），不留转圈不带时长
    expect(html).not.toContain("编写【订单管理】");
    expect(html).not.toContain("进行中");
    expect(html).not.toContain("animate-spin");
    expect(html).not.toContain("animate-pulse"); // 打字点同步沉没
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
            action({
              id: "a1",
              toolCallId: "tc1",
              toolName: "execute",
              state: "failed",
              label: "npm install",
              error: "npm err! code ELIFECYCLE",
            }),
            { kind: "text", id: "t1", text: "换个镜像源重装。" },
            { kind: "text", id: "t2", text: "重装成功。" },
            action({
              id: "a2",
              toolCallId: "tc2",
              toolName: "execute",
              state: "failed",
              label: "npm test",
              error: "1 test failed",
            }),
            { kind: "text", id: "t3", text: "修脚本。" },
            { kind: "text", id: "t4", text: "再跑一遍。" },
          ],
        })}
      />,
    );

    // 更早区失败红行常驻（label＋error，承接 #229 T2 形态），未展开也可见
    expect(html).toContain("npm install");
    expect(html).toContain("npm err! code ELIFECYCLE");
    // 坍缩行只数解说段（更早区＝1 句解说＋1 条失败痕，失败痕不进计数）
    expect(html).toContain("更早 1 项");
    // 最新失败红行在尾部锚定（label＋error），活性行转红字变体（唯一一条）
    expect(html).toContain("npm test");
    expect(html).toContain("1 test failed");
    expect(html.match(/没做成/g)).toHaveLength(3); // 两条失败痕徽标各一＋活性行红字变体含其一
    expect(html.match(/刚才的动作没做成，正在处理/g)).toHaveLength(1);
  });

  it("定格形态（收尾卡入流后）无成功动作残骸（story16）：成功/截断动作沉没，失败红行与叙事留驻", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        closingArrived
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
    expect(html).not.toContain("编写【订单页】"); // 定格截断的未终态动作随活性行沉没（#235：沉没锚＝收尾卡入流）
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

  it("定格留驻（收尾卡入流后终形）：标题与冻结时钟保形（story12 结束瞬间无跳变），脉冲点与活性行退场", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        closingArrived
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
    expect(html).not.toContain("animate-pulse"); // 活性行随定格沉没（#235）
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

  // ---------- #237 两级整合：生成轨切片级＋步骤级同卡共存 ----------

  /** 计划区容器类（切片级与步骤级同款边框盒，计数即清单区个数；match 全局正则复用安全）。 */
  const PLAN_BOX = /divide-y divide-border\/60 rounded-lg border border-border\/60/g;

  const steps: WorkPlanStep[] = [
    { id: "s1", title: "建订单数据表", state: "completed" },
    { id: "s2", title: "写订单页面", state: "in_progress" },
    { id: "s3", title: "下单接口联调", state: "pending" },
  ];

  it("生成轨 run 进行中：切片级＋步骤级两级清单同卡共存（#237）——切片级在上、步骤级在下，四态与两级高亮并存", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          slice: { title: "用户能下单支付", index: 2, total: 3 },
          plan: steps,
          parts: [{ kind: "text", id: "t0", text: "开始实现下单支付。" }],
        })}
        plan={plan}
      />,
    );

    // 两级各自成表：切片级行（四态）与步骤级行（三态）同卡可见
    for (const description of ["系统初始化", "用户能注册登录", "用户能下单支付", "用户能查看订单"]) {
      expect(html).toContain(description); // 切片级：✓✓ + 当前 + ✗
    }
    for (const title of ["建订单数据表", "写订单页面", "下单接口联调"]) {
      expect(html).toContain(title); // 步骤级：✓●○
    }
    expect(html.match(PLAN_BOX)).toHaveLength(2); // 恰两个清单区
    expect(html.indexOf("系统初始化")).toBeLessThan(html.indexOf("建订单数据表")); // 上半切片级、下半步骤级
    // 两级高亮并存：当前片 ● 与当前步 ● 各一、两级「进行中」徽标各一
    expect(html.match(/●/g)).toHaveLength(2);
    expect(html.match(/进行中/g)).toHaveLength(2);
    expect(html.match(/text-green-600/g)).toHaveLength(3); // 2 ✓ 已收口切片 + 1 ✓ 完成步骤
  });

  it("定格：两级各自收口——切片级去高亮（状态归 REST）、步骤级快照留驻（状态标保形、徽标退场）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          slice: { title: "用户能下单支付", index: 2, total: 3 },
          plan: steps,
          parts: [{ kind: "text", id: "t0", text: "写好了。" }],
        })}
        plan={plan}
      />,
    );

    expect(html).toContain("用户能注册登录"); // 切片级行留驻
    expect(html).toContain("写订单页面"); // 步骤级快照留驻
    expect(html).not.toContain("bg-primary/10"); // 两级高亮皆退场（定格去高亮不回归）
    expect(html).not.toContain("进行中"); // 两级徽标皆退场——静止的卡不自称在跑
    expect(html.match(/●/g)).toHaveLength(1); // 只剩步骤级快照自报的 ●（忘推进如实滞留）
  });

  it("更新轨 run 卡只显步骤级（#237）：有切片计划事实也不渲染切片区——唯一清单区＝步骤级，无空切片区", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ slice: { title: "系统更新" }, plan: steps })} plan={plan} />,
    );

    expect(html).not.toContain("系统初始化"); // 切片级行不出场（连容器都不出——无空切片区）
    expect(html).not.toContain("用户能注册登录");
    expect(html).toContain("建订单数据表"); // 步骤级照常（更新轨主承载）
    expect(html).toContain("系统更新"); // 头部照常
    expect(html.match(PLAN_BOX)).toHaveLength(1);
  });
});

describe("WorkMessage · 步骤清单（#236：run 级 part-plan 快照渲染，✓●○ 不设 ✗）", () => {
  const steps: WorkPlanStep[] = [
    { id: "s1", title: "读取现有配色", state: "completed" },
    { id: "s2", title: "调整主题色变量", state: "in_progress" },
    { id: "s3", title: "重启服务验证", state: "pending" },
  ];

  it("快照渲染：✓/●/○ 三态齐全、当前步高亮带「进行中」——更新轨 run 卡的主承载", () => {
    const html = renderToStaticMarkup(
      <WorkMessage work={work({ slice: { title: "系统更新" }, plan: steps })} />,
    );

    for (const title of ["读取现有配色", "调整主题色变量", "重启服务验证"]) {
      expect(html).toContain(title);
    }
    expect(html).toContain("●"); // 当前步
    expect(html).toContain("○"); // 待做步
    expect(html.match(/text-green-600/g)).toHaveLength(1); // ✓ 完成步（green Check）
    expect(html).toContain("系统更新"); // 头部照常（清单在计划区位、不取代标题）
  });

  it("快照就地整表更新：推进后新表替换旧表（单表不追加）", () => {
    const before = renderToStaticMarkup(<WorkMessage work={work({ plan: steps })} />);
    const after = renderToStaticMarkup(
      <WorkMessage
        work={work({
          plan: [
            { id: "s1", title: "读取现有配色", state: "completed" },
            { id: "s2", title: "调整主题色变量", state: "completed" },
            { id: "s3", title: "重启服务验证", state: "in_progress" },
          ],
        })}
      />,
    );

    expect(before.match(/调整主题色变量/g)).toHaveLength(1);
    expect(after.match(/重启服务验证/g)).toHaveLength(1); // 全量替换：不追加第二条清单
    expect(after.match(/text-green-600/g)).toHaveLength(2); // 两步已收口
    expect(after).toContain("进行中");
  });

  it("agent 不产清单：无清单区域、无报错（解说兜底）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({ parts: [{ kind: "text", id: "t0", text: "开始改配色。" }] })}
      />,
    );

    expect(html).toContain("开始改配色。");
    expect(html).not.toContain("○"); // 无清单行（快照缺省 = 不显示）
    expect(html).not.toContain("●");
  });

  it("定格留驻最后快照：「进行中」徽标退场、状态标保形（忘推进如实滞留）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          frozen: true,
          plan: steps,
          parts: [{ kind: "text", id: "1", text: "改完了。" }],
        })}
      />,
    );

    expect(html).toContain("读取现有配色"); // 清单留驻
    expect(html).toContain("●"); // 状态标保形（快照自报事实，平台不推断补偿）
    expect(html).not.toContain("进行中"); // 徽标退场——静止的卡不自称在跑
  });

  it("清单不进正文流水：计划变化不产生滚动播报/动作部件（步骤行与解说段分面）", () => {
    const html = renderToStaticMarkup(
      <WorkMessage
        work={work({
          plan: steps,
          parts: [{ kind: "text", id: "t0", text: "正在改配色。" }],
        })}
      />,
    );

    expect(html.match(/<p /g)?.length).toBe(1); // 正文只有解说段——步骤行是清单区行非段落（`<p ` 不误配图标 path）
    expect(html).toContain("正在改配色。");
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

  it("失败定格（run-failed）：失败红行留驻、不出收尾卡；成功动作无痕（#230）；无收尾卡可接力——活性行末行红字留驻（#235）", () => {
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
    // run-failed 无收尾卡入流——活性行保留末行（红字变体静态留驻，恢复出口归生成面）
    expect(html).toContain("刚才的动作没做成，正在处理");
    expect(html).not.toContain("animate-spin");
    expect(html).not.toContain("animate-pulse");
  });
});

describe("收尾卡「用时」格式（用户语言，整秒）", () => {
  it("formatDuration：<60 秒「N 秒」，跨分「M 分 SS 秒」", () => {
    expect(formatDuration(4_300)).toBe("4 秒");
    expect(formatDuration(63_000)).toBe("1 分 03 秒");
    expect(formatDuration(-5_000)).toBe("0 秒"); // 时钟回拨防御
  });
});

describe("presentWorkParts · 成功无痕投影（#230 静态面＝叙事＋失败痕；#235 直播动作归活性行、不进正文）", () => {
  it("completed 与进行中动作皆不进正文；解说/自检全保留；failed 留红行", () => {
    const parts: WorkPart[] = [
      { kind: "text", id: "1", text: "开始写。" },
      action({ id: "2", toolCallId: "t1", label: "编写【A】" }), // completed 沉没
      action({ id: "3", toolCallId: "t2", toolName: "execute", state: "failed", label: "npm test" }),
      action({ id: "4", toolCallId: "t3", state: "running", label: "编写【B】" }), // 直播归活性行
      { kind: "check", id: "5", state: "checking" },
    ];

    expect(presentWorkParts(parts).map((p) => p.id)).toEqual(["1", "3", "5"]);
  });

  it("正文投影与定格无关：同一部件集生长/收口同面（定格形态差全归活性行沉没锚，#235）", () => {
    const parts: WorkPart[] = [
      action({ id: "1", toolCallId: "t1", state: "running", label: "编写【A】" }),
      action({ id: "2", toolCallId: "t2", state: "failed", label: "npm test" }),
    ];

    expect(presentWorkParts(parts).map((p) => p.id)).toEqual(["2"]);
  });

  it("空部件 → 空呈现", () => {
    expect(presentWorkParts([])).toEqual([]);
  });
});

describe("activityOf · 活性行三态推导（#235：唯一实时状态行）", () => {
  it("末位动作失败 → 红字变体（失败破例：停滚提示压过仍在跑的并发动作）", () => {
    const parts: WorkPart[] = [
      action({ id: "1", toolCallId: "t1", state: "running", label: "编写【A】" }),
      action({ id: "2", toolCallId: "t2", toolName: "execute", state: "failed", label: "npm test" }),
    ];

    expect(activityOf(parts)).toEqual({ kind: "failed" });
  });

  it("动作在跑 → label 态；并发取最近发起的一条（story15 直播行＝「现在」）", () => {
    const parts: WorkPart[] = [
      action({ id: "1", toolCallId: "t1", state: "running", label: "编写【A】" }),
      action({ id: "2", toolCallId: "t2", toolName: "execute", state: "running", label: "npm test" }),
    ];

    expect(activityOf(parts)).toMatchObject({ kind: "action", part: { id: "2" } });
  });

  it("终态动作不夺走直播行：末位动作已收尾、更早动作仍在跑 → 锚定仍在跑的动作（#227 决定 5）", () => {
    const parts: WorkPart[] = [
      action({ id: "1", toolCallId: "t1", state: "running", label: "npm run dev" }),
      action({ id: "2", toolCallId: "t2", label: "npm test" }),
    ];

    expect(activityOf(parts)).toMatchObject({ kind: "action", part: { id: "1" } });
  });

  it("无在跑动作（纯解说／动作全收尾／空部件）→ 间隙打字点", () => {
    expect(activityOf([{ kind: "text", id: "1", text: "写好了。" }]).kind).toBe("idle");
    expect(activityOf([action({ id: "2", toolCallId: "t1" })]).kind).toBe("idle");
    expect(activityOf([]).kind).toBe("idle");
  });
});

describe("splitWorkBody · 混合坍缩投影（#225 尾部活动区常驻；#230 组退役后以部件为段；#235 直播动作归活性行）", () => {
  it("长解说流水：更早段坍缩（计数＝解说段数）、尾部＝最近两句解说——直播动作不进正文、起灭不推挤分区；分区无损", () => {
    const parts: WorkPart[] = [
      ...Array.from({ length: 6 }, (_, i) => ({ kind: "text", id: `t${i}`, text: `解说${i}。` } as WorkPart)),
      action({ id: "a1", toolCallId: "tc1", state: "running", label: "npm test" }),
    ];
    const present = presentWorkParts(parts);
    const body = splitWorkBody(present);

    expect(present).toHaveLength(6); // 直播动作不进正文（活性行承载）
    expect(body.collapsedCount).toBe(4);
    // 坍缩区组成＝解说段（呈现序列无成功/直播动作条目）
    expect(body.earlier.every((p) => p.kind === "text")).toBe(true);
    expect(body.tail.map((p) => p.id)).toEqual(["t4", "t5"]); // 尾部常驻最近两句解说
    // 分区无损：更早区 + 尾部 = 呈现序列（呈现层不再裁剪，仅坍缩控噪）
    expect(body.earlier.length + body.tail.length).toBe(present.length);
  });

  it("纯解说（成功无痕后动作全沉没的常态）：尾部取最后两段，其余坍缩", () => {
    const parts: WorkPart[] = [
      ...Array.from({ length: 5 }, (_, i) => ({ kind: "text", id: `t${i}`, text: `解说${i}。` } as WorkPart)),
      action({ id: "a1", toolCallId: "tc1" }),
    ];
    const body = splitWorkBody(presentWorkParts(parts));

    expect(body.tail.map((p) => p.id)).toEqual(["t3", "t4"]);
    expect(body.earlier).toHaveLength(3);
  });

  it("动作后长解说收尾（失败红行为锚）：尾部解说至多最近 2 句，更早句折进更早区（高度有界）", () => {
    const parts: WorkPart[] = [
      action({ id: "a1", toolCallId: "tc1", state: "failed", label: "npm test" }),
      ...Array.from({ length: 5 }, (_, i) => ({ kind: "text", id: `t${i}`, text: `交接${i}。` } as WorkPart)),
    ];
    const body = splitWorkBody(presentWorkParts(parts));

    expect(body.tail.map((p) => p.id)).toEqual(["a1", "t3", "t4"]);
    expect(body.earlier).toHaveLength(3); // 超配额的交接句折进更早（完整回看仍在）
  });

  it("失败破例：更早区失败红行不进坍缩行计数（渲染层提为破例面）", () => {
    const parts: WorkPart[] = [
      { kind: "text", id: "t0", text: "解说0。" },
      action({ id: "a1", toolCallId: "tc1", state: "failed", label: "npm install" }),
      { kind: "text", id: "t1", text: "解说1。" },
      { kind: "text", id: "t2", text: "解说2。" },
      action({ id: "a2", toolCallId: "tc2", state: "failed", label: "npm test" }),
      { kind: "text", id: "t3", text: "解说3。" },
      { kind: "text", id: "t4", text: "解说4。" },
    ];
    const body = splitWorkBody(presentWorkParts(parts));

    const failures = body.earlier.filter(
      (p): p is Extract<WorkPart, { kind: "action" }> =>
        p.kind === "action" && p.state === "failed",
    );
    expect(failures).toHaveLength(1); // 更早区失败红行（其后有新失败锚定尾部）
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

  it("长流水生长中：出「更早 N 项」一行；活性行＝最近发起的在跑动作（唯一一条），最近解说常驻可见，成功动作无痕", () => {
    const html = renderToStaticMarkup(<WorkMessage work={work({ parts: manyParts() })} />);

    expect(html).toContain("更早");
    expect(html).toContain("项");
    // 活性行（#235 唯一实时状态行）：最近发起的在跑动作 label＋进行中（story7/15）
    expect(html.match(/执行【起服务】/g)).toHaveLength(1);
    expect(html).toContain("进行中");
    expect(html).toContain("订单页快好了。"); // 尾部常驻最近解说
    // 成功动作无痕（#230）；被更新动作取代的并发进行中动作不进任何面（story15）
    expect(html).not.toContain("动作0");
    expect(html).not.toContain("编写【订单页】");
  });

  it("失败破例呈现（story9/10）：最新动作失败 → 活性行停滚转红；失败红行不被折叠埋掉", () => {
    const parts: WorkPart[] = [
      action({ id: "a1", toolCallId: "tc1", label: "编写【A】" }),
      action({ id: "a2", toolCallId: "tc2", label: "执行【B】" }),
      action({ id: "a3", toolCallId: "tc3", label: "编写【C】" }),
      action({ id: "a4", toolCallId: "tc4", state: "failed", label: "执行【安装依赖】" }),
    ];
    const html = renderToStaticMarkup(<WorkMessage work={work({ parts })} />);

    expect(html).toContain("执行【安装依赖】"); // 失败动作行不被折叠埋掉（尾部展开）
    expect(html).toContain("没做成");
    expect(html).toContain("刚才的动作没做成"); // 活性行停滚转红（story10）
    expect(html).not.toContain("正在干活"); // 字样退役（#235）
    expect(html).not.toContain("animate-pulse"); // 红字变体覆盖打字点
  });

  it("定格：坍缩形态保持（story12——结束瞬间无界面跳变）；活性行保留末行（静态）直到收尾卡入流，入流后沉没（#235）", () => {
    const retained = renderToStaticMarkup(
      <WorkMessage work={work({ frozen: true, parts: manyParts() })} />,
    );

    expect(retained).toContain("更早"); // 坍缩行保形
    expect(retained).toContain("执行【起服务】"); // 保留末行——定格不闪空
    expect(retained).not.toContain("进行中"); // 静态末行：不自称在跑
    expect(retained).not.toContain("编写【订单页】"); // 并发被取代的进行中动作不留痕

    const sunk = renderToStaticMarkup(
      <WorkMessage closingArrived work={work({ frozen: true, parts: manyParts() })} />,
    );

    expect(sunk).toContain("更早"); // 坍缩行保形
    expect(sunk).not.toContain("执行【起服务】"); // 活性行随定格沉没
    expect(sunk).not.toContain("编写【订单页】");
  });
});
