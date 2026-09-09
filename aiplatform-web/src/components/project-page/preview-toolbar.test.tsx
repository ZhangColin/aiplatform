import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { PreviewToolbar } from "./preview-toolbar";

// 预览底部浮动工具条（#127 置灰待启用）：三能力（选择/圈选/评论）与「改字」皆
// 置灰不可点——圈注标注脚本未注入主应用（归网关 #122），诚实呈现「未接好」。
// 纯呈现组件无 props/hooks，renderToStaticMarkup 直断言禁用态。
describe("PreviewToolbar · 置灰待启用（#127 网关注入前占位）", () => {
  it("四键全 disabled：三能力与改字 aria-label 皆带「待启用」", () => {
    const html = renderToStaticMarkup(<PreviewToolbar />);

    for (const label of ["选择组件", "画笔圈选", "评论", "直接改文字"]) {
      const tag = html.match(new RegExp(`<button[^>]*aria-label="${label}（待启用）"[^>]*>`))![0];
      expect(tag).toContain("disabled");
    }
  });

  it("形态位占位标签仍在：出「圈一下」（非标注态入口）", () => {
    const html = renderToStaticMarkup(<PreviewToolbar />);

    expect(html).toContain("圈一下");
  });
});
