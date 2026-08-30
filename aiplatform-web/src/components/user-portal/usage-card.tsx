"use client";

import { ChevronDown } from "lucide-react";
import type { ReactNode } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useProjectUsage } from "@/hooks/use-project-usage";
import type { CostBuckets, TokenUsage } from "@/lib/projects/usage";
import {
  TOKEN_USAGE_ROWS,
  costEntries,
  formatCost,
  formatTokenCount,
  iterationLabel,
  iterationRemainder,
  roleUsageLabel,
  sortedIterations,
  tokenCount,
  usageTotalTokens,
} from "@/lib/projects/usage";
import { cn } from "@/lib/utils";

/**
 * 用量卡（issue #20 简版 → #24 升级，spec 0002 §4，后端 #29 已收口），
 * 自上而下：① 总 token description → ② 平台成本（cost 币种分桶平铺）→
 * ③ 未配价提示（unpriced）→ ④ byRole 主表 → ⑤ byIteration 折叠（期后修复
 * 差额行）→ ⑥ byModel 折叠。五档 token 数。口径：金额一律标「平台成本」，
 * 禁价格/费用/金额措辞。
 */
export function UsageCard({ projectId }: { projectId: string }) {
  const { data, isPending } = useProjectUsage(projectId);

  const remainder = iterationRemainder(data?.total, data?.byIteration);

  return (
    <Card className="gap-3 py-4">
      <CardHeader>
        <CardTitle className="text-sm">用量</CardTitle>
        <CardDescription>
          共 {formatTokenCount(usageTotalTokens(data?.total))} token
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : (
          <>
            <CostBlock cost={data?.cost} />
            {(data?.unpriced.length ?? 0) > 0 && (
              <UsageCollapsible
                label={`部分用量未配价（${data?.unpriced.length} 档），平台成本不含这些分量`}
                triggerClassName="justify-start px-0"
              >
                <ul className="space-y-0.5 text-xs text-muted-foreground">
                  {(data?.unpriced ?? []).map((u, i) => (
                    <li key={`${u.provider}/${u.model}/${u.tokenKind ?? i}`} className="truncate">
                      {[u.provider, u.model].filter(Boolean).join("/")} · {u.tokenKindName ?? "—"}
                    </li>
                  ))}
                </ul>
              </UsageCollapsible>
            )}
            <TokenTable
              rows={[
                { label: "合计", tokens: data?.total },
                ...(data?.byRole ?? []).map((r) => ({
                  // 展示纪律（spec 0002 §6）：只走展示名字段，技术键不示人；
                  // roleLabel=null 的用途标记桶按 code 映射（期后修复/恢复执行）
                  label: roleUsageLabel(r.role, r.roleLabel),
                  tokens: r.tokens,
                })),
              ]}
            />
            {(data?.byIteration.length ?? 0) > 0 && (
              <UsageCollapsible label="按期明细">
                <TokenTable
                  rows={[
                    ...sortedIterations(data?.byIteration).map((it) => ({
                      label: iterationLabel(it.seq),
                      tokens: it.tokens,
                    })),
                    // 期后修复差额行 = total − Σ期桶机械减法，仅差额 >0 时显；
                    // 只有 token 无成本（cost 无按期维度不假装折算）
                    ...(usageTotalTokens(remainder) > 0
                      ? [{ label: "期后修复", tokens: remainder }]
                      : []),
                  ]}
                />
              </UsageCollapsible>
            )}
            {(data?.byModel ?? []).length > 0 && (
              <UsageCollapsible label="按模型明细">
                <TokenTable
                  rows={(data?.byModel ?? []).map((m) => ({
                    label: [m.provider, m.model].filter(Boolean).join("/"),
                    tokens: m.tokens,
                  }))}
                />
              </UsageCollapsible>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

/** 平台成本块：币种分桶平铺（ISO 4217 码 + 固定 4 位小数右对齐），不相加不折算；空分桶显「—」。 */
function CostBlock({ cost }: { cost?: CostBuckets }) {
  const entries = costEntries(cost);
  return (
    <div className="space-y-1 text-xs">
      <div className="text-muted-foreground">平台成本</div>
      {entries.length === 0 ? (
        <div>—</div>
      ) : (
        entries.map(([currency, amount]) => (
          <div key={currency} className="flex items-baseline justify-between gap-2">
            <span className="text-muted-foreground">{currency}</span>
            <span className="tabular-nums">{formatCost(amount)}</span>
          </div>
        ))
      )}
    </div>
  );
}

/** 折叠区块：ghost 小触发钮 + chevron（未配价/按期/按模型三段同构）。 */
function UsageCollapsible({
  label,
  triggerClassName,
  children,
}: {
  label: ReactNode;
  triggerClassName?: string;
  children: ReactNode;
}) {
  return (
    <Collapsible>
      <CollapsibleTrigger
        render={
          <Button
            variant="ghost"
            size="xs"
            className={cn("w-full text-muted-foreground", triggerClassName)}
          />
        }
      >
        {label} <ChevronDown className="size-3" />
      </CollapsibleTrigger>
      <CollapsibleContent>{children}</CollapsibleContent>
    </Collapsible>
  );
}

/** 五档小表（右栏宽度适配：标签列 + 五个数字列）。 */
function TokenTable({ rows }: { rows: { label: string; tokens?: TokenUsage }[] }) {
  if (rows.length === 0) return null;
  return (
    <Table className="text-xs tabular-nums">
      <TableHeader>
        <TableRow className="hover:bg-transparent">
          <TableHead className="h-6 p-1.5 pl-0 text-muted-foreground" />
          {TOKEN_USAGE_ROWS.map((row) => (
            <TableHead key={row.key} className="h-6 p-1.5 text-right font-normal text-muted-foreground">
              {row.label}
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.label}>
            <TableCell className="max-w-24 truncate p-1.5 pl-0 font-medium">{row.label}</TableCell>
            {TOKEN_USAGE_ROWS.map((col) => (
              <TableCell key={col.key} className="p-1.5 text-right text-muted-foreground">
                {formatTokenCount(tokenCount(row.tokens, col.key))}
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
