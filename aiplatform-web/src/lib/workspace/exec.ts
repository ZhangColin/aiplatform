import type { components } from "@/lib/api/schema";

/**
 * 工作区 exec 的载荷构造与结果归化（issue #42，spec 0001 §5「终端」）：
 * `POST /workspaces/{workspaceId}/exec` 请求体 `{command}`（dev 容器内 sh -c 执行），
 * 响应 `{stdout, stderr, exitCode}`。**非 0 退出码是命令失败，不是环境故障**——
 * 归化只做防御性收窄（缺省字段给空/0），失败语义由呈现层按 exitCode 判断，HTTP
 * 错误（4xx/5xx）才是环境故障、走 toast。纯函数，无副作用。
 */

export type WorkspaceExecCommand = components["schemas"]["WorkspaceExecCommand"];
export type ExecResultResponse = components["schemas"]["ExecResultResponse"];

/** 归化后的执行结果：三字段必有（stdout/stderr 缺省空串、exitCode 缺省 0）。 */
export type ExecResult = {
  stdout: string;
  stderr: string;
  exitCode: number;
};

/** 载荷构造：trim 后非空才发；纯空白 / 空串返回 null（表单侧拦截不请求）。 */
export function buildExecCommand(input: string): WorkspaceExecCommand | null {
  const command = input.trim();
  return command ? { command } : null;
}

/**
 * 结果归化：stdout/stderr 缺省空串、exitCode 缺省 0。后端正常都会带全三字段，
 * 缺省值是防御兜底（shape 未锁死的字段不建假语义，呈现不崩）。
 */
export function normalizeExecResult(raw: ExecResultResponse): ExecResult {
  return {
    stdout: raw.stdout ?? "",
    stderr: raw.stderr ?? "",
    exitCode: raw.exitCode ?? 0,
  };
}

/** 命令失败判定：非 0 退出码 = 命令自身失败（如实呈现，非环境故障）。 */
export function isCommandFailure(result: ExecResult): boolean {
  return result.exitCode !== 0;
}
