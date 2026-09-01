import { describe, expect, it } from "vitest";

import { buildLoginRedirectUrl } from "./login-redirect";

describe("buildLoginRedirectUrl · 两入口共用契约（spec 0004 §2）", () => {
  it("returnTo 编码 pathname + search", () => {
    expect(buildLoginRedirectUrl("/projects/p1", "?tab=logs&x=1")).toBe(
      "/auth/login?returnTo=%2Fprojects%2Fp1%3Ftab%3Dlogs%26x%3D1",
    );
  });

  it("根路径无 search", () => {
    expect(buildLoginRedirectUrl("/", "")).toBe("/auth/login?returnTo=%2F");
  });
});
