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

describe("智能体事件族收窄", () => {
  it("平台事件按名册收窄（正本 run-start 字段）；平台 type 不落入透传口", () => {
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "run-start",
        payload: { projectId: "a1b2c3d4", runId: "r1", prompt: "实现预约表单", model: "deepseek-v3" },
        ts: "",
      }),
    );
    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "run-start",
      payload: { projectId: "a1b2c3d4", runId: "r1", prompt: "实现预约表单" },
    });
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it("question-raised 按正本收窄（#83 起纯 QUESTION——payload {projectId, runId, summary, engineRef, data}）；平台 type 不落入透传口", () => {
    // 期望值来自正本「智能体事件族」question-raised 行
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "question-raised",
        payload: { projectId: "a1b2c3d4", runId: "r1", sessionId: "s1", summary: "选哪个配色", engineRef: "reply-1", data: { questions: [] } },
        ts: "",
      }),
    );

    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "question-raised",
      payload: { projectId: "a1b2c3d4", runId: "r1", summary: "选哪个配色", engineRef: "reply-1" },
    });
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it("run-failed 按正本收窄（payload {projectId, runId}）；平台 type 不落入透传口", () => {
    // 期望值来自正本「智能体事件族」run-failed 行（#56：编码 run 重试超限终态收口事件）
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "run-failed",
        payload: { projectId: "a1b2c3d4", runId: "r3" },
        ts: "",
      }),
    );

    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "run-failed",
      payload: { projectId: "a1b2c3d4", runId: "r3" },
    });
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it.each([
    "role-assigned",
    "run-created",
    "run-retrying",
    "fix-unchanged",
    "dispatch-stage",
    "live-text",
    "live-action",
    "live-step",
  ] as const)("退役事件 $type 不进名册：平台口与透传口都收不到（到达即 miss 忽略）", (type) => {
    // 收缩验收（#82）：退役五族零残留——解析层不认旧名，消费层零处理
    const env = parseSseEnvelope(
      JSON.stringify({ type, payload: { projectId: "p1", runId: "r1", data: { x: 1 } }, ts: "" }),
    );

    expect(asPlatformAgentEvent(env!)).toBeNull();
  });

  it("guide-reply 按正本收窄（payload {projectId, runId, prompt, label, text}）；平台 type 不落入透传口", () => {
    // 期望值来自正本「智能体事件族」guide-reply 行（#47：兜底轻引导回复）
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "guide-reply",
        payload: {
          projectId: "p1",
          runId: "r1",
          prompt: "你好呀",
          label: "平台",
          text: "我在这里帮您把系统做出来。",
        },
        ts: "",
      }),
    );

    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "guide-reply",
      payload: { projectId: "p1", runId: "r1", prompt: "你好呀", label: "平台", text: "我在这里帮您把系统做出来。" },
    });
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it("intercept-card 按正本收窄（payload {projectId, runId, interceptId, lane, prompt, sourceSelected, deductionSource?, sourcePoolBalance?}）；平台 type 不落入透传口", () => {
    // 期望值来自正本「智能体事件族」intercept-card 行（#533 计费②门②：试用
    // 拦截卡——被拦输入留存，显源池/随卡首次选源/确认扣减翻转）
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "intercept-card",
        payload: {
          projectId: "p1",
          runId: "run1",
          interceptId: "9012345678901",
          lane: "OPINION",
          prompt: "把主色调改成绿色",
          sourceSelected: true,
          deductionSource: 2,
          sourcePoolBalance: 10000,
        },
        ts: "",
      }),
    );

    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "intercept-card",
      payload: {
        projectId: "p1",
        runId: "run1",
        interceptId: "9012345678901",
        lane: "OPINION",
        prompt: "把主色调改成绿色",
        sourceSelected: true,
        deductionSource: 2,
        sourcePoolBalance: 10000,
      },
    });
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it.each([
    {
      type: "part-signal",
      payload: { projectId: "p1", runId: "r1", sessionId: "coder-p1", engine: "agentscope", signal: "derailed" },
    },
    {
      type: "part-action",
      payload: {
        projectId: "p1",
        runId: "r1",
        sessionId: "coder-p1",
        engine: "agentscope",
        toolCallId: "tc-1",
        toolName: "write_file",
        state: "started",
        label: "编写【代码文件】",
      },
    },
  ] as const)("消息部件事件 $type 按正本收窄为平台事件，不落入透传口", (frame) => {
    // 期望值来自正本「消息部件事件」节（#77 parts 契约；#608 后只余脱轨信号与
    // 工具动作两件）
    const env = parseSseEnvelope(JSON.stringify({ ...frame, ts: "" }));

    expect(asPlatformAgentEvent(env!)).toMatchObject(frame);
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it("part-hint（已退役 2026-11-12）：不再属平台事件面，存量事件两侧均不认（忽略）", () => {
    // 微指令是模型面指引、不进用户面——后端已停发；存量/未知 part-hint 帧按
    // 未知类型处理（平台口与透传口均不收窄，桥侧静默忽略）
    const env = parseSseEnvelope(JSON.stringify({
      type: "part-hint",
      payload: { projectId: "p1", runId: "r1", sessionId: "coder-p1", kind: "failure_stop_loss", text: "…" },
      ts: "",
    }));

    expect(asPlatformAgentEvent(env!)).toBeNull();
    expect(asPassthroughAgentEvent(env!)).toBeNull();
  });

  it("part-action 的 state 值域收窄（started/running/completed/failed 全生命周期）", () => {
    for (const state of ["started", "running", "completed", "failed"] as const) {
      const env = parseSseEnvelope(
        JSON.stringify({
          type: "part-action",
          payload: {
            projectId: "p1",
            runId: "r1",
            sessionId: "coder-p1",
            engine: "agentscope",
            toolCallId: "tc-1",
            toolName: "write_file",
            state,
            label: "编写【订单管理】",
          },
          ts: "",
        }),
      );
      const event = asPlatformAgentEvent(env!);
      expect(event?.type).toBe("part-action");
      if (event?.type === "part-action") {
        expect(event.payload.state).toBe(state);
      }
    }
  });

  it("part-action 失败留痕（#229）：failed 携 error（错误/stderr 首行截断）照收——与 label 各管各的额度", () => {
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "part-action",
        payload: {
          projectId: "p1",
          runId: "r1",
          sessionId: "coder-p1",
          engine: "agentscope",
          toolCallId: "tc-1",
          toolName: "execute",
          state: "failed",
          label: "npm test",
          error: "npm err! code ELIFECYCLE",
        },
        ts: "",
      }),
    );
    const event = asPlatformAgentEvent(env!);
    expect(event?.type).toBe("part-action");
    if (event?.type === "part-action") {
      expect(event.payload.error).toBe("npm err! code ELIFECYCLE");
    }
  });

  it.each(["part-text", "part-check", "part-plan"] as const)(
    "%s（#608 呈现面清场退役）：不再属平台事件面，存量事件两侧均不认（忽略）",
    (type) => {
      const env = parseSseEnvelope(JSON.stringify({
        type,
        payload: { projectId: "p1", runId: "r1", sessionId: "coder-p1", text: "…", steps: [], state: "checking" },
        ts: "",
      }));

      expect(asPlatformAgentEvent(env!)).toBeNull();
      expect(asPassthroughAgentEvent(env!)).toBeNull();
    },
  );

  it("run-start 携角色键（#77 引擎信息归一：role=CODER 标编码 run）", () => {
    const env = parseSseEnvelope(
      JSON.stringify({
        type: "run-start",
        payload: { projectId: "p1", runId: "r1", prompt: "做系统", model: "m", engine: "agentscope", role: "CODER" },
        ts: "",
      }),
    );
    expect(asPlatformAgentEvent(env!)).toMatchObject({
      type: "run-start",
      payload: { role: "CODER" },
    });
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
