// @vitest-environment happy-dom
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { beforeEach, describe, expect, it } from "vitest";

import { encodeAnnotate, parseAnchorEvent, parseExitEvent } from "./annotation";

/**
 * 圈注契约测试缝（#134 主缝）：happy-dom 装载**真实注入脚本**（服务端资源单源
 * aiplatform-server/src/main/resources/docker/workspace/annotation.js，经 ADR-0014
 * 网关注入预览页——测试直读文件、不复制），从外部驱动进出标注态与真实 DOM 事件，
 * 断言脚本发出的每个信封都能被父窗解析器原样解析（跨帧双侧契约）。Esc 信封
 * 契约错位（#134 前：退出信号包进锚信封 payload、父窗查顶层，必被丢弃）由本缝
 * 拦截。只测外部可观测行为（发出的信封、DOM 副作用），不测脚本内部函数与私有状态。
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
