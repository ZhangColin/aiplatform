import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { avatarTint, ProjectAvatar } from "./project-avatar";

describe("ProjectAvatar（项目首字色块）", () => {
  it("渲染项目名首字；同名同色（跨侧栏/首页一致）", () => {
    const a = renderToStaticMarkup(<ProjectAvatar name="巷口花店小程序" />);
    const b = renderToStaticMarkup(<ProjectAvatar name="巷口花店小程序" />);
    expect(a).toContain("巷");
    expect(a).toBe(b);
  });

  it("色板哈希稳定且有色系类名", () => {
    for (const name of ["花店", "餐厅点单", "瑜伽馆预约", "社区团购站", "宠物医院"]) {
      expect(avatarTint(name)).toMatch(/^bg-(rose|amber|emerald|sky|violet)-100 /);
    }
  });

  it("空名落中性灰（未命名项目）", () => {
    expect(avatarTint("")).toBe("bg-muted text-muted-foreground");
    expect(avatarTint("   ")).toBe("bg-muted text-muted-foreground");
  });
});
