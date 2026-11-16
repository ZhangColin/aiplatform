"use client";

import { ArrowDownCircle, ArrowUpCircle, History, Wallet } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useMyBilling, useProjectBilling } from "@/hooks/use-billing";
import { useMe } from "@/hooks/use-me";
import { useProjectList } from "@/hooks/use-projects";
import type { BillingEntryView } from "@/lib/billing/api";
import type { ProjectSummary } from "@/lib/projects/list";
import { useCheckoutStore } from "@/lib/store/checkout";
import { useM01Store } from "@/lib/store/m01";
import { cn } from "@/lib/utils";

/**
 * 「我的账户」视图（#534 计费④，ADR-0047「我的账户」词条兑现）：双池余额
 * （我的账户池＋各项目余额）＋流水（append-only 账本全量呈现，新先序）＋项目
 * 充值入口（逐项目开模拟收银台——只充当前扣减源池，非源池选项不呈现）。账户
 * 池与各项目池流水分节承载；账户池扣减行带 projectId，并联项目清单呈现项目名。
 *
 * <p>未登录＝登录门（CONTEXT「模拟收银台」：须先登录——M01 checkout-login 来路，
 * 登录成功 resume 重开账户面查询自愈）。数据面：useMyBilling＋逐项目
 * useProjectBilling（billing 弱引用不跨 BC join 项目名——并联归前端既有项目清单，
 * 账本可分离缝不被读面焊死）。</p>
 */

/** 分 → 元呈现（undefined＝无池——消费端给零态文案，不造假零余额）。 */
function yuan(fen: number | undefined): string | null {
  return fen === undefined ? null : (fen / 100).toFixed(2);
}

/** 落定时间呈现（ISO → 本地短形）。 */
function when(iso: string | undefined): string {
  if (!iso) return "";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString("zh-CN", { hour12: false });
}

export function MyAccountView() {
  const me = useMe();
  const loggedIn = me.data != null;

  if (!loggedIn) {
    return <LoginGate />;
  }
  return <AccountBody />;
}

/** 未登录门：充值与余额是账户面（访客无账户档案——先 M01，登录后查询自愈）。 */
function LoginGate() {
  const openM01 = useM01Store((s) => s.openM01);
  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-3 px-4 py-24 text-center">
      <Wallet className="size-8 text-muted-foreground" aria-hidden="true" />
      <h1 className="text-lg font-semibold">我的账户</h1>
      <p className="text-sm text-muted-foreground">
        余额与流水保存在你的账号下——登录后即可查看双池余额、全部流水并自助充值。
      </p>
      <Button onClick={() => openM01("checkout-login")}>登录查看</Button>
    </div>
  );
}

function AccountBody() {
  const myBilling = useMyBilling(true);
  const projects = useProjectList(true);
  const accountBalance = myBilling.data?.accountPool?.balance;
  const entries = myBilling.data?.entries ?? [];
  const projectNameOf = new Map(
    (projects.data ?? []).map((p: ProjectSummary) => [p.id, p.name]),
  );

  return (
    <div className="mx-auto w-full max-w-3xl space-y-8 px-4 py-8">
      <section data-account-pool>
        <div className="flex items-end justify-between gap-3">
          <div>
            <h2 className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
              <Wallet className="size-4" /> 我的账户（账户余额）
            </h2>
            <p className="mt-1 text-3xl font-semibold tracking-tight" data-account-balance>
              {accountBalance != null ? `¥${yuan(accountBalance)}` : "未充值"}
            </p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              账户余额跨项目可用——扣减来源选「我的账户」的项目从这里取用。
            </p>
          </div>
          <AccountTopupButton />
        </div>
        <EntryTable entries={entries} projectNameOf={projectNameOf} emptyHint="账户暂无流水——充值后这里记账。" />
      </section>

      <section data-project-pools>
        <h2 className="text-sm font-medium text-muted-foreground">项目余额</h2>
        <p className="mt-0.5 text-xs text-muted-foreground">
          项目余额仅本项目可用——试用项目充值即选定「本项目余额」为扣减来源。
        </p>
        <div className="mt-3 space-y-4">
          {(projects.data ?? []).length === 0 ? (
            <p className="rounded-lg border border-dashed px-4 py-6 text-center text-sm text-muted-foreground">
              还没有项目——去首页一句话创建，试用整场生成零扣费。
            </p>
          ) : (
            (projects.data ?? []).map((project) => (
              <ProjectPoolSection key={project.id} project={project} />
            ))
          )}
        </div>
      </section>
    </div>
  );
}

/** 账户充值入口：账户上下文开收银台（projectId=null）。 */
function AccountTopupButton() {
  const openCheckout = useCheckoutStore((s) => s.openCheckout);
  return (
    <Button size="sm" data-account-topup onClick={() => openCheckout(null)}>
      充值
    </Button>
  );
}

/** 项目余额节：项目池余额＋计费态＋项目池流水＋项目充值入口。 */
function ProjectPoolSection({ project }: { project: ProjectSummary }) {
  const billing = useProjectBilling(project.id);
  const openCheckout = useCheckoutStore((s) => s.openCheckout);
  const view = billing.data;
  const balance = view?.pool?.balance;
  /** 只充当前扣减源池：账户源项目的充值入口引导充我的账户（本节不呈现项目充值）。 */
  const topupHere = view == null || view.checkoutTarget !== 1;

  return (
    <div className="rounded-xl border p-4" data-project-pool={project.id}>
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate text-sm font-medium">{project.name}</span>
            {view?.sourceSelected ? (
              <span className="shrink-0 rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
                扣减源：{view.deductionSourceName}
              </span>
            ) : (
              <span className="shrink-0 rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-800">
                试用中
              </span>
            )}
            {view?.paid ? (
              <span className="shrink-0 rounded-full bg-emerald-100 px-2 py-0.5 text-xs text-emerald-800">
                付费
              </span>
            ) : null}
          </div>
          <p className="mt-1 text-xl font-semibold" data-project-balance>
            {balance != null ? `¥${yuan(balance)}` : "未充值"}
          </p>
          {view?.checkoutTarget === 1 ? (
            <p className="mt-0.5 text-xs text-muted-foreground">
              该项目的扣减源是我的账户——到上方「我的账户」充值。
            </p>
          ) : null}
        </div>
        {topupHere ? (
          <Button
            size="sm"
            variant="outline"
            data-project-topup
            disabled={billing.isPending}
            onClick={() => openCheckout(project.id)}
          >
            {billing.isPending ? <Spinner className="size-3.5" /> : null}
            充值
          </Button>
        ) : null}
      </div>
      <EntryTable
        entries={view?.entries ?? []}
        projectNameOf={new Map()}
        emptyHint="项目暂无流水。"
        compact
      />
    </div>
  );
}

/** 流水表（账户节与项目节共用）：种类图标＋带符号金额＋行后余额＋备注/项目名。 */
function EntryTable({
  entries,
  projectNameOf,
  emptyHint,
  compact = false,
}: {
  entries: BillingEntryView[];
  projectNameOf: Map<string, string>;
  emptyHint: string;
  compact?: boolean;
}) {
  if (entries.length === 0) {
    return <p className="mt-3 text-xs text-muted-foreground">{emptyHint}</p>;
  }
  return (
    <div className={cn("divide-y", compact ? "mt-2" : "mt-4 rounded-xl border")}>
      {entries.map((entry) => {
        const positive = (entry.amount ?? 0) > 0;
        const projectName = entry.projectId ? projectNameOf.get(entry.projectId) : undefined;
        return (
          <div
            key={entry.entryId}
            data-entry
            className={cn(
              "flex items-center gap-2 py-2 text-sm",
              compact ? "px-0" : "px-3",
            )}
          >
            {positive ? (
              <ArrowDownCircle className="size-4 shrink-0 text-emerald-600" aria-hidden="true" />
            ) : (
              <ArrowUpCircle className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            )}
            <div className="min-w-0 flex-1">
              <span className="font-medium">
                {entry.kindName ?? "流水"}
                {projectName ? <span className="text-muted-foreground"> · {projectName}</span> : null}
              </span>
              {entry.note ? (
                <span className="ml-1.5 text-xs text-muted-foreground">{entry.note}</span>
              ) : null}
              <span className="ml-1.5 text-xs text-muted-foreground">{when(entry.createdAt)}</span>
            </div>
            <span
              className={cn("shrink-0 font-medium tabular-nums", positive ? "text-emerald-700" : "text-foreground")}
              data-amount
            >
              {positive ? "+" : ""}
              ¥{yuan(entry.amount)}
            </span>
            <span className="shrink-0 text-xs text-muted-foreground tabular-nums">
              余 ¥{yuan(entry.balanceAfter)}
            </span>
          </div>
        );
      })}
      <div className="flex items-center gap-1.5 py-2 text-xs text-muted-foreground">
        <History className="size-3.5" /> 流水 append-only——只增不改，逐笔可审计。
      </div>
    </div>
  );
}
