import { describe, expect, it } from "vitest";

import {
  MATERIAL_UPLOAD_LIMIT_BYTES,
  isUploadableImageName,
  parseImageAttachment,
  renderMaterialsText,
  toImageAttachmentCommand,
} from "./materials";

/**
 * 图片物料纯逻辑（#286）：格式判定（后端 extensionOf 同构）、image 形态载荷的
 * 构造与容错解析（水合/乐观回显共用）、作答通道文本行渲染。
 */
describe("materials · 上传格式与上限（后端口径镜像）", () => {
  it("五格式大小写不敏感；非图片/无扩展名不符", () => {
    for (const name of ["a.png", "b.jpg", "c.jpeg", "d.webp", "e.gif", "f.svg", "g.PNG", "h.WebP"]) {
      expect(isUploadableImageName(name), name).toBe(true);
    }
    for (const name of ["价目表.pdf", "截图.mov", "无扩展名", ""]) {
      expect(isUploadableImageName(name), name).toBe(false);
    }
  });

  it("上限 = 10MB（后端 PRJ_043 同口径）", () => {
    expect(MATERIAL_UPLOAD_LIMIT_BYTES).toBe(10 * 1024 * 1024);
  });
});

describe("materials · image 形态载荷", () => {
  it("已上传物料 → 发送命令（载荷＝路径引用，不带字节）", () => {
    expect(toImageAttachmentCommand({ name: "logo.png", path: "materials/123-logo.png" })).toEqual({
      attachmentType: "image",
      name: "logo.png",
      path: "materials/123-logo.png",
    });
  });

  it("对话史载荷 → 物料条目（name 缺省回落路径末段）", () => {
    expect(
      parseImageAttachment({
        attachmentType: "image",
        name: "海报.png",
        path: "materials/3897654321098765432-海报.png",
      }),
    ).toEqual({ path: "materials/3897654321098765432-海报.png", name: "海报.png" });

    expect(parseImageAttachment({ attachmentType: "image", path: "materials/123-x.png" })?.name).toBe(
      "123-x.png",
    );
  });

  it("容错收窄：非 image 形态/缺路径/坏形状即 null（圈注不误读）", () => {
    expect(parseImageAttachment(null)).toBeNull();
    expect(parseImageAttachment({ attachmentType: "annotation", annotation: {} })).toBeNull();
    expect(parseImageAttachment({ attachmentType: "image", name: "无路径.png" })).toBeNull();
    expect(parseImageAttachment({ attachmentType: "image", path: 123 })).toBeNull();
  });
});

describe("materials · 作答通道文本行（无附件位的渲染进文本）", () => {
  it("逐条编号与发言通道 AttachmentPrompt 同构；空集空串", () => {
    expect(renderMaterialsText([])).toBe("");
    expect(
      renderMaterialsText([
        { name: "logo.png", path: "materials/1-logo.png" },
        { name: "海报.png", path: "materials/2-海报.png" },
      ]),
    ).toBe("1. logo.png（materials/1-logo.png）；2. 海报.png（materials/2-海报.png）");
  });
});
