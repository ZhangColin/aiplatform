import { describe, expect, it } from "vitest";

import {
  buildExecCommand,
  isCommandFailure,
  normalizeExecResult,
  type ExecResultResponse,
} from "./exec";

describe("exec 载荷构造（issue #42：command trim + 空值拦截）", () => {
  it("trim 首尾空白后携带 command", () => {
    expect(buildExecCommand("  ls -la  ")).toEqual({ command: "ls -la" });
  });

  it("内部空白原样保留（不做分词/改写）", () => {
    expect(buildExecCommand("echo 'a  b'")).toEqual({ command: "echo 'a  b'" });
  });

  it("空串 / 纯空白返回 null（不请求）", () => {
    expect(buildExecCommand("")).toBeNull();
    expect(buildExecCommand("   ")).toBeNull();
    expect(buildExecCommand("\t\n")).toBeNull();
  });
});

describe("exec 结果归化（issue #42：stdout/stderr/exitCode 缺省防御）", () => {
  it("全字段透传：stdout / stderr / exitCode", () => {
    const raw: ExecResultResponse = { stdout: "ok\n", stderr: "", exitCode: 0 };
    expect(normalizeExecResult(raw)).toEqual({ stdout: "ok\n", stderr: "", exitCode: 0 });
  });

  it("缺省字段归空串 / 0（shape 未锁死防御）", () => {
    expect(normalizeExecResult({})).toEqual({ stdout: "", stderr: "", exitCode: 0 });
    expect(normalizeExecResult({ stdout: "a" })).toEqual({ stdout: "a", stderr: "", exitCode: 0 });
  });

  it("非 0 退出码原样保留（命令失败如实呈现，非环境故障）", () => {
    const raw: ExecResultResponse = { stdout: "", stderr: "No such file\n", exitCode: 127 };
    expect(normalizeExecResult(raw)).toEqual({
      stdout: "",
      stderr: "No such file\n",
      exitCode: 127,
    });
  });
});

describe("isCommandFailure：非 0 退出码 = 命令失败", () => {
  it("0 成功，非 0 失败", () => {
    expect(isCommandFailure({ stdout: "", stderr: "", exitCode: 0 })).toBe(false);
    expect(isCommandFailure({ stdout: "", stderr: "", exitCode: 1 })).toBe(true);
    expect(isCommandFailure({ stdout: "", stderr: "", exitCode: 127 })).toBe(true);
  });
});
