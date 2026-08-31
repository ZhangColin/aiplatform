import { describe, expect, it } from "vitest";

import {
  asPassthroughAgentEvent,
  asPlatformAgentEvent,
  asNotificationEvent,
  parseSseEnvelope,
} from "./events";

describe("parseSseEnvelope", () => {
  it("解析正本示例信封：type / payload / ts", () => {
    // 期望值来自后端 docs/spec/SSE事件清单.md 信封示例（独立真相源）
    const raw = JSON.stringify({
      type: "document-updated",
      payload: { projectId: "a1b2c3d4", documentType: "PRD" },
      ts: "2026-08-19T02:15:33.123Z",
    });

    expect(parseSseEnvelope(raw)).toEqual({
      type: "document-updated",
      payload: { projectId: "a1b2c3d4", documentType: "PRD" },
      ts: "2026-08-19T02:15:33.123Z",
    });
  });

  it("拒收非 JSON / 缺 type / payload 非对象：返回 null，不抛", () => {
    expect(parseSseEnvelope("not json")).toBeNull();
    expect(parseSseEnvelope('{"payload":{},"ts":"..."}')).toBeNull();
    expect(parseSseEnvelope('{"type":"x","payload":"str","ts":"..."}')).toBeNull();
    expect(parseSseEnvelope('"scalar"')).toBeNull();
  });
});

describe("asNotificationEvent", () => {
  it("已知 type 收窄为对应名册成员（正本 workspace-created 示例 payload）", () => {
    // 期望值来自正本「通道一」示例行
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "workspace-created",
        payload: {
          projectId: "a1b2c3d4",
          projectName: "官网 demo",
          container: "aiplatform-dev-a1b2c3d4",
          projectType: "WEBSITE",
          engine: "opencode",
        },
        ts: "2026-08-19T02:15:33.123Z",
      }),
    );

    expect(asNotificationEvent(env!)).toMatchObject({
      type: "workspace-created",
      payload: { projectId: "a1b2c3d4", projectName: "官网 demo" },
    });
  });

  it("通知通道是封闭集合：名册外 type（含已删事件 stage-changed）返回 null", () => {
    const env = parseSseEnvelope(
      JSON.stringify({ type: "future-event", payload: { projectId: "p1" }, ts: "" }),
    );
    expect(asNotificationEvent(env!)).toBeNull();
    const deleted = parseSseEnvelope(
      JSON.stringify({ type: "stage-changed", payload: { projectId: "p1" }, ts: "" }),
    );
    expect(asNotificationEvent(deleted!)).toBeNull();
  });

  it("project-renamed 按正本收窄（payload {projectId, projectName}）", () => {
    // 期望值来自正本「通道一」project-renamed 示例行（aiplatform-server #52）
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "project-renamed",
        payload: { projectId: "a1b2c3d4", projectName: "品牌官网" },
        ts: "2026-08-26T02:15:33.123Z",
      }),
    );

    expect(asNotificationEvent(env!)).toMatchObject({
      type: "project-renamed",
      payload: { projectId: "a1b2c3d4", projectName: "品牌官网" },
    });
  });
});

describe("agent 流收窄", () => {
  it("平台事件按名册收窄（正本 task-start 字段）；平台 type 不落入透传口", () => {
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "task-start",
        payload: { projectId: "a1b2c3d4", runId: "r1", prompt: "实现预约表单", model: "deepseek-v3" },
        ts: "",
      }),
    );
    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "task-start",
      payload: { projectId: "a1b2c3d4", runId: "r1", prompt: "实现预约表单" },
    });
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it("wait-raised 按正本收窄（payload {projectId, runId, kind, summary}）；平台 type 不落入透传口", () => {
    // 期望值来自正本「通道二」wait-raised 行
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "wait-raised",
        payload: { projectId: "a1b2c3d4", runId: "r1", sessionId: "s1", kind: "QUESTION", summary: "选哪个配色" },
        ts: "",
      }),
    );

    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "wait-raised",
      payload: { projectId: "a1b2c3d4", runId: "r1", kind: "QUESTION", summary: "选哪个配色" },
    });
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it("引擎透传事件：data 为引擎 part 原样，字符串 type 照收", () => {
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "text",
        payload: { projectId: "p1", runId: "r1", data: { text: "最终文本", part: { type: "text" } } },
        ts: "",
      }),
    );
    expect(asPlatformAgentEvent(env!)).toBeNull();
    expect(asPassthroughAgentEvent(env!)).toMatchObject({
      type: "text",
      payload: { projectId: "p1", runId: "r1", data: { text: "最终文本" } },
    });
  });

  it("透传是开放集合：名册外 type 收窄为透传形态而非 null；无 data 的杂音返回 null", () => {
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "part-updated",
        payload: { projectId: "p1", runId: "r1", data: { whatever: true } },
        ts: "",
      }),
    );
    const event = asPassthroughAgentEvent(env!);
    expect(event?.type).toBe("part-updated");
    expect(event?.payload.data).toEqual({ whatever: true });

    const noise = parseSseEnvelope(
      JSON.stringify({ type: "garbage", payload: { projectId: "p1" }, ts: "" }),
    );
    expect(asPassthroughAgentEvent(noise!)).toBeNull();
  });
});
