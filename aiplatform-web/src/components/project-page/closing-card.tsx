"use client";

import { Check, ChevronDown, Clock3, Eye, FileText, ListChecks, Monitor, RotateCcw, ShieldCheck } from "lucide-react";
import { useEffect, useRef, useState } from "react";

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
import { Button } from "@/components/ui/button";
import { useRollbackVersion, useStartVersionView, useStopVersionView } from "@/hooks/use-version";
import type { WorkClosing } from "@/lib/store/chat";
import { cn } from "@/lib/utils";

import { formatDuration } from "./work-message";
import { VersionViewDialog } from "./version-view-dialog";

/** 变更清单默认可见条数（其余收进「查看全部」——清单可长，卡不无限长）。 */
const VISIBLE_FILES = 5;

/**
 * 收尾卡（#88 定格收口）：run 收口 = 工作消息定格，本卡即其收尾部件（不是另起
 * 的卡）——四要素全出服务端权威事实（run-finish 的 closing 扩载）：摘要（判定
 * 事实的合并叙事）/ 判定行（PRD/系统改没改 + 原因——不由前端推导，旧「编辑无
 * 变化」推导口径已移除）/ 变更清单（文件级，+N −M 行数）/ 轮末统计（时长/文件
 * 数/变更行数 + 自检通过——closing 在场 ⟺ 收口判据核验通过）。过程明细已随
 * 定格退场（凝聚物）；「查看当时 / 回滚到此」版本控件（#92/#93）随 closing.version
 * 成版锚点呈现（成版失败缺 version 键则不出，版本动作无锚不可用）。
 */
export function ClosingCard({ closing, projectId }: { closing: WorkClosing; projectId: string }) {
  const [filesOpen, setFilesOpen] = useState(false);
  const files = closing.files;
  const shown = filesOpen ? files : files.slice(0, VISIBLE_FILES);
  const added = files.reduce((total, file) => total + file.added, 0);
  const removed = files.reduce((total, file) => total + file.removed, 0);
  // 自测统计（#96）：可缺省——自测子智能体未跑时不携带；只记「自测几项」，逐项 ✅/❌ 明细在过程播报
  const selfTest = closing.selfTest;

  // 版本动作（#92/#93）：查看当时 = 起快照（关窗即销毁）；回滚 = 追加新版本
  const version = closing.version;
  const [viewOpen, setViewOpen] = useState(false);
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const startView = useStartVersionView(projectId);
  const stopView = useStopVersionView(projectId);
  const rollback = useRollbackVersion(projectId);
  // 起服中关窗（data 未就绪）→ 记下，待快照就绪即销毁——防起服完成却无人关闭的孤儿快照
  const closePendingView = useRef(false);

  function openView() {
    if (!version) return;
    closePendingView.current = false;
    setViewOpen(true);
    startView.mutate(version);
  }

  function handleViewOpenChange(open: boolean) {
    if (!open) {
      const view = startView.data;
      if (view && version) {
        stopView.mutate({ version, viewId: view.viewId });
        startView.reset();
      } else {
        closePendingView.current = true;
      }
    }
    setViewOpen(open);
  }

  // 快照就绪时若窗口已关（起服中关窗）→ 立即销毁，不留孤儿
  useEffect(() => {
    if (closePendingView.current && startView.data && version) {
      stopView.mutate({ version, viewId: startView.data.viewId });
      startView.reset();
      closePendingView.current = false;
    }
  }, [startView.data, version, stopView, startView]);

  return (
    <div className="rounded-xl border border-green-600/25 bg-green-500/[0.06] p-3">
      <div className="mb-1.5 flex items-center gap-2">
        <Check className="size-4 shrink-0 text-green-600" strokeWidth={3} />
        <span className="text-sm font-semibold">本轮完成</span>
      </div>

      {/* 摘要：本轮做了什么（文档与系统合并叙事，不分侧） */}
      <p className="text-sm leading-relaxed">{closing.summary}</p>

      {/* 判定行：PRD / 系统改没改 + 原因（服务端权威值） */}
      <div className="mt-1.5 flex items-start gap-2 text-[13px]">
        <FileText className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
        <span>
          需求文档：
          {closing.prdChanged ? (
            <>
              已修订
              {closing.prdNote ? <span className="text-muted-foreground">——{closing.prdNote}</span> : null}
            </>
          ) : (
            "未修订"
          )}
        </span>
      </div>
      <div className="mt-1 flex items-start gap-2 text-[13px]">
        <Monitor className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
        <span>
          系统：
          {closing.systemChanged ? (
            <>
              已更新
              {closing.systemNote ? <span className="text-muted-foreground">——{closing.systemNote}</span> : null}
            </>
          ) : (
            <>
              无需改动
              {closing.systemNote ? <span className="text-muted-foreground">——{closing.systemNote}</span> : null}
            </>
          )}
        </span>
      </div>

      {/* 变更清单：文件级，+N −M（空清单不出——如系统无需改动的轮） */}
      {files.length > 0 ? (
        <div className="mt-2.5 rounded-lg border bg-background px-2.5 py-2">
          <div className="font-mono text-xs">
            {shown.map((file) => (
              <div key={file.path} className="flex items-center justify-between gap-2 py-0.5">
                <span className="min-w-0 truncate text-foreground/80" title={file.path}>
                  {file.path}
                </span>
                <span className="shrink-0 tabular-nums">
                  <span className="text-green-600">+{file.added}</span>
                  {file.removed > 0 ? <span className="text-destructive"> −{file.removed}</span> : null}
                </span>
              </div>
            ))}
          </div>
          {files.length > VISIBLE_FILES ? (
            <button
              type="button"
              className="mt-1 flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
              onClick={() => setFilesOpen(!filesOpen)}
            >
              <ChevronDown className={cn("size-3.5 transition-transform", filesOpen && "rotate-180")} />
              {filesOpen ? "收起" : `查看全部 ${files.length} 个文件`}
            </button>
          ) : null}
        </div>
      ) : null}

      {/* 轮末统计：自检通过（closing 在场 ⟺ 收口判据核验通过）+ 自测统计（#96 自测
          子智能体清单式播报的收尾统计——可缺省）+ 时长 + 文件数 + 变更行数 */}
      <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <ShieldCheck className="size-3.5" /> 检查通过
        </span>
        {selfTest ? (
          <span className="flex items-center gap-1">
            <ListChecks className="size-3.5" /> 自测 {selfTest.total} 项
          </span>
        ) : null}
        <span className="flex items-center gap-1">
          <Clock3 className="size-3.5" /> 用时{" "}
          <b className="font-medium text-foreground/80">{formatDuration(closing.durationMs)}</b>
        </span>
        {files.length > 0 ? (
          <>
            <span className="flex items-center gap-1">
              <b className="font-medium text-foreground/80">{files.length}</b> 个文件
            </span>
            <span className="tabular-nums">
              <span className="font-medium text-green-600">+{added}</span>
              {removed > 0 ? <span className="font-medium text-destructive"> −{removed}</span> : null} 行
            </span>
          </>
        ) : null}
      </div>

      {/* 版本控件（#92/#93）：成版锚点在场才可用——查看当时（起快照只逛不换）/回滚到此
          （追加版本、只回代码不回数据）。成版失败（缺 version）轮不出控件。 */}
      {version ? (
        <>
          <div className="mt-2.5 flex items-center gap-2">
            <Button variant="outline" size="sm" className="h-7 gap-1 px-2.5 text-xs" onClick={openView}>
              <Eye className="size-3.5" />
              查看当时
            </Button>
            <Button
              variant="outline"
              size="sm"
              className="h-7 gap-1 px-2.5 text-xs"
              onClick={() => setRollbackOpen(true)}
            >
              <RotateCcw className="size-3.5" />
              回滚到此
            </Button>
          </div>

          <VersionViewDialog
            open={viewOpen}
            onOpenChange={handleViewOpenChange}
            pending={startView.isPending}
            error={startView.isError}
            previewUrl={startView.data?.previewUrl}
          />

          <AlertDialog open={rollbackOpen} onOpenChange={setRollbackOpen}>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>回滚到这个版本？</AlertDialogTitle>
                <AlertDialogDescription>
                  系统代码会回到该版本，业务数据保留；回滚会追加一个新版本，历史不丢失。
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>再想想</AlertDialogCancel>
                <AlertDialogAction variant="destructive" onClick={() => rollback.mutate(version)}>
                  回滚
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      ) : null}
    </div>
  );
}
