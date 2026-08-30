import { describe, expect, it } from "vitest";

import {
  buildWorkbenchDeepLink,
  parseWorkbenchDeepLink,
  parseWorkbenchDeepLinkFromSearchParams,
} from "./deep-link";

describe("buildWorkbenchDeepLink", () => {
  it("wait 型：waitId 编码进 query", () => {
    expect(buildWorkbenchDeepLink({ kind: "wait", waitId: "w/1 2" }, "123")).toBe(
      "/dev/projects/123?wait=w%2F1%202",
    );
  });

  it("gate / tasks 型：focus 参数", () => {
    expect(buildWorkbenchDeepLink({ kind: "gate" }, "123")).toBe("/dev/projects/123?focus=gate");
    expect(buildWorkbenchDeepLink({ kind: "tasks" }, "123")).toBe("/dev/projects/123?focus=tasks");
  });

  it("base 可覆写：需求端工作台落 /projects（issue #49）", () => {
    expect(buildWorkbenchDeepLink({ kind: "wait", waitId: "w1" }, "9", "/projects")).toBe(
      "/projects/9?wait=w1",
    );
    expect(buildWorkbenchDeepLink({ kind: "gate" }, "9", "/projects")).toBe(
      "/projects/9?focus=gate",
    );
  });
});

describe("parseWorkbenchDeepLink", () => {
  it("wait 参数 → wait 深链", () => {
    expect(parseWorkbenchDeepLink(new URLSearchParams("?wait=w789"))).toEqual({
      kind: "wait",
      waitId: "w789",
    });
  });

  it("focus=gate / focus=tasks → 对应深链", () => {
    expect(parseWorkbenchDeepLink(new URLSearchParams("?focus=gate"))).toEqual({ kind: "gate" });
    expect(parseWorkbenchDeepLink(new URLSearchParams("?focus=tasks"))).toEqual({ kind: "tasks" });
  });

  it("无匹配参数 / 未知 focus → null", () => {
    expect(parseWorkbenchDeepLink(new URLSearchParams())).toBeNull();
    expect(parseWorkbenchDeepLink(new URLSearchParams("?foo=bar"))).toBeNull();
    expect(parseWorkbenchDeepLink(new URLSearchParams("?focus=unknown"))).toBeNull();
  });
});

describe("parseWorkbenchDeepLinkFromSearchParams", () => {
  it("服务端 searchParams record（含 string[] / undefined 值）→ 深链，重复键取首值", () => {
    expect(parseWorkbenchDeepLinkFromSearchParams({ wait: ["w1", "w2"] })).toEqual({
      kind: "wait",
      waitId: "w1",
    });
    expect(parseWorkbenchDeepLinkFromSearchParams({ focus: "gate" })).toEqual({ kind: "gate" });
    expect(parseWorkbenchDeepLinkFromSearchParams({ focus: undefined, foo: "bar" })).toBeNull();
  });
});
