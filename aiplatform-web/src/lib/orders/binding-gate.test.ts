import { describe, expect, it } from "vitest";

import { ApiError } from "@/lib/api/api-error";

import { ORDER_BINDING_REQUIRED_CODE, isBindingGateError } from "./binding-gate";

describe("isBindingGateError（#328 签约门判据）", () => {
  it("业务码常量即 ORD_017（域码 5×1000＋17＝5017）", () => {
    expect(ORDER_BINDING_REQUIRED_CODE).toBe(5017);
  });

  it("ORD_017（码 5017）→ true（认业务码非 status）", () => {
    expect(isBindingGateError(new ApiError({ status: 403, code: 5017 }))).toBe(true);
  });

  it("别的业务码 / 非 ApiError / 空值 → false", () => {
    expect(isBindingGateError(new ApiError({ status: 409, code: 5003 }))).toBe(false);
    expect(isBindingGateError(new Error("x"))).toBe(false);
    expect(isBindingGateError(undefined)).toBe(false);
  });
});
