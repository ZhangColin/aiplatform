import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { ExecEntryBlock } from "./terminal-panel";

// 终端记录块的 SSR 断言（node 环境）。TerminalPanel 全量组件依赖 react-query
// （useProjectJourney → useQuery），在无 QueryClient 的 SSR 下不可直渲；但记录块
// 是纯 props 呈现，单独渲验证「非 0 退出码如实呈现」的核心口径。

describe("ExecEntryBlock（终端执行记录，issue #42）", () => {
  it("stdout / stderr 原样呈现 + `$ command` 头 + exit 0 绿", () => {
    const html = renderToStaticMarkup(
      <ExecEntryBlock command="ls -la" result={{ stdout: "app\nsrc\n", stderr: "", exitCode: 0 }} />,
    );
    expect(html).toContain("ls -la");
    expect(html).toContain("app");
    expect(html).toContain("exit 0");
    expect(html).toContain("text-emerald-600");
  });

  it("非 0 退出码 = 命令失败：exit N 红 + stderr 红呈现", () => {
    const html = renderToStaticMarkup(
      <ExecEntryBlock
        command="ls no-such"
        result={{ stdout: "", stderr: "No such file or directory\n", exitCode: 127 }}
      />,
    );
    expect(html).toContain("exit 127");
    expect(html).toContain("No such file or directory");
    expect(html).toContain("text-destructive");
  });

  it("无 stdout / stderr 时不渲染空块（如实：空输出不出假占位）", () => {
    const html = renderToStaticMarkup(
      <ExecEntryBlock command="touch a.txt" result={{ stdout: "", stderr: "", exitCode: 0 }} />,
    );
    expect(html).toContain("touch a.txt");
    expect(html).toContain("exit 0");
    expect(html).not.toContain("<pre");
  });
});
