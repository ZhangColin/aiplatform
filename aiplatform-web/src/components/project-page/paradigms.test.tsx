import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PARADIGMS, paradigmOf } from "./paradigms";

/**
 * 范式注册表契约（#79 验收锚）：注册即挂载——id 唯一、每面有 label/blurb/icon
 * （「+ 新标签页」可挂载浏览的最低门槛）、「系统」居首默认主舞台、「文档」
 * 默认挂载、文件/数据/订单/设置/终端按需（defaultOn=false）。各面内容呈现归
 * 各面板测试（system-panel / files-panel / order-panel）。
 */
describe("PARADIGMS · 范式注册表", () => {
  it("id 唯一（挂载/关闭按 id 键控，重号会串台）", () => {
    const ids = PARADIGMS.map((p) => p.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it("「系统」居首（默认主舞台）、「文档」默认挂载；其余按需", () => {
    expect(PARADIGMS[0]).toMatchObject({ id: "system", label: "系统" });
    expect(PARADIGMS.filter((p) => p.defaultOn).map((p) => p.id)).toEqual(["system", "docs"]);
    expect(PARADIGMS.filter((p) => !p.defaultOn).map((p) => p.id)).toEqual([
      "files",
      "data",
      "order",
      "terminal",
      "settings",
    ]);
  });

  it("每面可挂载浏览的最低门槛：label / blurb / icon / render 就位", () => {
    for (const p of PARADIGMS) {
      expect(p.label.trim().length, `${p.id} label`).toBeGreaterThan(0);
      expect(p.blurb.trim().length, `${p.id} blurb`).toBeGreaterThan(0);
      expect(renderToStaticMarkup(<>{p.icon}</>).length, `${p.id} icon`).toBeGreaterThan(0);
      expect(typeof p.render, `${p.id} render`).toBe("function");
    }
  });

  it("paradigmOf：按 id 取范式；未注册 id = undefined", () => {
    expect(paradigmOf("system")?.label).toBe("系统");
    expect(paradigmOf("nope")).toBeUndefined();
  });
});
