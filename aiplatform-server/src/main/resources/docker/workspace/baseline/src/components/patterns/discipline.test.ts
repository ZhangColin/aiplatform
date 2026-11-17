import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";

/**
 * 模式件静态纪律（ADR-0051 准入判据①「呈现全参数化」的机械防线）：
 * 只吃语义 token——源码层面禁裸色（hex/oklch/rgb/hsl）、禁任意值类
 * （p-[13px] 等 -[...] 形）、禁 inline style。换肤只动 :root 的前提＝件内
 * 不出现任何写死呈现值。与三态 UX 件「样式吃项目语义 token」测试同律，
 * 本测试扫源码全量（不依赖渲染路径覆盖）。路径走 vitest 工程根 cwd
 * （happy-dom 环境下 import.meta.url 无 file scheme）。
 */

const sources = {
  "ValidatedForm.tsx": readFileSync(
    "src/components/patterns/ValidatedForm.tsx",
    "utf8",
  ),
  "FilterableList.tsx": readFileSync(
    "src/components/patterns/FilterableList.tsx",
    "utf8",
  ),
};

describe("模式件·静态纪律（呈现零主张）", () => {
  for (const [name, source] of Object.entries(sources)) {
    it(`${name}：无裸色（hex/oklch/rgb/hsl 色值字面量）`, () => {
      expect(source).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
      expect(source).not.toMatch(/oklch\(|rgba?\(|hsla?\(/i);
    });

    it(`${name}：无任意值工具类（-[...] 形）`, () => {
      expect(source).not.toMatch(/-\[[^\]]+\]/);
    });

    it(`${name}：无 inline style`, () => {
      expect(source).not.toMatch(/style=\{/);
    });
  }
});

describe("模式件·tree-shake 面（纯 ESM 无导入副作用）", () => {
  // 未被应用引用的基座件＝不进模块图（Next 生产构建按图收录，天然消除）；
  // 被引用但部分使用时能否摇掉取决于模块纯度——导入即执行顶层代码的模块
  // 不可摇。此处钉住「导入零副作用」：无 console 输出、无 DOM 落子。
  it("导入两件：console 静默、document 无落子、导出面完整", async () => {
    const consoleSpy = vi.spyOn(console, "log").mockImplementation(() => {});
    try {
      const form = await import("./ValidatedForm");
      const list = await import("./FilterableList");
      expect(typeof form.ValidatedForm).toBe("function");
      expect(typeof list.FilterableList).toBe("function");
      expect(consoleSpy).not.toHaveBeenCalled();
      expect(document.body.children).toHaveLength(0);
    } finally {
      consoleSpy.mockRestore();
    }
  });
});
