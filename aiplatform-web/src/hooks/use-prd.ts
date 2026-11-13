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
 * 直读（markdown + updatedAt + readiness，主智能体的 savePrd 写出）。**未产出
 * 是正常态**——后端以 404 PRJ_015 区分「工作区无该文件」与项目不存在
 * （PRJ_001），这里把前者归一为 null（面板据 null 呈引导占位），其余错误照
 * 抛。实时性失效源 = 通知通道 document-updated（桥失效 documents 域）；断线
 * 走门控轮询兜底。options.enabled 供「只在 PRD 必在场时才查」的消费面关闭
 * 404 空跑。
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
 * PRD 版本清单（#330 成行制，#462 去门化语义顺延，ADR-0039）：versionNo 倒序、
 * 空列表＝尚无构建基准记行。阶段门「首个 Demo 在途」事实源（#343 验收闭合）
 * ——版本行由 Demo 类 run 起跑自动记行（首次 savePrd 触发首个 run）；失效源两路：
 * document-updated（savePrd 写出）与 run-start(agent=executor)（记行落库后必达，
 * 桥内失效——savePrd 通知与记行存在竞态，单靠前者阶段门会卡到刷新）。
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
 * 单版 PRD 详情（含全文快照，#330；#462 起快照＝构建基准记行时点的工作区 PRD
 * 全文）：版本记录抽屉 PRD 条目点开时随用随查（#344）——content＝记行时
 * 工作区 PRD 全文的不可变快照。版本行不可变，staleTime Infinity 即事实缓存
 * （键随版本号，永不复用于别版）。
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
