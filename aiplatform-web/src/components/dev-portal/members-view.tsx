"use client";

import { PortalContent } from "@/components/layout/portal-sidebar";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useAccounts } from "@/hooks/use-accounts";
import { errorText } from "@/lib/api/api-error";

/**
 * 成员页（issue #22，spec 0003 §4）：v1 极简只读表格——头像 + 显示名 + 外部 ID，
 * 零操作。数据 = `GET /api/accounts`（AccountResponse = accountId + displayName，
 * 建档顺序；角色列与维护动作在此页生长留缝）。
 */
export function MembersView() {
  const accounts = useAccounts();
  const items = accounts.data ?? [];

  return (
    <PortalContent>
      <div className="mx-auto max-w-3xl p-6">
        {/* 非工作台页页头（spec 0001 §2）：标题 + 说明（收起归品牌行，issue #50） */}
        <header className="mb-5 flex items-center gap-2">
          <div>
            <h1 className="text-lg font-semibold">成员</h1>
            <p className="text-xs text-muted-foreground">平台账号，按建档顺序</p>
          </div>
        </header>

        {accounts.isPending ? (
          <div className="space-y-2">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-12 w-full" />
            ))}
          </div>
        ) : accounts.isError ? (
          <div className="flex flex-col items-center gap-3 py-16 text-sm text-muted-foreground">
            <p>{errorText(accounts.error, "成员列表加载失败")}</p>
            <Button variant="outline" size="sm" onClick={() => void accounts.refetch()}>
              重试
            </Button>
          </div>
        ) : items.length === 0 ? (
          <div className="rounded-xl border border-dashed py-16 text-center text-sm text-muted-foreground">
            暂无成员
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>成员</TableHead>
                <TableHead className="w-48">外部 ID</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {items.map((account) => (
                <TableRow key={account.accountId}>
                  <TableCell>
                    <div className="flex items-center gap-3">
                      <Avatar size="sm">
                        <AvatarFallback>
                          {(account.displayName || "?").slice(0, 1)}
                        </AvatarFallback>
                      </Avatar>
                      <span className="font-medium">{account.displayName || "未命名"}</span>
                    </div>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-muted-foreground">
                    {account.accountId || "—"}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </div>
    </PortalContent>
  );
}
