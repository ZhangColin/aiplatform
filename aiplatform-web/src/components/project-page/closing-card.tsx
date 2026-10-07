"use client";

import {
  Check,
  ChevronDown,
  Clock3,
  Eye,
  FileCode2,
  FileText,
  Image as ImageIcon,
  ListChecks,
  Monitor,
  Palette,
  PenLine,
  RotateCcw,
  ShieldCheck,
} from "lucide-react";
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
import type { ClosingDraft, DesignLintClosing, WorkClosing } from "@/lib/store/chat";
import { rawFileUrl } from "@/lib/projects/files";
import { cn } from "@/lib/utils";

import { formatDuration } from "./work-message";
import { VersionViewDialog } from "./version-view-dialog";

/** 变更清单默认可见条数（其余收进「查看全部」——清单可长，卡不无限长）。 */
const VISIBLE_FILES = 5;
/** 稿清单默认可见条数（其余收进「查看全部」——多稿候选可长，卡不无限长）。 */
const VISIBLE_DRAFTS = 5;

/** 稿形态 → 图标（html＝界面稿〔可交互轻页面〕/ image＝图像稿〔平面位图〕）。 */
function DraftIcon({ media }: { media: ClosingDraft["media"] }) {
  return media === "html" ? (
    <FileCode2 className="size-3.5 shrink-0 text-muted-foreground" />
  ) : (
    <ImageIcon className="size-3.5 shrink-0 text-muted-foreground" />
  );
}

/** 稿去向文件名（path 末段——完整路径是工程语料，用户面认文件名）。 */
function draftNameOf(path: string): string {
  const name = path.substring(path.lastIndexOf("/") + 1);
  return name || path;
}

/**
 * 稿清单区（#290 设计会话收尾卡扩载呈现）：本轮落进 design/ 的稿——每稿一行
 * （形态图标 + 文件名 + 形态标签），去向＝文件区（path 即去向锚）。图像稿行可
 * 点：平台文件服务 raw 直看 inline 大图（未付费照看：门只盖下载面，#287）；界面
 * 稿（html）不出直链——raw 只伺服图片（PRJ_038）、渲染式呈现归成果区设计稿范式
 * （#293 稿伺服通道），此处如实提示文件区可看。其余收进「查看全部」。
 */
function DraftsArea({ projectId, drafts }: { projectId: string; drafts: ClosingDraft[] }) {
  const [open, setOpen] = useState(false);
  const shown = open ? drafts : drafts.slice(0, VISIBLE_DRAFTS);
  return (
    <div className="mt-2.5 rounded-lg border bg-background px-2.5 py-2">
      {shown.map((draft) =>
        draft.media === "image" ? (
          <a
            key={draft.path}
            href={rawFileUrl(projectId, draft.path)}
            target="_blank"
            rel="noopener"
            className="flex items-center gap-2 rounded-md py-0.5 text-[13px] transition-colors hover:bg-muted/50"
            title={`${draft.path}（点开看大图）`}
          >
            <DraftIcon media={draft.media} />
            <span className="min-w-0 flex-1 truncate text-foreground/80">{draftNameOf(draft.path)}</span>
            <span className="shrink-0 text-xs text-muted-foreground">图像稿</span>
          </a>
        ) : (
          <div
            key={draft.path}
            className="flex items-center gap-2 rounded-md py-0.5 text-[13px]"
            title={`${draft.path}（文件区可查看）`}
          >
            <DraftIcon media={draft.media} />
            <span className="min-w-0 flex-1 truncate text-foreground/80">{draftNameOf(draft.path)}</span>
            <span className="shrink-0 text-xs text-muted-foreground">界面稿 · 文件区可看</span>
          </div>
        ),
      )}
      {drafts.length > VISIBLE_DRAFTS ? (
        <button
          type="button"
          className="mt-1 flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
          onClick={() => setOpen(!open)}
        >
          <ChevronDown className={cn("size-3.5 transition-transform", open && "rotate-180")} />
          {open ? "收起" : `查看全部 ${drafts.length} 稿`}
        </button>
      ) : null}
      <FinalizeTriggers drafts={drafts} />
    </div>
  );
}

/** 违规清单默认可见条数（其余收进「查看全部」——同文件/稿清单收口）。 */
const VISIBLE_LINT = 5;

/** lint 规则中文名（用户面不露工程码；诊断原文经行 hover 可见）。 */
const LINT_RULE_LABELS: Record<string, string> = {
  "no-raw-colors": "裸色",
  "no-arbitrary-values": "任意值",
  "no-inline-styles": "内联样式",
};

/**
 * 按稿对齐区（#296 遵守三件套③感知面）：带规范项目编码 run 的设计规范扫描
 * 事实——样式合规（曾自动修一轮则注明，纠偏过程如实）/违规清单（文件:行 +
 * 规则中文名，hover 带诊断原文）/扫描未执行（如实，不假装达标）。圈注提意见
 * 走系统预览面既有通道（「像不像」归用户人眼，ADR-0028）。
 */
function DesignLintArea({ lint }: { lint: DesignLintClosing }) {
  const [open, setOpen] = useState(false);
  const violations = lint.violations ?? [];
  const shown = open ? violations : violations.slice(0, VISIBLE_LINT);
  const violating = lint.status === "violations";
  return (
    <div className="mt-2.5 rounded-lg border bg-background px-2.5 py-2">
      <div className="flex items-center gap-2 text-[13px]">
        <Palette className={cn("size-3.5 shrink-0", violating ? "text-amber-600" : "text-muted-foreground")} />
        <span>
          按稿对齐：
          {lint.status === "passed" ? (
            lint.retried ? "样式合规（曾自动修正一轮）" : "样式合规"
          ) : lint.status === "unavailable" ? (
            "样式合规扫描未执行"
          ) : (
            <>
              <span className="font-medium text-amber-600">
                {lint.total ?? violations.length} 处样式违规
              </span>
              {lint.retried ? "（已自动修正一轮，仍余）" : null}
            </>
          )}
        </span>
      </div>
      {violating && violations.length > 0 ? (
        <>
          <div className="mt-1 font-mono text-xs">
            {shown.map((violation) => (
              <div
                key={`${violation.file}:${violation.line}:${violation.rule}`}
                className="flex items-center justify-between gap-2 py-0.5"
                title={violation.message}
              >
                <span className="min-w-0 truncate text-foreground/80">
                  {violation.file}:{violation.line || ""}
                </span>
                <span className="shrink-0 text-muted-foreground">
                  {LINT_RULE_LABELS[violation.rule] ?? violation.rule}
                </span>
              </div>
            ))}
          </div>
          {violations.length > VISIBLE_LINT ? (
            <button
              type="button"
              className="mt-1 flex items-center gap-1 text-xs text-muted-foreground transition-colors hover:text-foreground"
              onClick={() => setOpen(!open)}
            >
              <ChevronDown className={cn("size-3.5 transition-transform", open && "rotate-180")} />
              {open ? "收起" : `查看全部 ${violations.length} 处`}
            </button>
          ) : null}
        </>
      ) : null}
    </div>
  );
}

/**
 * 定稿后续触发行（#291 定稿收尾卡）：drafts 携 triggers（仅定稿收尾卡——显式
 * 动作收口后的分岔事实：已按稿对齐/已开始构建/下单开放）即出「已触发」一行；
 * 候选产出轮（首产/改稿）不携带、零呈现。定稿稿行在卡内即「锁定那张」的叙事
 * （摘要＋版本控件），不另加标记。
 */
function FinalizeTriggers({ drafts }: { drafts: ClosingDraft[] }) {
  const triggers = drafts.flatMap((draft) => draft.triggers ?? []).filter(Boolean);
  if (triggers.length === 0) {
    return null;
  }
  return (
    <p className="mt-1.5 text-xs text-muted-foreground">
      已触发：{triggers.join("；")}
    </p>
  );
}

/**
 * 收尾卡（#88 定格收口）：run 收口 = 工作消息定格，本卡即其收尾部件（不是另起
 * 的卡）——四要素全出服务端权威事实（run-finish 的 closing 扩载）：摘要（判定
 * 事实的合并叙事）/ 判定行（PRD/系统改没改 + 原因——不由前端推导，旧「编辑无
 * 变化」推导口径已移除）/ 变更清单（文件级，+N −M 行数）/ 轮末统计（时长/文件
 * 数/变更行数 + 自检通过——closing 在场 ⟺ 收口判据核验通过）。工作消息定格留驻
 * （#117：过程明细仍留工作消息、不落库，本卡紧随入流）；「查看当时 / 回滚到此」
 * 版本控件（#92/#93）随 closing.version 成版锚点呈现（成版失败缺 version 键则不
 * 出，版本动作无锚不可用）。
 *
 * <p><b>设计会话变体</b>（#289 扩载、#290 呈现；#291 定稿收尾卡）：closing.drafts
 * 在场即设计收口——稿清单＋去向是本卡主承载（{@link DraftsArea}），判定行/文件
 * 清单让位（设计稿不是系统：PRD 恒未动、系统恒未动，文件清单与稿清单同集重复）
 * ；轮末统计＝时长＋稿数。候选产出轮（首产/改稿）不自动成版（ADR-0025），版本
 * 控件不出；定稿收尾卡（平台侧发射、无直播卡）例外——closing 携 version（成版
 * 锚）与 drafts 单条（triggers 后续触发事实，{@link FinalizeTriggers}）。</p>
 */
export function ClosingCard({
  closing,
  projectId,
  round,
}: {
  closing: WorkClosing;
  projectId: string;
  /** 轮次序数（#142「查看当时」标题语境源：对话流收尾卡序数，装配层数出）。 */
  round?: number;
}) {
  const [filesOpen, setFilesOpen] = useState(false);
  const files = closing.files;
  const shown = filesOpen ? files : files.slice(0, VISIBLE_FILES);
  const added = files.reduce((total, file) => total + file.added, 0);
  const removed = files.reduce((total, file) => total + file.removed, 0);
  // 自测统计（#96）：可缺省——自测子智能体未跑时不携带；只记「自测几项」，逐项 ✅/❌ 明细在过程播报
  const selfTest = closing.selfTest;
  // 设计规范扫描（#296）：可缺省——带规范项目编码 run 携带（「按稿对齐」行）
  const designLint = closing.designLint;
  // 稿清单（#289 设计会话扩载）：drafts 键在场即设计收口变体（判定行/文件清单/
  // 版本控件让位）——空数组（畸形载荷，后端收口判据＝有新稿、正常路径非空）也走
  // 设计形态如实出「本轮 0 稿」，不闪编码 run 语料
  const drafts = closing.drafts;
  const design = !!drafts;

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
      // schema 字段可选：viewId 在场才销毁（起服结果契约上必带）
      const viewId = startView.data?.viewId;
      if (viewId && version) {
        stopView.mutate({ version, viewId });
        startView.reset();
      } else {
        closePendingView.current = true;
      }
    }
    setViewOpen(open);
  }

  // 快照就绪时若窗口已关（起服中关窗）→ 立即销毁，不留孤儿
  useEffect(() => {
    const viewId = startView.data?.viewId;
    if (closePendingView.current && viewId && version) {
      stopView.mutate({ version, viewId });
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

      {design ? (
        /* 稿清单＋去向（#290 设计会话变体主承载）：判定行/文件清单让位；空数组
           （畸形载荷）无稿行不出清单区，统计行如实「0 稿」 */
        drafts && drafts.length > 0 ? <DraftsArea projectId={projectId} drafts={drafts} /> : null
      ) : (
        <>
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

          {/* 按稿对齐（#296）：带规范项目的样式合规扫描事实——编码 run 面上的
              叙事行＋违规清单；设计会话不携带不出 */}
          {designLint ? <DesignLintArea lint={designLint} /> : null}
        </>
      )}

      {/* 轮末统计：设计会话＝时长＋稿数（#290）；编码 run＝自检通过（closing 在场 ⟺
          收口判据核验通过）+ 自测统计（#96，可缺省）+ 时长 + 文件数 + 变更行数 */}
      <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
        {design && drafts ? (
          <span className="flex items-center gap-1">
            <PenLine className="size-3.5" /> 本轮 <b className="font-medium text-foreground/80">{drafts.length}</b> 稿
          </span>
        ) : (
          <span className="flex items-center gap-1">
            <ShieldCheck className="size-3.5" /> 检查通过
          </span>
        )}
        {selfTest ? (
          <span className="flex items-center gap-1">
            <ListChecks className="size-3.5" /> 自测 {selfTest.total} 项
          </span>
        ) : null}
        <span className="flex items-center gap-1">
          <Clock3 className="size-3.5" /> 用时{" "}
          <b className="font-medium text-foreground/80">{formatDuration(closing.durationMs)}</b>
        </span>
        {!design && files.length > 0 ? (
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
          （追加版本、只回代码不回数据）。成版失败（缺 version）轮不出控件；设计
          会话的候选产出轮（首产/改稿）不携带 version（候选不自动成版，ADR-0025）
          亦不出；定稿收尾卡（#291）例外——显式动作收口即成版，「查看当时」即见
          定稿稿。 */}
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
            round={round}
            summary={closing.summary}
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
