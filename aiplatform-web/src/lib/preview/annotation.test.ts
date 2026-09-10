import { describe, expect, it } from "vitest";

import {
  annotationLabel,
  annotationSummary,
  encodeAnnotate,
  parseAnchorEvent,
  parseAnnotationAttachment,
  parseExitEvent,
  renderAnnotationsText,
  toAttachmentCommand,
} from "./annotation";

/**
 * 圈注协议与载荷（#97 圈注 B 档，纯函数）：预览 iframe 与平台前端的 postMessage
 * 协议 + 结构化 DOM 锚载荷。双侧契约——服务端注入脚本（docker/workspace/
 * annotation.js）与本节同源。容错收窄：坏消息/伪锚/缺字段不落地（返回 null/空）。
 */
describe("圈注协议解析（postMessage 锚）", () => {
  it("解析点选锚：selector + text", () => {
    const draft = parseAnchorEvent({
      __aiplatform__: true,
      type: "anchor",
      payload: { kind: "select", anchor: { selector: "button.submit", text: "提交订单" }, note: "" },
    });

    expect(draft).toEqual({
      kind: "select",
      anchor: { selector: "button.submit", text: "提交订单" },
      note: "",
    });
  });

  it("解析圈选锚：region 矩形（选择器可缺省）", () => {
    const draft = parseAnchorEvent({
      __aiplatform__: true,
      type: "anchor",
      payload: { kind: "circle", anchor: { region: { x: 1, y: 2, width: 3, height: 4 } }, note: "" },
    });

    expect(draft).toEqual({
      kind: "circle",
      anchor: { region: { x: 1, y: 2, width: 3, height: 4 } },
      note: "",
    });
  });

  it("坏消息/伪锚/缺锚不落地：返回 null", () => {
    expect(parseAnchorEvent(null)).toBeNull();
    expect(parseAnchorEvent({ type: "anchor" })).toBeNull(); // 缺 __aiplatform__
    expect(parseAnchorEvent({ __aiplatform__: true, type: "other" })).toBeNull();
    expect(
      parseAnchorEvent({ __aiplatform__: true, type: "anchor", payload: { kind: "select" } }),
    ).toBeNull(); // 缺 anchor
    expect(
      parseAnchorEvent({
        __aiplatform__: true,
        type: "anchor",
        payload: { kind: "weird", anchor: { selector: "a" } },
      }),
    ).toBeNull(); // 未知 kind
  });

  it("退出信封（注入脚本 Esc 退出）识别：顶层 { __aiplatform__, type: 'exit' }", () => {
    expect(parseExitEvent({ __aiplatform__: true, type: "exit" })).toBe(true);
    // #134 前的错位形状：退出信号包进锚信封 payload、父窗查顶层——信号必被丢弃（回归锚）
    expect(
      parseExitEvent({ __aiplatform__: true, type: "anchor", payload: { __exit__: true } }),
    ).toBe(false);
    expect(parseExitEvent({ type: "exit" })).toBe(false); // 缺 __aiplatform__ 印记
    expect(parseExitEvent({ type: "anchor" })).toBe(false);
  });
});

describe("圈注载荷（对话史水合 / 发送映射）", () => {
  it("附件命令载荷 → 条目（attachmentType 必须 annotation）", () => {
    const draft = parseAnnotationAttachment({
      attachmentType: "annotation",
      annotation: { kind: "comment", anchor: { selector: "div.banner", text: "横幅" }, note: "改成红色" },
    });

    expect(draft).toEqual({
      kind: "comment",
      anchor: { selector: "div.banner", text: "横幅" },
      note: "改成红色",
    });
    expect(parseAnnotationAttachment({ attachmentType: "file", annotation: null })).toBeNull();
  });

  it("条目 → 发送命令附件（本地 id 不进载荷）", () => {
    const cmd = toAttachmentCommand({
      kind: "circle",
      anchor: { region: { x: 0, y: 0, width: 10, height: 20 } },
      note: "",
    });

    expect(cmd).toEqual({
      attachmentType: "annotation",
      annotation: { kind: "circle", anchor: { region: { x: 0, y: 0, width: 10, height: 20 } }, note: "" },
    });
  });

  it("标注类型 → 用户面标签", () => {
    expect(annotationLabel("select")).toBe("点选");
    expect(annotationLabel("circle")).toBe("圈选");
    expect(annotationLabel("comment")).toBe("评论");
  });

  it("可读摘要：点选/评论用文本，圈选用矩形", () => {
    expect(annotationSummary({ kind: "select", anchor: { text: "提交订单" }, note: "" })).toBe("提交订单");
    expect(
      annotationSummary({
        kind: "circle",
        anchor: { region: { x: 100, y: 200, width: 300, height: 80 } },
        note: "",
      }),
    ).toBe("区域 (100, 200) 300×80");
  });

  it("圈选摘要：带 DOM 参照（文本）时矩形后补参照", () => {
    expect(
      annotationSummary({
        kind: "circle",
        anchor: { region: { x: 100, y: 200, width: 300, height: 80 }, text: "订单卡片" },
        note: "",
      }),
    ).toBe("区域 (100, 200) 300×80 · 订单卡片");
  });

  it("圈注条目 → 文本行（作答通道渲染进答复文本）", () => {
    expect(
      renderAnnotationsText([
        { kind: "select", anchor: { text: "提交订单" }, note: "" },
        { kind: "comment", anchor: { text: "横幅" }, note: "改成红色" },
      ]),
    ).toBe("点选：提交订单；评论：横幅");
    expect(renderAnnotationsText([])).toBe("");
  });

  it("父 → 子进出标注态的信封", () => {
    expect(encodeAnnotate("enter", "select")).toEqual({
      __aiplatform__: true,
      type: "annotate",
      mode: "enter",
      tool: "select",
    });
    expect(encodeAnnotate("exit")).toEqual({ __aiplatform__: true, type: "annotate", mode: "exit", tool: undefined });
  });
});
