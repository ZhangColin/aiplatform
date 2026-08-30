import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { AgentRun } from "@/lib/store/agent-streams";

import { MessageFeed } from "./message-feed";

// 运行错误卡（issue #61，真机事故：BA 起跑即死只余一帧 error）：error 段是用户
// 唯一的失败信号——主文案说人话，后端原文（常为英文技术细节）以等宽小字保留，
// 供反馈截图。本组件为纯渲染（run 走 props），SSR 静态断言即可。

/** 起跑即死口径的 stub run：bridge 收到无前置的 error 帧补建（见 bridge.test）。 */
const deadRun: AgentRun = {
  runId: "run1",
  projectId: "p1",
  status: "error",
  segments: [
    {
      kind: "error",
      id: "run1:1",
      message: "Failed to create model: Environment variable DEEPSEEK_API_KEY is required",
    },
  ],
};

describe("MessageFeed · 运行错误卡（issue #61）", () => {
  it("error 段渲染醒目错误卡：中文主文案 + 后端原文小字 + 重试引导", () => {
    const html = renderToStaticMarkup(<MessageFeed run={deadRun} />);
    expect(html).toContain("运行遇到问题，暂时没能继续");
    expect(html).toContain("DEEPSEEK_API_KEY");
    expect(html).toContain("再发一次即可重试");
    // 无障碍口径：错误卡是 live region
    expect(html).toContain('role="alert"');
  });

  it("message 为空：只出主文案与引导，不留空原文块", () => {
    const html = renderToStaticMarkup(
      <MessageFeed run={{ ...deadRun, segments: [{ kind: "error", id: "run1:1", message: "" }] }} />,
    );
    expect(html).toContain("运行遇到问题，暂时没能继续");
    expect(html).toContain("再发一次即可重试");
  });

  it("无 error 段的正常 run：错误卡不出现", () => {
    const html = renderToStaticMarkup(
      <MessageFeed
        run={{
          ...deadRun,
          status: "finished",
          segments: [{ kind: "text", id: "run1:1", data: { text: "顾问正在梳理" } }],
        }}
      />,
    );
    expect(html).not.toContain("运行遇到问题");
    expect(html).toContain("顾问正在梳理");
  });
});
