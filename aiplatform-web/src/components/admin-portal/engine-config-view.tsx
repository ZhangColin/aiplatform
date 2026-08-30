"use client";

import { useState } from "react";

import { PortalContent } from "@/components/layout/portal-sidebar";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useAgentEngines } from "@/hooks/use-agent-engines";
import { useEngineConfig, useSwitchEngine } from "@/hooks/use-engine-config";
import { errorText } from "@/lib/api/api-error";
import type { components } from "@/lib/api/schema";

/**
 * 简易后台引擎配置页（issue #56，CONTEXT「简易后台」）：当前生效引擎位
 * （`GET /admin/engine-config`）+ 引擎能力矩阵只读渲染（`GET /agent-engines`，
 * 显式注册表全量）。切换走确认弹窗，文案明示生效口径 = 新项目生效、存量项目
 * 保持创建时固化的引擎（spec 0002 §3.1，aiplatform-server#42）。
 */

type EngineInfo = components["schemas"]["EngineInfo"];

export function EngineConfigView() {
  const config = useEngineConfig();
  const engines = useAgentEngines();
  const switchEngine = useSwitchEngine();
  // 确认弹窗受控目标（null = 关闭）；切完即清，停留矩阵可连续切换
  const [target, setTarget] = useState<EngineInfo | null>(null);

  const current = config.data?.engine;
  const items = engines.data ?? [];
  const currentLabel = items.find((e) => e.name === current)?.label ?? current;

  return (
    <PortalContent>
      <div className="mx-auto max-w-3xl p-6">
        {/* 非工作台页页头（spec 0001 §2）：标题 + 说明 */}
        <header className="mb-5">
          <h1 className="text-lg font-semibold">引擎配置</h1>
          <p className="text-xs text-muted-foreground">
            平台新项目使用的开发智能体引擎；切换不影响存量项目
          </p>
        </header>

        <Card>
          <CardHeader>
            <CardTitle>当前生效引擎</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {/* 当前引擎位：label 取自矩阵 join，未登记名回退原值 */}
            {config.isPending ? (
              <Skeleton className="h-8 w-40" />
            ) : config.isError ? (
              <div className="flex items-center gap-3 text-sm text-muted-foreground">
                <span>{errorText(config.error, "引擎配置加载失败")}</span>
                <Button variant="outline" size="sm" onClick={() => void config.refetch()}>
                  重试
                </Button>
              </div>
            ) : (
              <p className="text-sm">
                新项目将使用 <span className="font-medium">{currentLabel || "—"}</span>
              </p>
            )}

            {/* 能力矩阵只读渲染：当前行标「当前生效」，非当前行可切换 */}
            {engines.isPending ? (
              <div className="space-y-2">
                {[0, 1, 2].map((i) => (
                  <Skeleton key={i} className="h-10 w-full" />
                ))}
              </div>
            ) : engines.isError ? (
              <div className="flex items-center gap-3 text-sm text-muted-foreground">
                <span>{errorText(engines.error, "能力矩阵加载失败")}</span>
                <Button variant="outline" size="sm" onClick={() => void engines.refetch()}>
                  重试
                </Button>
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>引擎</TableHead>
                    <TableHead className="w-20">问答</TableHead>
                    <TableHead className="w-20">权限申请</TableHead>
                    <TableHead>备注</TableHead>
                    <TableHead className="w-24" />
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((engine) => {
                    const isCurrent = engine.name === current && current !== undefined;
                    return (
                      <TableRow key={engine.name ?? engine.label}>
                        <TableCell>
                          <div className="flex flex-col">
                            <span className="font-medium">{engine.label || engine.name}</span>
                            <span className="font-mono text-xs text-muted-foreground">
                              {engine.name}
                            </span>
                          </div>
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {engine.questionSupported ? "支持" : "—"}
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {engine.permissionSupported ? "支持" : "—"}
                        </TableCell>
                        <TableCell className="text-xs text-muted-foreground">
                          {engine.note || "—"}
                        </TableCell>
                        <TableCell>
                          {isCurrent ? (
                            <Badge variant="secondary">当前生效</Badge>
                          ) : (
                            <Button variant="ghost" size="sm" onClick={() => setTarget(engine)}>
                              设为当前
                            </Button>
                          )}
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>

        {/* 切换确认（task-panel 受控弹窗同款）：文案明示生效口径——只影响此后
            创建的新项目，存量项目保持创建时固化的引擎跑完。 */}
        <AlertDialog open={target !== null} onOpenChange={(open) => !open && setTarget(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>切换生效引擎到「{target?.label || target?.name}」？</AlertDialogTitle>
              <AlertDialogDescription>
                仅对此后创建的新项目生效；存量项目保持创建时固化的引擎继续运行，不受影响。
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>再想想</AlertDialogCancel>
              <AlertDialogAction
                disabled={switchEngine.isPending}
                onClick={() => {
                  if (!target?.name) return;
                  switchEngine.mutate(target.name);
                  setTarget(null);
                }}
              >
                确认切换
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </PortalContent>
  );
}
