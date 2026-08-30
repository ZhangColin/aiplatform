import type { components } from "@/lib/api/schema";

/**
 * 项目改名（issue #55，spec 0002 §4）：RenameProjectCommand 载荷构造与前端
 * 预校验的纯逻辑层。校验口径与后端建项目契约一致——空白拒绝（PRJ_005）、
 * 长度上限 100；先拦在字段就地提示，后端仍为最权威裁决。
 */

export type RenameProjectCommand = components["schemas"]["RenameProjectCommand"];

/** 项目名长度上限（后端与建项目同口径）。 */
export const PROJECT_NAME_MAX_LENGTH = 100;

export type RenameProjectInput = {
  name: string;
};

/** 字段级校验：返回错误文案（就地展示不进 toast），null = 可提交。 */
export function validateProjectName(name: string): string | null {
  const trimmed = name.trim();
  if (trimmed.length === 0) return "项目名不能为空";
  if (trimmed.length > PROJECT_NAME_MAX_LENGTH)
    return `项目名不能超过 ${PROJECT_NAME_MAX_LENGTH} 字`;
  return null;
}

/** 载荷构造：name trim 后入载荷（首尾空白不进载荷）。 */
export function buildRenameProjectCommand(input: RenameProjectInput): RenameProjectCommand {
  return { name: input.name.trim() };
}
