// @vitest-environment happy-dom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { InterviewQuotaChip } from "./interview-quota-chip";

afterEach(cleanup);

describe("InterviewQuotaChip（#538 访谈①「第 N/5」服务端单源呈现）", () => {
  it("服务端读面在场：按服务端数原样呈现「已答 2/5 问」（前端不自算）", () => {
    render(<InterviewQuotaChip quota={{ answered: 2, total: 5 }} />);

    const chip = screen.getByText("已答 2/5 问");
    expect(chip).toBeTruthy();
    expect(chip.closest("[data-interview-quota]")).toBeTruthy();
  });

  it("额度读尽（5/5）同样原样呈现", () => {
    render(<InterviewQuotaChip quota={{ answered: 5, total: 5 }} />);

    expect(screen.getByText("已答 5/5 问")).toBeTruthy();
  });

  it("读面缺席（非访谈期——付费翻转 / PRD 已产出 / 已生成）：不渲染 chip", () => {
    const { container } = render(<InterviewQuotaChip quota={null} />);

    expect(container.querySelector("[data-interview-quota]")).toBeNull();
    expect(screen.queryByText(/已答/)).toBeNull();
  });
});
