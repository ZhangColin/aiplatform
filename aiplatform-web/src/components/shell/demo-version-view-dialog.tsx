"use client";

import { useEffect, useRef } from "react";

import { VersionViewDialog } from "@/components/project-page/version-view-dialog";
import { useGitVersions, useVersionViewSession } from "@/hooks/use-version";

/**
 * Demo 版本「查看当时」弹窗（#344 版本记录抽屉 Demo 条目点开面）：复用既有
 * 「查看当时」快照预览弹窗（#92/#140 能力），起服/销毁生命周期归
 * {@link useVersionViewSession} 单点（关窗即销毁、起服中关窗回执到场即销毁）。
 * Demo 版本的 runId 经 #91 版本表（git log 投影）映射 commit hash 后起服。
 * 标题带版本语境（「Demo vN 当时的系统」——版本记录的消费形，非轮次语境）。
 */

/**
 * Demo 版本条目引用（抽屉选中位→弹窗的投影形）：版本号（标题语境）＋runId
 * （「查看当时」的映射锚）。
 */
export type DemoVersionRef = { versionNo?: number; runId?: string };

export function DemoVersionViewDialog({
  projectId,
  version,
  onOpenChange,
}: {
  projectId: string;
  /** 选中的 Demo 版本（null＝未选条目，弹窗关、版本表不查）。 */
  version: DemoVersionRef | null;
  onOpenChange: (open: boolean) => void;
}) {
  const open = version !== null;
  const {
    data: gitVersions,
    isPending: listPending,
    isError: listError,
  } = useGitVersions(projectId, { enabled: open });
  const { startView, start, finish } = useVersionViewSession(projectId);
  // 本弹窗已起服的 hash（幂等锚：effect 重跑不重复起服；finish 即清）
  const startedRef = useRef<string | null>(null);

  const runId = version?.runId;
  // runId 空不配（防御）：回滚 commit 的 runId 也是空，undefined===undefined 会
  // 误配到回滚行——Demo 版本行 runId 恒在场，缺场如实走错误态
  const commitHash =
    runId != null
      ? gitVersions?.find((row) => row.runId === runId)?.commitHash
      : undefined;

  // 开且映射就位 → 起服一次（列表在途先等——弹窗出「正在准备当时系统…」）
  useEffect(() => {
    if (!open || !commitHash || startedRef.current === commitHash) return;
    startedRef.current = commitHash;
    start(commitHash);
  }, [open, commitHash, start]);

  function handleOpenChange(next: boolean) {
    if (!next) {
      finish();
      startedRef.current = null;
    }
    onOpenChange(next);
  }

  return (
    <VersionViewDialog
      open={open}
      onOpenChange={handleOpenChange}
      pending={listPending || startView.isPending}
      // 错误面三合一：起服失败／版本表查询失败／版本表在而映射缺位（收口成版
      // 与 git 提交偶发分叉的防御位）——如实走错误态，不出无提示空舞台
      error={
        startView.isError ||
        listError ||
        (!listPending && !listError && open && gitVersions != null && !commitHash)
      }
      previewUrl={startView.data?.previewUrl}
      title={version?.versionNo != null ? `Demo v${version.versionNo} 当时的系统` : undefined}
    />
  );
}
