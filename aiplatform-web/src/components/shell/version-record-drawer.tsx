"use client";

import { FileText, History } from "lucide-react";
import { useState } from "react";

import { Badge } from "@/components/ui/badge";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { useDemoVersions } from "@/hooks/use-demo-version";
import { usePrdVersions } from "@/hooks/use-prd";
import { useVersionRecordStore } from "@/lib/store/version-record";
import { formatRelativeTime } from "@/lib/utils/time";

import { DemoVersionViewDialog, type DemoVersionRef } from "./demo-version-view-dialog";
import { PrdVersionViewDialog } from "./prd-version-view-dialog";

/**
 * 版本记录抽屉（#344，spec #320 ⑥——交接包 §5.2 要求、原型未画→补建）：
 * 项目级单抽屉，PRD 版本组（确认快照，#330）＋Demo 版本组（构建成版，#333），
 * 默认呈现最新可用版本（清单最新在前的首行带「最新」标记）——历史成果收进
 * 抽屉、不堆叠卡片。挂点两处共用：顶栏「版本记录」＋Demo 版本 chip（经
 * {@link useVersionRecordStore} 同一开合契约），抽屉本体由项目空间壳挂载。
 *
 * <p>条目只读可回看：PRD 条目＝快照全文查看、Demo 条目＝「查看当时」系统快照
 * （复用既有弹窗能力）；历史版本无确认/回滚等变更动作。「从历史版本返回签约时
 * 需明确选择要确认的版本」的呈现位在此预留（{@link SIGNBACK_VERSION_NOTICE}
 * 单点导出——签约页 #348 消费，回签选择不改默认合作范围）。</p>
 */

/**
 * 回签选择提示（呈现位预留，#348 签约页消费的单点话术）：从历史版本回签时，
 * 签约范围不默认改变——用户需明确选择要确认的版本。
 */
export const SIGNBACK_VERSION_NOTICE =
  "历史版本只读，仅供回看；从历史版本返回签约时，需明确选择要确认的版本。";

export function VersionRecordDrawer({ projectId }: { projectId: string }) {
  const open = useVersionRecordStore((s) => s.open);
  const openVersionRecord = useVersionRecordStore((s) => s.openVersionRecord);
  const closeVersionRecord = useVersionRecordStore((s) => s.closeVersionRecord);
  const { data: prdVersions } = usePrdVersions(projectId);
  const { data: demoVersions } = useDemoVersions(projectId);
  /** PRD 条目点开面：选中的版本号（null＝无——弹窗不查不出）。 */
  const [prdViewNo, setPrdViewNo] = useState<number | null>(null);
  /** Demo 条目点开面：选中的版本行（null＝无——弹窗关、版本表不查）。 */
  const [demoView, setDemoView] = useState<DemoVersionRef | null>(null);

  return (
    <>
      <Sheet
        open={open}
        onOpenChange={(next) => (next ? openVersionRecord() : closeVersionRecord())}
      >
        {/* 宽度覆写走同 variant（data-[side=right]: 前缀）——异档类不互斥、
            基座 w-3/4／sm:max-w-sm 会压掉裸 w-full／sm:max-w-md（#140 同教训） */}
        <SheetContent
          side="right"
          className="flex flex-col gap-0 p-0 data-[side=right]:w-full data-[side=right]:sm:max-w-md"
        >
          <SheetHeader className="border-b">
            <SheetTitle className="flex items-center gap-2">
              <History className="size-4 text-ink-faint" aria-hidden="true" />
              版本记录
            </SheetTitle>
            <SheetDescription>
              默认呈现最新可用版本；历史版本只读，可回看当时的文档与系统。
            </SheetDescription>
          </SheetHeader>

          <div className="min-h-0 flex-1 overflow-y-auto">
            <PrdVersionGroup versions={prdVersions ?? []} onView={setPrdViewNo} />
            <DemoVersionGroup versions={demoVersions ?? []} onView={setDemoView} />
          </div>

          <div className="border-t px-4 py-3">
            <p className="text-xs leading-relaxed text-ink-faint">
              {SIGNBACK_VERSION_NOTICE}
            </p>
          </div>
        </SheetContent>
      </Sheet>

      {/* 条目弹窗与 Sheet 平级（不嵌 Dialog.Root——外层 modal 的 inert 面会
          误伤内层弹窗）；抽屉留在底层可续翻。PRD 条目＝快照全文查看；Demo
          条目＝当时系统快照（复用既有弹窗能力） */}
      <PrdVersionViewDialog
        projectId={projectId}
        versionNo={prdViewNo}
        onOpenChange={(next) => {
          if (!next) setPrdViewNo(null);
        }}
      />
      <DemoVersionViewDialog
        projectId={projectId}
        version={demoView}
        onOpenChange={(next) => {
          if (!next) setDemoView(null);
        }}
      />
    </>
  );
}

/** 组头（两组同构：组名＋条目数）。 */
function GroupHeading({ label, count }: { label: string; count: number }) {
  return (
    <div className="flex items-baseline justify-between px-4 pb-2 pt-5">
      <span className="text-label-sm font-semibold uppercase tracking-wider text-ink-faint">
        {label}
      </span>
      <span className="text-label-sm text-ink-faint">{count} 个版本</span>
    </div>
  );
}

/** 空态（诚实优先于占位——不虚构版本概念）。 */
function GroupEmpty({ text }: { text: string }) {
  return <p className="px-4 pb-2 text-body-sm text-ink-faint">{text}</p>;
}

/** PRD 版本组：确认即成版的不可变快照（#330），条目＝快照全文查看。 */
function PrdVersionGroup({
  versions,
  onView,
}: {
  versions: readonly { versionNo?: number; confirmedAt?: string }[];
  onView: (versionNo: number) => void;
}) {
  return (
    <section aria-label="PRD 版本">
      <GroupHeading label="PRD 版本" count={versions.length} />
      {versions.length === 0 ? (
        <GroupEmpty text="尚未确认过 PRD——确认后的版本会记录在这里。" />
      ) : (
        <ul className="flex flex-col gap-1.5 px-3">
          {versions.map((version, index) => (
            <li key={version.versionNo}>
              <button
                type="button"
                aria-current={index === 0 || undefined}
                onClick={() => version.versionNo != null && onView(version.versionNo)}
                className="flex w-full flex-col gap-1 rounded-xl border border-slate-soft/60 bg-surface-dawn/50 p-3 text-left transition-colors hover:bg-surface-dawn"
              >
                <span className="flex min-w-0 items-center gap-2">
                  <span className="flex items-center gap-1.5 font-mono text-sm font-semibold text-ink-primary">
                    <FileText className="size-3.5 text-ink-faint" aria-hidden="true" />
                    PRD v{version.versionNo}
                  </span>
                  {index === 0 ? <Badge variant="secondary">最新</Badge> : null}
                </span>
                <span className="text-xs text-ink-faint">
                  {confirmedAtText(version.confirmedAt)} · 查看全文
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** Demo 版本组：每轮构建收口自动成版（#333），条目＝「查看当时」系统快照。 */
function DemoVersionGroup({
  versions,
  onView,
}: {
  versions: readonly {
    versionNo?: number;
    prdVersionNo?: number | null;
    runId?: string;
    createdAt?: string;
    confirmedAt?: string | null;
  }[];
  onView: (version: DemoVersionRef) => void;
}) {
  return (
    <section aria-label="Demo 版本">
      <GroupHeading label="Demo 版本" count={versions.length} />
      {versions.length === 0 ? (
        <GroupEmpty text="尚未生成 Demo——每轮构建收口会自动成版。" />
      ) : (
        <ul className="flex flex-col gap-1.5 px-3 pb-4">
          {versions.map((version, index) => (
            <li key={version.versionNo}>
              <button
                type="button"
                aria-current={index === 0 || undefined}
                onClick={() => onView({ versionNo: version.versionNo, runId: version.runId })}
                className="flex w-full flex-col gap-1 rounded-xl border border-slate-soft/60 bg-surface-dawn/50 p-3 text-left transition-colors hover:bg-surface-dawn"
              >
                <span className="flex min-w-0 items-center gap-2">
                  <span className="font-mono text-sm font-semibold text-ink-primary">
                    Demo v{version.versionNo}
                  </span>
                  {index === 0 ? <Badge variant="secondary">最新</Badge> : null}
                  {version.confirmedAt != null ? (
                    <Badge variant="outline" className="border-emerald-active/40 text-emerald-active">
                      已确认
                    </Badge>
                  ) : null}
                  <span className="ml-auto shrink-0 text-xs text-ink-faint">
                    {createdAtText(version.createdAt)}
                  </span>
                </span>
                <span className="text-xs text-ink-faint">
                  {version.prdVersionNo != null
                    ? `基于 PRD v${version.prdVersionNo} 快照 · `
                    : ""}
                  查看当时系统
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** 确认时间（不可解析不臆造——缺场只出动作提示）。 */
function confirmedAtText(iso: string | undefined): string {
  const relative = formatRelativeTime(iso);
  return relative ? `确认于 ${relative}` : "已确认";
}

/** 成版时间（不可解析不臆造——缺场不出时间位）。 */
function createdAtText(iso: string | undefined): string {
  return formatRelativeTime(iso);
}
