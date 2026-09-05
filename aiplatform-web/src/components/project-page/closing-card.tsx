"use client";

import { Check, ChevronDown, Clock3, FileText, Monitor, ShieldCheck } from "lucide-react";
import { useState } from "react";

import { cn } from "@/lib/utils";
import type { WorkClosing } from "@/lib/store/chat";

import { formatDuration } from "./work-message";

/** 变更清单默认可见条数（其余收进「查看全部」——清单可长，卡不无限长）。 */
const VISIBLE_FILES = 5;

/**
 * 收尾卡（#88 定格收口）：run 收口 = 工作消息定格，本卡即其收尾部件（不是另起
 * 的卡）——四要素全出服务端权威事实（run-finish 的 closing 扩载）：摘要（判定
 * 事实的合并叙事）/ 判定行（PRD/系统改没改 + 原因——不由前端推导，旧「编辑无
 * 变化」推导口径已移除）/ 变更清单（文件级，+N −M 行数）/ 轮末统计（时长/文件
 * 数/变更行数 + 自检通过——closing 在场 ⟺ 收口判据核验通过）。过程明细已随
 * 定格退场（凝聚物）；「查看当时 / 回滚到此」版本控件归版本层（#91–#93）。
 */
export function ClosingCard({ closing }: { closing: WorkClosing }) {
  const [filesOpen, setFilesOpen] = useState(false);
  const files = closing.files;
  const shown = filesOpen ? files : files.slice(0, VISIBLE_FILES);
  const added = files.reduce((total, file) => total + file.added, 0);
  const removed = files.reduce((total, file) => total + file.removed, 0);

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

      {/* 轮末统计：自检通过（closing 在场 ⟺ 收口判据核验通过）+ 时长 + 文件数 + 变更行数 */}
      <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <ShieldCheck className="size-3.5" /> 检查通过
        </span>
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
    </div>
  );
}
