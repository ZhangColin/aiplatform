import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type { BillingEntryView } from "@/lib/billing/api";
import type { ProjectSummary } from "@/lib/projects/list";

import { MyAccountView } from "./my-account-view";

// 请求面 mock 掉（无 QueryClient 可挂——呈现面断言归本文件，动作路由归 hooks 测试）
const meState = vi.hoisted(() => ({ loggedIn: true }));
const billingState = vi.hoisted(() => ({
  accountBalance: 12_345 as number | undefined,
  accountEntries: [] as BillingEntryView[],
  projectViews: new Map<string, { pool?: { balance?: number }; checkoutTarget: number; sourceSelected: boolean; deductionSourceName?: string; paid?: boolean; entries: BillingEntryView[] }>(),
  projects: [] as ProjectSummary[],
}));

vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: meState.loggedIn ? { accountId: "7", displayName: "测试用户" } : undefined }),
}));
vi.mock("@/hooks/use-billing", () => ({
  useMyBilling: () => ({ data: { accountPool: billingState.accountBalance === undefined ? undefined : { poolId: "p1", balance: billingState.accountBalance }, entries: billingState.accountEntries } }),
  useProjectBilling: (projectId: string) => ({ data: billingState.projectViews.get(projectId) ?? null }),
}));
vi.mock("@/hooks/use-projects", () => ({
  useProjectList: () => ({ data: billingState.projects }),
}));

const ENTRY: BillingEntryView = {
  entryId: "e1",
  kind: 1,
  kindName: "入账",
  projectId: undefined,
  amount: 5000,
  balanceAfter: 12345,
  note: "模拟收银台充值",
  operatorName: undefined,
  createdAt: "2026-11-16T12:00:00",
};

function renderView() {
  return renderToStaticMarkup(<MyAccountView />);
}

describe("MyAccountView · 我的账户（#534 计费④）", () => {
  it("未登录＝登录门：充值与余额是账户面，先 M01（checkout-login 来路）", () => {
    meState.loggedIn = false;
    billingState.projects = [];

    const html = renderView();

    expect(html).toContain("我的账户");
    expect(html).toContain("登录查看");
    expect(html).not.toContain("data-account-balance");
    meState.loggedIn = true;
  });

  it("双池余额＋流水：账户余额呈现、项目余额分节、入账行带符号金额与行后余额", () => {
    billingState.accountBalance = 12_345;
    billingState.accountEntries = [ENTRY];
    billingState.projects = [
      { id: "p-1", name: "官网项目", archived: false },
      { id: "p-2", name: "小程序项目", archived: false },
    ];
    billingState.projectViews = new Map([
      ["p-1", { pool: { balance: 7_500 }, checkoutTarget: 2, sourceSelected: true, deductionSourceName: "项目余额", paid: true, entries: [] }],
      ["p-2", { checkoutTarget: 2, sourceSelected: false, entries: [] }],
    ]);

    const html = renderView();

    expect(html).toContain("¥123.45"); // 账户余额（分→元）
    expect(html).toContain("¥75.00"); // 项目余额
    expect(html).toContain("官网项目");
    expect(html).toContain("小程序项目");
    expect(html).toContain("扣减源：项目余额");
    expect(html).toContain("试用中");
    expect(html).toContain("付费");
    expect(html).toContain("+¥50.00"); // 入账带符号
    expect(html).toContain("余 ¥123.45"); // 行后余额
    expect(html).toContain("模拟收银台充值"); // 备注留痕呈现
    // 项目充值入口：账户源以外的项目各一
    expect(html.match(/data-project-topup/g)?.length).toBe(2);
    expect(html).toContain("data-account-topup");
  });

  it("账户源项目：不呈现项目充值入口——引导到「我的账户」充值（死钱防线消费面）", () => {
    billingState.accountBalance = undefined; // 未充值＝零态不造假池
    billingState.accountEntries = [];
    billingState.projects = [{ id: "p-3", name: "账户源项目", archived: false }];
    billingState.projectViews = new Map([
      ["p-3", { checkoutTarget: 1, sourceSelected: true, deductionSourceName: "账户余额", entries: [] }],
    ]);

    const html = renderView();

    expect(html).toContain("该项目的扣减源是我的账户——到上方「我的账户」充值。");
    expect(html).not.toContain("data-project-topup");
    expect(html).not.toContain("¥0.00"); // 账户未充值不造假零（余额面缺席）
  });
});
