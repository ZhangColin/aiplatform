// @vitest-environment happy-dom
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { beforeEach, describe, expect, it, vi } from "vitest";

import { encodeAnnotate, parseAnchorEvent, parseExitEvent } from "./annotation";

/**
 * 圈注契约测试缝（#134 主缝）：happy-dom 装载**真实注入脚本**（服务端资源单源
 * aiplatform-server/src/main/resources/docker/workspace/annotation.js，经 ADR-0014
 * 网关注入预览页——测试直读文件、不复制），从外部驱动进出标注态与真实 DOM 事件，
 * 断言脚本发出的每个信封都能被父窗解析器原样解析（跨帧双侧契约）。Esc 信封
 * 契约错位（#134 前：退出信号包进锚信封 payload、父窗查顶层，必被丢弃）由本缝
 * 拦截。只测外部可观测行为（发出的信封、DOM 副作用），不测脚本内部函数与私有状态。
 *
 * #136 圈选扩展：遮罩可见性断言用 getComputedStyle（happy-dom 与浏览器同判
 * 内联样式压样式表规则——出生内联 display:none 压 .active{display:block} 的
 * 层叠 bug 正是在这层观测面暴露）；拖框以真实 PointerEvent 驱动遮罩（圈选的
 * 指针命中面），DOM 参照（selector/text）依赖真实命中测试、happy-dom 恒缺，
 * 圈选锚以 region 为硬判据。
 *
 * DOM 环境用 happy-dom（仓库逐文件 pragma 例外先例；票面「jsdom」泛指 DOM 仿真）。
 * 父→子方向直接用 encodeAnnotate 的产物驱动（父窗编码器 ↔ 脚本监听同缝收口）；
 * 子→父方向覆写 window.parent 记录 postMessage（脚本对父窗的全部可观测输出）。
 */

/** 服务端注入脚本单源（vitest 恒以 aiplatform-web 为 cwd，跨包指到服务端资源；
 * 文件缺失 = 契约缝断裂，readFileSync 直接红——不静默跳过）。 */
const SCRIPT_PATH = resolve(
  process.cwd(),
  "../aiplatform-server/src/main/resources/docker/workspace/annotation.js",
);

/** 假父窗源（回传目标 = 呼出消息的 event.origin，脚本不做通配广播）。 */
const PARENT_ORIGIN = "http://localhost:3333";

/** 记录脚本对父窗发出的全部 postMessage（data + 目标 origin）。 */
const posted: { data: unknown; origin: string }[] = [];

// 装载真实脚本（IIFE 往全局挂监听；自带防重入守卫，模块级执行一次）。
// window.parent 覆写为记录器：脚本 post() 的唯一出口，即子→父可观测面。
Object.defineProperty(window, "parent", {
  value: { postMessage: (data: unknown, origin: string) => posted.push({ data, origin }) },
  configurable: true,
});
new Function(readFileSync(SCRIPT_PATH, "utf8"))();

/** 父 → 子：以父窗真实编码器的产物驱动脚本 message 监听（真实 origin）。 */
function sendToChild(message: unknown) {
  window.dispatchEvent(new MessageEvent("message", { data: message, origin: PARENT_ORIGIN }));
}

/** 页面元素上的真实 click 冒泡（点选拾取路径）。 */
function clickOn(el: Element) {
  el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
}

/** 标注态内按 Esc（脚本 document keydown 监听）。 */
function pressEsc() {
  document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
}

beforeEach(() => {
  sendToChild(encodeAnnotate("exit")); // 脚本状态跨用例常驻（监听挂全局），显式归零到未标注态
  posted.length = 0;
  document.body.innerHTML = "";
});

describe("圈注契约 · 真实注入脚本 ↔ 父窗解析器（#134）", () => {
  it("点选工具点击元素：发出锚信封，父窗解析器原样解析（双侧一致）", () => {
    const btn = document.createElement("button");
    btn.id = "submit";
    btn.textContent = "提交订单";
    document.body.appendChild(btn);

    sendToChild(encodeAnnotate("enter", "select"));
    clickOn(btn);

    expect(posted).toHaveLength(1);
    expect(parseAnchorEvent(posted[0].data)).toEqual({
      kind: "select",
      anchor: { selector: "#submit", text: "提交订单" },
      note: "",
    });
    expect(posted[0].origin).toBe(PARENT_ORIGIN); // 回传目标 = 呼出 origin，不通配
  });

  it("标注态内按 Esc：发出顶层退出信封，父窗识别为退出且不误读为锚（#134 回归）", () => {
    sendToChild(encodeAnnotate("enter", "select"));

    pressEsc();

    expect(posted).toHaveLength(1);
    expect(parseExitEvent(posted[0].data)).toBe(true);
    expect(parseAnchorEvent(posted[0].data)).toBeNull(); // 退出信封不是锚（形状互斥）
    expect(posted[0].origin).toBe(PARENT_ORIGIN); // 回传目标 = 呼出 origin，不通配
  });

  it("Esc 退出后脚本停发：再点元素无新信封", () => {
    const btn = document.createElement("button");
    btn.textContent = "提交订单";
    document.body.appendChild(btn);
    sendToChild(encodeAnnotate("enter", "select"));
    pressEsc();
    posted.length = 0;

    clickOn(btn);

    expect(posted).toHaveLength(0);
  });

  it("父窗 exit 信封（再点工具键/关闭按钮两路的子窗侧）：脚本退出停发", () => {
    const btn = document.createElement("button");
    btn.textContent = "提交订单";
    document.body.appendChild(btn);
    sendToChild(encodeAnnotate("enter", "select"));

    sendToChild(encodeAnnotate("exit"));
    clickOn(btn);

    expect(posted).toHaveLength(0);
  });
});

/** 标注态遮罩（enter 后在场；显隐是 #136 根因的观测面）。 */
function findOverlay(): HTMLElement {
  const el = document.querySelector("[data-aiplatform-annotation='overlay']");
  expect(el).toBeTruthy();
  return el as HTMLElement;
}

/** 遮罩上的真实 pointer 事件（圈选模式下遮罩 pointer-events:auto，即指针命中面）。 */
function pointerOn(el: Element, type: string, x: number, y: number) {
  const ev = new PointerEvent(type, { pointerId: 1, clientX: x, clientY: y, bubbles: true, cancelable: true });
  el.dispatchEvent(ev);
  return ev;
}

describe("圈注契约 · 圈选拖框（#136：遮罩显隐内联直切起死回生）", () => {
  it("切入圈选：遮罩即显示；父窗 exit：即隐藏（出生内联 display:none 压样式表 .active 的层叠 bug 回归锚）", () => {
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();
    // computed 口径的可见性（inline × 样式表层叠的真相面——修复前 .active{display:block}
    // 恒败于出生内联 display:none，遮罩从未显示，拖拽落在真实页面上触发原生选择）
    expect(getComputedStyle(overlay).display).toBe("block");
    expect(overlay.style.pointerEvents).toBe("auto"); // 圈选：遮罩拦指针（拖框面）
    const hint = document.querySelector("[data-aiplatform-annotation='hint']") as HTMLElement | null;
    expect(hint?.style.pointerEvents).toBe("none"); // 顶部提示不挡拖拽起点

    sendToChild(encodeAnnotate("exit"));

    expect(getComputedStyle(overlay).display).toBe("none");
  });

  it("拖拽出实时矩形反馈，松手发出区域锚信封——父窗解析器原样解析（>2px 阈值）", () => {
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();
    expect(getComputedStyle(overlay).display).toBe("block"); // 拖拽发生在可见的遮罩上
    const box = overlay.querySelector("[data-aiplatform-box]") as HTMLElement;

    pointerOn(overlay, "pointerdown", 100, 150);
    pointerOn(overlay, "pointermove", 250, 300);
    expect(getComputedStyle(box).display).toBe("block"); // 拖动中实时矩形可见（静止出生无 0×0 残点）
    expect(overlay.style.getPropertyValue("--ax")).toBe("100px");
    expect(overlay.style.getPropertyValue("--ay")).toBe("150px");
    expect(overlay.style.getPropertyValue("--aw")).toBe("150px");
    expect(overlay.style.getPropertyValue("--ah")).toBe("150px");
    pointerOn(overlay, "pointerup", 250, 300);

    // DOM 参照（selector/text）来自浏览器命中测试（happy-dom 的 elementFromPoint 恒
    // null），region 是圈选锚的硬判据、参照可选——真实预览走查（#139）覆盖参照命中
    expect(posted).toHaveLength(1);
    expect(parseAnchorEvent(posted[0].data)).toEqual({
      kind: "circle",
      anchor: { region: { x: 100, y: 150, width: 150, height: 150 } },
      note: "",
    });
    expect(posted[0].origin).toBe(PARENT_ORIGIN); // 回传目标 = 呼出 origin，不通配
  });

  it("拖拽全程页面不穿透、原生选择被压制", () => {
    const pageSpy = vi.fn();
    document.addEventListener("pointerdown", pageSpy);
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();
    expect(overlay.style.userSelect).toBe("none"); // 拖拽面不吃文本选择
    expect(overlay.style.touchAction).toBe("none"); // 触屏拖拽不被滚动接管

    const down = pointerOn(overlay, "pointerdown", 20, 20);
    pointerOn(overlay, "pointermove", 200, 200);
    pointerOn(overlay, "pointerup", 200, 200);

    expect(down.defaultPrevented).toBe(true); // 默认行为（选择/拖拽）被拦
    expect(pageSpy).not.toHaveBeenCalled(); // 不冒泡到页面的 document 监听
    document.removeEventListener("pointerdown", pageSpy);
  });

  it("微小拖动（≤2px）不产区域锚", () => {
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();

    pointerOn(overlay, "pointerdown", 100, 100);
    pointerOn(overlay, "pointerup", 101, 101);

    expect(posted).toHaveLength(0);
  });

  it("拖出视口边界的松手仍正确收尾（指针捕获保证事件不丢）：锚照发、迟到的 move 不再作用", () => {
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();

    pointerOn(overlay, "pointerdown", 100, 100);
    pointerOn(overlay, "pointermove", 200, 200);
    pointerOn(overlay, "pointerup", -50, 800); // 视口外松手（捕获仍把事件送回遮罩）

    expect(posted).toHaveLength(1);
    expect(parseAnchorEvent(posted[0].data)?.anchor.region).toEqual({ x: -50, y: 100, width: 150, height: 700 });

    pointerOn(overlay, "pointermove", 300, 300); // 收尾即拆监听：拖框不再动、不再发
    expect(overlay.style.getPropertyValue("--aw")).toBe("100px");
    expect(posted).toHaveLength(1);
  });

  it("二次拖拽起手收掉上一场的完成矩形：无移动的微拖不留指代错位的残影", () => {
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();
    const box = overlay.querySelector("[data-aiplatform-box]") as HTMLElement;

    pointerOn(overlay, "pointerdown", 100, 100); // 第一场：完成一条区域锚
    pointerOn(overlay, "pointermove", 200, 200);
    pointerOn(overlay, "pointerup", 200, 200);
    expect(posted).toHaveLength(1);
    expect(getComputedStyle(box).display).toBe("block"); // 完成矩形保留（对应刚进的 chip）

    pointerOn(overlay, "pointerdown", 300, 300); // 第二场起手：上一场矩形即收

    expect(getComputedStyle(box).display).toBe("none");
    pointerOn(overlay, "pointerup", 300, 300); // 无移动微拖：不产锚、无残影
    expect(posted).toHaveLength(1);
  });

  it("拖拽中 Esc 退出：收尾干净，迟到的 pointer 事件不再发锚", () => {
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();

    pointerOn(overlay, "pointerdown", 100, 100);
    pointerOn(overlay, "pointermove", 200, 200);
    pressEsc(); // 拖拽中从子窗侧退出

    expect(posted).toHaveLength(1); // 只有退出信封，无锚
    expect(parseExitEvent(posted[0].data)).toBe(true);
    pointerOn(overlay, "pointerup", 200, 200); // 迟到的松手不发锚
    pointerOn(overlay, "pointermove", 300, 300);
    expect(posted).toHaveLength(1);
  });
});
