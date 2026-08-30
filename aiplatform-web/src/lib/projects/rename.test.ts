import { describe, expect, it } from "vitest";

import {
  buildRenameProjectCommand,
  PROJECT_NAME_MAX_LENGTH,
  validateProjectName,
} from "./rename";

// 项目改名（issue #55，spec 0002 §4）：校验口径与后端建项目契约一致——空白拒绝
// （PRJ_005）、长度上限 100。前端先拦就地提示，后端仍为最权威裁决。
describe("项目名校验（口径与创建一致）", () => {
  it("空名 / 纯空白：就地报「项目名不能为空」", () => {
    expect(validateProjectName("")).toBe("项目名不能为空");
    expect(validateProjectName("   ")).toBe("项目名不能为空");
  });

  it("超长（> 100 字）：报长度上限", () => {
    expect(validateProjectName("a".repeat(PROJECT_NAME_MAX_LENGTH + 1))).toBe(
      "项目名不能超过 100 字",
    );
  });

  it("边界恰好合法：100 字 / 普通名 / 首尾空白不判空", () => {
    expect(validateProjectName("a".repeat(PROJECT_NAME_MAX_LENGTH))).toBeNull();
    expect(validateProjectName("宠物医院预约官网")).toBeNull();
    expect(validateProjectName("  官网  ")).toBeNull();
  });
});

describe("改名载荷构造", () => {
  it("name trim 后入载荷（首尾空白不进载荷）", () => {
    const command = buildRenameProjectCommand({ name: "  在线预约平台  " });
    expect(command).toEqual({ name: "在线预约平台" });
  });
});
