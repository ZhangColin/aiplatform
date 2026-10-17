import { useQuery } from "@tanstack/react-query";

import { ApiError } from "@/lib/api/api-error";
import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";
import { useSseFallbackPolling } from "@/lib/sse/provider";

type PrdResponse = components["schemas"]["PrdResponse"];
type PrdVersionSummary = components["schemas"]["PrdVersionSummaryResponse"];
type PrdVersionDetail = components["schemas"]["PrdVersionResponse"];

/**
 * 当前版 PRD（#20 成果区文件模式）：`GET …/prd` = 项目工作区 docs/PRD.md
 * 直读（markdown + updatedAt + readiness + draftChangeSummary，主智能体的
 * savePrd 写出）。**未产出是正常态**——后端以 404 PRJ_015 区分「工作区无该
 * 文件」与项目不存在（PRJ_001），这里把前者归一为 null（面板据 null 呈引导
 * 占位），其余错误照抛。draftChangeSummary（#334 改需求回路）＝范围级新草稿
 * 停驻待确认的差异摘要（null = 无）——对话区「PRD 已更新，确认新版后生成
 * 下一版 Demo」提示与确认门摘要层的数据面。实时性失效源 = 通知通道
 * document-updated（桥失效 documents 域）；断线走门控轮询兜底。
 * options.enabled 供「只在 PRD 必在场时才查」的消费面关闭 404 空跑（如对话区
 * 仅已生成项目读差异摘要）。
 */
export function usePrd(
  projectId: string | undefined,
  options?: { enabled?: boolean },
) {
  const fallbackPolling = useSseFallbackPolling("notification");
  return useQuery({
    queryKey: queryKeys.documents.prd(projectId ?? ""),
    queryFn: ({ signal }) =>
      api
        .get<PrdResponse>(`/projects/${projectId}/prd`, { signal })
        .catch((error: unknown) => {
          // PRJ_015 数字业务码（4015＝域码 PRJ=4×1000＋序号，ErrorCodePrefix）：信封
          // code 是数字（#169），旧字符串比对是死分支、未产出曾误炸面板。
          if (error instanceof ApiError && error.code === 4015) return null;
          throw error;
        }),
    enabled: projectId !== undefined && (options?.enabled ?? true),
    refetchInterval: fallbackPolling,
  });
}

/**
 * 已确认 PRD 版本列表（#330）：versionNo 倒序、空列表＝从未确认。阶段门
 * 「PRD 已确认」事实源（#343 验收闭合）——不伪造确认概念；确认动作成功后
 * 由 useConfirmPrd 失效本域（确认不写 docs/PRD.md，document-updated 不触发）。
 */
export function usePrdVersions(projectId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.documents.prdVersions(projectId ?? ""),
    queryFn: ({ signal }) =>
      api.get<PrdVersionSummary[]>(`/projects/${projectId ?? ""}/prd/versions`, { signal }),
    enabled: projectId !== undefined,
  });
}

/**
 * 单版 PRD 详情（含全文快照，#330）：版本记录抽屉 PRD 条目点开时随用随查
 * （#344）——content＝确认时工作区 PRD 全文的不可变快照。版本行不可变，
 * staleTime Infinity 即事实缓存（键随版本号，永不复用于别版）。
 */
export function usePrdVersion(projectId: string | undefined, versionNo: number | null) {
  return useQuery({
    queryKey: queryKeys.documents.prdVersion(projectId ?? "", versionNo ?? 0),
    queryFn: ({ signal }) =>
      api.get<PrdVersionDetail>(
        `/projects/${projectId ?? ""}/prd/versions/${versionNo ?? 0}`,
        { signal },
      ),
    enabled: projectId !== undefined && versionNo !== null,
    staleTime: Infinity,
  });
}
