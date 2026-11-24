import { useMutation, useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useRef } from "react";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import type { components } from "@/lib/api/schema";
import { errorText } from "@/lib/api/api-error";
import { queryKeys } from "@/lib/api/keys";
import { useVersionRecordStore } from "@/lib/store/version-record";

/**
 * 版本动作数据层（#92 查看当时 + #627 异步回滚）：收尾卡上「查看当时 / 回到此版」
 * 两个动作的请求面。查看当时 = 起快照容器（POST view，返回 viewId + 预览 URL，
 * 逛完经 DELETE 关闭销毁）；回到此版 = 平台侧异步动作（restore 代码 → 停进程 →
 * 重新 build → 重启 → 探活 → 记新版本）。回滚进度经工作消息起止呈现（SSE），
 * 成功由 run-finish 失效项目域刷新时间线/预览，失败经 run-failed 明确展示。
 */

/** 查看会话起服结果（#92）：viewId 是关闭动作的寻址锚，previewUrl 是快照预览。 */
type VersionViewStart = components["schemas"]["VersionViewStartResponse"];
/** 版本行（#91 版本层 git log 投影）。 */
type GitVersion = components["schemas"]["VersionResponse"];
/** 回到某版受理回执（#627）：runId＝SSE 锚，label＝版本标签（回到 vN）。 */
type RollbackStart = components["schemas"]["VersionRollbackStartResponse"];

/**
 * run 收口版本表（#91：git log 即版本序列，新→旧）。版本记录抽屉 Demo 条目
 * （#344）经 runId→commitHash 映射接「查看当时」；options.enabled 供点开才查
 * 的消费面省空跑（抽屉本体常驻壳上、不点 Demo 条目不发）。
 */
export function useGitVersions(
  projectId: string,
  options?: { enabled?: boolean },
) {
  return useQuery({
    queryKey: queryKeys.projects.versions(projectId),
    queryFn: ({ signal }) =>
      api.get<GitVersion[]>(`/projects/${projectId}/versions`, { signal }),
    enabled: options?.enabled ?? true,
  });
}

/** 起服会话的销毁锚：hash×viewId 成对到场（#344 收敛单点）。 */
type ViewSession = { version: string; viewId: string };

/**
 * 「查看当时」起服/销毁生命周期（#92 口径，#344 自收尾卡收敛单点）：开即起快照
 * （{@link start}）、关窗即销毁（{@link finish}）——销毁锚是 mutate 回执里成对
 * 到场的 hash×viewId（sessionRef），不取「当前 mutation data×当前锚」的拼配
 * （在途换版本时二者可能错位）。起服中关窗（viewId 未到）→ 记下，回执到场即
 * 销毁——防起服完成却无人关闭的孤儿快照；在途换版被顶掉的旧回执只丢前端锚，
 * 容器侧归后端闲置清扫（sweepIdleViews 运行期兜底）。消费面：收尾卡（#92）、
 * 版本记录抽屉 Demo 条目（#344）。
 */
export function useVersionViewSession(projectId: string) {
  const startView = useStartVersionView(projectId);
  const stopView = useStopVersionView(projectId);
  const sessionRef = useRef<ViewSession | null>(null);
  const closePendingRef = useRef(false);

  /** 起快照（同一会话期幂等由调用方把关；回执成对记锚）。 */
  const start = useCallback(
    (version: string) => {
      closePendingRef.current = false;
      startView.mutate(version, {
        onSuccess: (result) => {
          if (result?.viewId) sessionRef.current = { version, viewId: result.viewId };
        },
      });
    },
    [startView],
  );

  /** 销毁在场会话（有成对锚才发得出 DELETE）；无锚＝起服在途，返回 false。 */
  const destroy = useCallback(() => {
    const session = sessionRef.current;
    if (!session) return false;
    stopView.mutate({ version: session.version, viewId: session.viewId });
    sessionRef.current = null;
    startView.reset();
    return true;
  }, [stopView, startView]);

  /**
   * 关窗收口：会话在手即销毁；起服在途（无锚）记下、回执到场即销毁（下方
   * effect 兜底）——防孤儿快照。
   */
  const finish = useCallback(() => {
    if (!destroy()) closePendingRef.current = true;
  }, [destroy]);

  // 起服中关窗的补销毁：回执（startView.data）到场时若窗已关，立即销毁
  useEffect(() => {
    if (closePendingRef.current && sessionRef.current) {
      destroy();
      closePendingRef.current = false;
    }
  }, [startView.data, destroy]);

  return { startView, start, finish };
}

/** 起「查看当时」快照容器。 */
export function useStartVersionView(projectId: string) {
  return useMutation({
    mutationFn: (version: string) =>
      api.post<VersionViewStart>(`/projects/${projectId}/versions/${version}/view`),
  });
}

/** 关闭「查看当时」会话（销毁快照容器，用完即销毁）。 */
export function useStopVersionView(projectId: string) {
  return useMutation({
    mutationFn: ({ version, viewId }: { version: string; viewId: string }) =>
      api.delete<void>(`/projects/${projectId}/versions/${version}/view/${viewId}`),
  });
}

/**
 * 回到某版（#627 异步回滚）：POST 立即受理（返回 runId＋版本标签），进度经工作
 * 消息起止呈现（SSE「回到版本 vN」）；成功由 run-finish 失效项目域刷新时间线与
 * 预览，失败经 run-failed 明确展示（含两条恢复路）。受理成功即收起时间线抽屉让
 * 用户回到主视图看进度；同步拒绝（版本不存在/已归档/在途）走 toast。
 */
export function useRollbackVersion(projectId: string) {
  const closeVersionRecord = useVersionRecordStore((s) => s.closeVersionRecord);
  return useMutation({
    mutationFn: (version: string) =>
      api.post<RollbackStart>(`/projects/${projectId}/versions/${version}/rollback`),
    onSuccess: (result) => {
      closeVersionRecord();
      if (result?.label) toast.info(`正在${result.label}，完成后会自动刷新`);
    },
    onError: (error) => {
      toast.error(errorText(error, "回滚失败，请稍后重试"));
    },
  });
}
