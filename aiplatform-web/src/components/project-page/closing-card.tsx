"use client";

import {
  Check,
  ChevronDown,
  Clock3,
  Eye,
  ExternalLink,
  Monitor,
  RotateCcw,
  ShieldCheck,
  X,
} from "lucide-react";
import { useState } from "react";

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
import { useProjectPreview } from "@/hooks/use-project-preview";
import { useRollbackVersion, useVersionViewSession } from "@/hooks/use-version";
import type { WorkClosing } from "@/lib/store/chat";
import { cn } from "@/lib/utils";

import { ChatMarkdown } from "./chat-markdown";
import { formatDuration } from "./work-message";
import { VersionViewDialog } from "./version-view-dialog";

/** 变更清单默认可见条数（其余收进「查看全部」——清单可长，卡不无限长）。 */
const VISIBLE_FILES = 5;

/**
 * 判据单项呈现（#617）：逐件如实——过＝绿、未过＝红。收尾卡只在收口判据双件
 * 全过后出现，未过档由失败终态呈现（不出现本卡）；组件仍按真实布尔渲染，
 * 不写死「通过」。
 */
function CriterionRow({
  icon,
  label,
  passed,
  passText,
  failText,
}: {
  icon: React.ReactNode;
  label: string;
  passed: boolean;
  passText: string;
  failText: string;
}) {
  return (
    <span className={cn("flex items-center gap-1", !passed && "text-destructive")}>
      {icon}
      {label}
      <b className={cn("font-medium", passed ? "text-foreground/80" : "text-destructive")}>
        {passed ? passText : failText}
      </b>
      {passed ? (
        <Check className="size-3.5 text-green-600" strokeWidth={3} />
      ) : (
        <X className="size-3.5" strokeWidth={3} />
      )}
    </span>
  );
}

/**
 * 收尾卡（#617 五字段骨架；#619 变更清单改真实 diff）：run 收口 = 工作消息定格，
 * 本卡即其收尾部件（不是另起的卡）。五字段全出服务端权威事实（run-finish 的
 * closing 扩载）：用时（durationMs）/判据逐件（build＋预览，criteria）/变更清单
 * （files，工作区真实 diff：迭代可点单个文件看只读 patch，超限标注「diff 过大」；
 * 首建只出行数——目录汇总归 #620）/内核自述（narrative，内核末次文本，markdown
 * 渲染；空则不出该块）/预览入口（既有预览查询的 URL：未就绪禁用、就绪走新窗口
 * 打开）。快照或 diff 失败时清单区标注「变更清单不可用」（生成照常成功）。旧
 * 平台句式摘要与「需求文档／系统」判定行退场；档案内旧载荷由 store 读侧回落
 * （toWorkClosing），旧对话史不出坏卡。变更清单头部认合计（文件数/±行数，一次
 * 呈现——旧轮末统计行里的重复计数已并入此处）。「查看当时 / 回滚到此」版本控件
 * （#92/#93）随 closing.version 成版锚点呈现（#605 后新载荷不携 version，存量
 * 旧卡可回；版本语义归阶段四重建）。
 */
export function ClosingCard({
  closing,
  projectId,
}: {
  closing: WorkClosing;
  projectId: string;
}) {
  const [filesOpen, setFilesOpen] = useState(false);
  // 单文件只读 diff 的展开态（#619：一次只展开一个文件，点同文件收起）
  const [expandedPath, setExpandedPath] = useState<string | null>(null);
  const files = closing.files;
  const shown = filesOpen ? files : files.slice(0, VISIBLE_FILES);
  const added = files.reduce((total, file) => total + file.added, 0);
  const removed = files.reduce((total, file) => total + file.removed, 0);
  // 预览入口（#617）：URL 取既有预览查询；轮询/事件推送归查询层，本卡只消费
  const preview = useProjectPreview(projectId, true);
  const previewUrl = preview.data?.url;

  // 版本动作（#92/#93）：查看当时 = 起快照（关窗即销毁——起服/销毁生命周期归
  // useVersionViewSession 单点，#344 收敛）；回滚 = 追加新版本
  const version = closing.version;
  const [viewOpen, setViewOpen] = useState(false);
  const [rollbackOpen, setRollbackOpen] = useState(false);
  const { startView, start, finish } = useVersionViewSession(projectId);
  const rollback = useRollbackVersion(projectId);

  function openView() {
    if (!version) return;
    setViewOpen(true);
    start(version);
  }

  function handleViewOpenChange(open: boolean) {
    if (!open) finish();
    setViewOpen(open);
  }

  /** 新窗口打开预览（#126 既有动作；#182 触碰先行——先 refetch 唤醒休眠沙箱）。 */
  function openPreview() {
    if (!previewUrl) return;
    void preview.refetch();
    window.open(previewUrl, "_blank", "noopener");
  }

  return (
    <div className="rounded-xl border border-green-600/25 bg-green-500/[0.06] p-3">
      <div className="mb-1.5 flex items-center gap-2">
        <Check className="size-4 shrink-0 text-green-600" strokeWidth={3} />
        <span className="text-sm font-semibold">生成完成</span>
      </div>

      {/* 判据结果：build＋预览逐件如实（收口硬判据的可视化） */}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[13px]">
        <CriterionRow
          icon={<ShieldCheck className="size-3.5 shrink-0 text-muted-foreground" />}
          label="构建"
          passed={closing.criteria.buildPassed}
          passText="通过"
          failText="未通过"
        />
        <CriterionRow
          icon={<Monitor className="size-3.5 shrink-0 text-muted-foreground" />}
          label="预览"
          passed={closing.criteria.previewPassed}
          passText="可用"
          failText="未就绪"
        />
      </div>

      {/* 内核自述：内核末次文本，markdown 渲染；空则不出该块 */}
      {closing.narrative?.trim() ? (
        <div className="mt-2 rounded-lg border bg-background px-2.5 py-2">
          <div className="mb-1 text-xs text-muted-foreground">内核自述</div>
          <div className="text-sm">
            <ChatMarkdown>{closing.narrative}</ChatMarkdown>
          </div>
        </div>
      ) : null}

      {/* 变更清单：文件级 +N −M（真实 diff）；迭代点文件看只读 patch，超限标
          「diff 过大」；快照/diff 失败时标注不可用。头部认合计（文件数与 ± 行数
          一次呈现） */}
      {files.length > 0 || closing.filesUnavailable ? (
        <div className="mt-2.5 rounded-lg border bg-background px-2.5 py-2">
          <div className="mb-1 flex items-center justify-between gap-2 text-xs text-muted-foreground">
            <span>变更清单</span>
            {closing.filesUnavailable ? null : (
              <span className="shrink-0 tabular-nums">
                <b className="font-medium text-foreground/80">{files.length}</b> 个文件{" "}
                <span className="font-medium text-green-600">+{added}</span>
                {removed > 0 ? <span className="font-medium text-destructive"> −{removed}</span> : null} 行
              </span>
            )}
          </div>
          {closing.filesUnavailable ? (
            <div className="py-0.5 text-xs text-muted-foreground">变更清单不可用</div>
          ) : (
            <div className="font-mono text-xs">
              {shown.map((file) => {
                const patch = file.patch;
                const expandable = typeof patch === "string" && patch.length > 0;
                const expanded = expandedPath === file.path;
                const row = (
                  <>
                    <span className="min-w-0 truncate text-foreground/80" title={file.path}>
                      {file.path}
                    </span>
                    <span className="flex shrink-0 items-center gap-2 tabular-nums">
                      {file.patchOmitted ? (
                        <span className="font-sans text-[11px] text-muted-foreground">diff 过大</span>
                      ) : null}
                      <span className="text-green-600">+{file.added}</span>
                      {file.removed > 0 ? <span className="text-destructive"> −{file.removed}</span> : null}
                      {expandable ? (
                        <ChevronDown
                          className={cn("size-3.5 text-muted-foreground transition-transform",
                            expanded && "rotate-180")}
                        />
                      ) : null}
                    </span>
                  </>
                );
                return (
                  <div key={file.path}>
                    {expandable ? (
                      <button
                        type="button"
                        className="flex w-full items-center justify-between gap-2 py-0.5 text-left transition-colors hover:text-foreground"
                        aria-expanded={expanded}
                        aria-label={`${expanded ? "收起" : "查看"} ${file.path} 的差异`}
                        onClick={() => setExpandedPath(expanded ? null : file.path)}
                      >
                        {row}
                      </button>
                    ) : (
                      <div className="flex items-center justify-between gap-2 py-0.5">{row}</div>
                    )}
                    {expandable && expanded ? (
                      <div className="mt-1 mb-1.5 max-h-72 overflow-auto rounded bg-muted/40 p-2 text-[11px] leading-relaxed">
                        {patch.split("\n").map((line, index) => (
                          <div
                            key={index}
                            className={cn(
                              "whitespace-pre",
                              line.startsWith("+")
                                ? "text-green-700"
                                : line.startsWith("-")
                                  ? "text-destructive"
                                  : "text-muted-foreground",
                            )}
                          >
                            {line || " "}
                          </div>
                        ))}
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
          )}
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

      {/* 用时＋预览入口：入口未就绪禁用、就绪走既有新窗口打开动作 */}
      <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-2 text-xs text-muted-foreground">
        <span className="flex items-center gap-1">
          <Clock3 className="size-3.5" /> 用时{" "}
          <b className="font-medium text-foreground/80">{formatDuration(closing.durationMs)}</b>
        </span>
        <Button
          variant="outline"
          size="sm"
          className="h-7 gap-1 px-2.5 text-xs"
          disabled={!previewUrl}
          onClick={openPreview}
          title="在新窗口打开预览"
          aria-label="在新窗口打开预览"
        >
          <ExternalLink className="size-3.5" />
          打开预览
        </Button>
      </div>

      {/* 版本控件（#92/#93）：成版锚点在场才可用——查看当时（起快照只逛不换）/回滚到此
          （追加版本、只回代码不回数据）。#605 后新载荷不携 version，存量旧卡可回。 */}
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
            summary={closing.narrative}
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
