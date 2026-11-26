import { describe, expect, it } from "vitest";

import {
  PRD_PATH,
  downloadFileUrl,
  downloadFileNameOf,
  formatFileSize,
  rawFileUrl,
  relativeFormOf,
} from "./files";

// 工作区文件取件口径（#608 呈现面清场后：文件树与内容点看退役，只留取件 URL /
// 落盘名 / 字节数的纯逻辑）。

describe("PRD_PATH", () => {
  it("与后端 WorkspaceLayout.PRD 同值", () => {
    expect(PRD_PATH).toBe("docs/PRD.md");
  });
});

describe("formatFileSize", () => {
  it("B/KB/MB 三档，一位小数", () => {
    expect(formatFileSize(512)).toBe("512 B");
    expect(formatFileSize(1536)).toBe("1.5 KB");
    expect(formatFileSize(2 * 1024 * 1024)).toBe("2 MB");
  });
});

describe("rawFileUrl · 文件直出 URL", () => {
  it("同源 /api 直链，path 整体编码（不经 api client——二进制不走 JSON）", () => {
    expect(rawFileUrl("p1", "materials/ref.png")).toBe(
      "/api/projects/p1/files/raw?path=materials%2Fref.png",
    );
    // 特殊字符（空格/引号/中文）原样过 URL 编码，不破 query 结构
    expect(rawFileUrl("p1", "materials/我的 图#1.png")).toBe(
      `/api/projects/p1/files/raw?path=${encodeURIComponent("materials/我的 图#1.png")}`,
    );
  });

  it("工作区锚定形（前导 /）归一为相对形——工作区锚定路径单点归一", () => {
    expect(rawFileUrl("p1", "/assets/home-1.html")).toBe(
      "/api/projects/p1/files/raw?path=assets%2Fhome-1.html",
    );
  });
});

describe("downloadFileUrl · 单文件下载 URL（#287 支付门）", () => {
  it("同源 /api 直链，path 整体编码（同 raw 直链形——门判定归后端，前端不预判）", () => {
    expect(downloadFileUrl("p1", "exports/海报-终稿.png")).toBe(
      `/api/projects/p1/files/download?path=${encodeURIComponent("exports/海报-终稿.png")}`,
    );
    // 锚定形归一同 rawFileUrl
    expect(downloadFileUrl("p1", "/assets/home-1.html")).toBe(
      "/api/projects/p1/files/download?path=assets%2Fhome-1.html",
    );
  });
});

describe("relativeFormOf · 两形归一", () => {
  it("锚定形剥前导斜杠；相对形原样", () => {
    expect(relativeFormOf("/a/b")).toBe("a/b");
    expect(relativeFormOf("a/b")).toBe("a/b");
  });
});

describe("downloadFileNameOf · 落盘文件名消毒（与后端 fileNameOf 同口径）", () => {
  it("路径末段直取；中文等非 ASCII 原样保留", () => {
    expect(downloadFileNameOf("materials/ref.png")).toBe("ref.png");
    expect(downloadFileNameOf("exports/海报 终稿.png")).toBe("海报 终稿.png");
  });

  it("控制字符与引号剔除（blob 锚点不走响应头，消毒镜像在后端同口径）", () => {
    expect(downloadFileNameOf("materials/ba\"d.png")).toBe("bad.png");
    expect(downloadFileNameOf("materials/line\nbreak.png")).toBe("linebreak.png");
  });
});
