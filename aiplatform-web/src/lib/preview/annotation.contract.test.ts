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
 * #137 标注态反馈与冻结：拾取改 pointerdown 层（遮罩全拦 + preventDefault，
 * 点击不再穿透页面）——DOM 参照同因 happy-dom 命中测试恒缺，以
 * elementFromPoint 桩供给命中元素（浏览器命中是环境设施，脚本围绕它的行为
 * 才是被测对象）；hover 高亮/徽章以遮罩 mousemove 驱动、断言画在目标元素
 * 自身（随滚动自然跟随的机制面）。
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

/** 页面元素上的真实 click 冒泡（穿透路径——#137 起标注态不应再拾取）。 */
function clickOn(el: Element) {
  el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
}

/** 标注态内按 Esc（脚本 document keydown 监听）。 */
function pressEsc() {
  document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
}

/** 悬停徽章（#137：遮罩子元素，随遮罩显隐）。 */
function findBadge(): HTMLElement {
  const el = findOverlay().querySelector("[data-aiplatform-annotation='badge']");
  expect(el).toBeTruthy();
  return el as HTMLElement;
}

/**
 * 命中测试桩：happy-dom 的 elementFromPoint 恒 null（无布局），pointerdown 拾取
 * 与 hover 反馈（#137）都经它命中——桩供给命中元素，被测对象是脚本围绕命中的
 * 行为（拾取/高亮/徽章），浏览器命中本身是环境设施。beforeEach restore 归零。
 */
function stubHitTest(el: Element | null) {
  vi.spyOn(document, "elementFromPoint").mockImplementation(() => el);
}

/** 遮罩上的真实 mousemove（hover 反馈驱动面）。 */
function moveOn(el: Element, x: number, y: number) {
  el.dispatchEvent(new MouseEvent("mousemove", { clientX: x, clientY: y, bubbles: true }));
}

beforeEach(() => {
  sendToChild(encodeAnnotate("exit")); // 脚本状态跨用例常驻（监听挂全局），显式归零到未标注态
  posted.length = 0;
  document.body.innerHTML = "";
  vi.restoreAllMocks(); // elementFromPoint 桩逐用例重置（命中元素由各用例自备）
});

describe("圈注契约 · 真实注入脚本 ↔ 父窗解析器（#134）", () => {
  it("点选拾取发出锚信封，父窗解析器原样解析（双侧一致）——pointerdown 层（#137）", () => {
    const btn = document.createElement("button");
    btn.id = "submit";
    btn.textContent = "提交订单";
    document.body.appendChild(btn);
    stubHitTest(btn);

    sendToChild(encodeAnnotate("enter", "select"));
    pointerOn(findOverlay(), "pointerdown", 10, 20);

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

  it("Esc 退出后脚本停发：标注态外的按压（含页面自身 click）无新信封", () => {
    const btn = document.createElement("button");
    btn.textContent = "提交订单";
    document.body.appendChild(btn);
    stubHitTest(btn);
    sendToChild(encodeAnnotate("enter", "select"));
    pressEsc();
    posted.length = 0;

    pointerOn(findOverlay(), "pointerdown", 10, 20);
    clickOn(btn);

    expect(posted).toHaveLength(0);
  });

  it("父窗 exit 信封（再点工具键/关闭按钮两路的子窗侧）：脚本退出停发", () => {
    const btn = document.createElement("button");
    btn.textContent = "提交订单";
    document.body.appendChild(btn);
    stubHitTest(btn);
    sendToChild(encodeAnnotate("enter", "select"));

    sendToChild(encodeAnnotate("exit"));
    pointerOn(findOverlay(), "pointerdown", 10, 20);
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

describe("圈注契约 · 标注态反馈与冻结（#137：pointerdown 全拦截 + hover 徽章 + 禁用控件）", () => {
  it("切入选择：遮罩即拦指针（全部工具 pointer-events:auto），光标统一 crosshair 不随页面走", () => {
    sendToChild(encodeAnnotate("enter", "select"));
    const overlay = findOverlay();

    expect(overlay.style.pointerEvents).toBe("auto"); // 选择模式同样遮罩拦截——拾取走命中测试
    expect(getComputedStyle(overlay).cursor).toBe("crosshair"); // 命中面在遮罩，页面自身 cursor 不再生效
  });

  it("标注态交互不穿透：pointerdown 被拦（preventDefault）、页面监听（按压/点击/悬停轨迹）不被触发，拾取即锚", () => {
    const btn = document.createElement("button");
    btn.id = "submit";
    btn.textContent = "提交订单";
    document.body.appendChild(btn);
    stubHitTest(btn);
    const spies = ["pointerdown", "click", "mousemove", "mouseover"].map((type) => {
      const spy = vi.fn();
      document.addEventListener(type, spy);
      return [type, spy] as const;
    });

    sendToChild(encodeAnnotate("enter", "select"));
    const overlay = findOverlay();
    const down = pointerOn(overlay, "pointerdown", 10, 20);
    // 余波同压：拾取后的原生 click、悬停期的 mousemove/mouseover 落在遮罩上
    // 冒泡，也不放行到页面 document 冒泡监听（委托式监听吃不到标注态交互）
    overlay.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true }));
    moveOn(overlay, 30, 40);
    overlay.dispatchEvent(new MouseEvent("mouseover", { bubbles: true, cancelable: true }));

    expect(down.defaultPrevented).toBe(true); // 默认行为（focus 漂移/原生选择起点）被压掉
    for (const [, spy] of spies) expect(spy).not.toHaveBeenCalled(); // stopPropagation：document 不收到
    expect(posted).toHaveLength(1); // 按压本身就是拾取——一次动作两份语义不并存
    expect(parseAnchorEvent(posted[0].data)?.anchor).toEqual({ selector: "#submit", text: "提交订单" });
    for (const [type, spy] of spies) document.removeEventListener(type, spy);
  });

  it("禁用控件可指认并回传锚（浏览器不派发 click 的死角，pointerdown+命中测试打通）", () => {
    const btn = document.createElement("button");
    btn.id = "save";
    btn.disabled = true;
    btn.textContent = "保存草稿";
    document.body.appendChild(btn);
    stubHitTest(btn);

    sendToChild(encodeAnnotate("enter", "select"));
    pointerOn(findOverlay(), "pointerdown", 10, 20);

    expect(posted).toHaveLength(1);
    expect(parseAnchorEvent(posted[0].data)).toEqual({
      kind: "select",
      anchor: { selector: "#save", text: "保存草稿" },
      note: "",
    });
  });

  it("按压落空（命中背景）不发锚不炸场", () => {
    stubHitTest(null);
    sendToChild(encodeAnnotate("enter", "select"));
    pointerOn(findOverlay(), "pointerdown", 10, 20);

    expect(posted).toHaveLength(0);
  });

  it("悬停出高亮与徽章：outline 画在目标元素自身（随滚动自然跟随），徽章贴鼠标且不挡命中", () => {
    const card = document.createElement("div");
    card.setAttribute("data-slot", "card");
    card.textContent = "订单卡片";
    document.body.appendChild(card);
    stubHitTest(card);

    sendToChild(encodeAnnotate("enter", "select"));
    moveOn(findOverlay(), 40, 60);

    expect(card.style.outline).toContain("rgb(59, 130, 246)"); // 高亮画在元素自身＝跟随滚动的机制面
    expect(card.style.outlineOffset).toBe("-2px");
    const badge = findBadge();
    expect(getComputedStyle(badge).display).toBe("block");
    expect(badge.style.pointerEvents).toBe("none"); // 徽章不吃命中（不挡下一次拾取/拖拽）
    expect(badge.textContent).toBe("card"); // data-slot 组件名优先
  });

  it("徽章文案优先级：data-slot 组件名 → 标签名（无组件痕迹时）", () => {
    const trigger = document.createElement("button");
    trigger.setAttribute("data-slot", "dialog-trigger");
    const nav = document.createElement("nav");
    document.body.append(trigger, nav);

    sendToChild(encodeAnnotate("enter", "select"));
    stubHitTest(trigger);
    moveOn(findOverlay(), 40, 60);
    expect(findBadge().textContent).toBe("dialog-trigger");

    stubHitTest(nav); // 命中换元素：徽章换文案、前元素高亮还原
    moveOn(findOverlay(), 80, 90);
    expect(findBadge().textContent).toBe("nav");
    expect(trigger.style.outline).toBe(""); // 行内 outline 还原（不吃页面自身样式）
    expect(nav.style.outline).toContain("rgb(59, 130, 246)");
  });

  it("鼠标离开视口（遮罩 mouseleave）：高亮与徽章收场", () => {
    const card = document.createElement("div");
    card.textContent = "订单卡片";
    document.body.appendChild(card);
    stubHitTest(card);
    sendToChild(encodeAnnotate("enter", "select"));
    moveOn(findOverlay(), 40, 60);
    expect(findBadge().style.display).toBe("block");

    findOverlay().dispatchEvent(new MouseEvent("mouseleave"));

    expect(card.style.outline).toBe("");
    expect(findBadge().style.display).toBe("none");
  });

  it("退出标注态：高亮还原、徽章随遮罩隐藏，页面交还操作权", () => {
    const card = document.createElement("div");
    card.textContent = "订单卡片";
    document.body.appendChild(card);
    stubHitTest(card);
    sendToChild(encodeAnnotate("enter", "select"));
    moveOn(findOverlay(), 40, 60);

    sendToChild(encodeAnnotate("exit"));

    expect(card.style.outline).toBe("");
    expect(getComputedStyle(findBadge()).display).toBe("none");
    expect(getComputedStyle(findOverlay()).display).toBe("none");
  });

  it("圈选拖拽中悬停反馈让位（拖框是当下反馈），收尾后 hover 恢复", () => {
    const card = document.createElement("div");
    card.textContent = "订单卡片";
    document.body.appendChild(card);
    stubHitTest(card);
    sendToChild(encodeAnnotate("enter", "circle"));
    const overlay = findOverlay();

    pointerOn(overlay, "pointerdown", 100, 100); // 起拖即收悬停场
    moveOn(overlay, 150, 150);
    expect(findBadge().style.display).toBe("none"); // 拖拽中不更新悬停

    pointerOn(overlay, "pointerup", 150, 150);
    expect(posted).toHaveLength(1); // 区域锚照发
    moveOn(overlay, 160, 160); // 收尾后 hover 反馈恢复
    expect(findBadge().style.display).toBe("block");
    expect(card.style.outline).toContain("rgb(59, 130, 246)");
  });
});
