import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { components } from "@/lib/api/schema";

type ProjectFileContentResponse = components["schemas"]["ProjectFileContentResponse"];

/**
 * 文本文件内容（#27 文件模式点看）：`GET …/files/content?path=`——path 为文件树
 * 条目原样回传。后端限文本限大小（PRJ_020/021/022/023 拒绝口径），错误在消费
 * 面呈现；enabled = 选中文件存在（PRD 正文另有专用端点 usePrd，带 mtime 与
 * 修订回路语义，不经此口）。
 */
export function useProjectFileContent(projectId: string | undefined, path: string | null) {
  return useQuery({
    queryKey: queryKeys.projects.fileContent(projectId ?? "", path ?? ""),
    queryFn: ({ signal }) =>
      api.get<ProjectFileContentResponse>(`/projects/${projectId}/files/content`, {
        query: { path: path ?? undefined },
        signal,
      }),
    enabled: projectId !== undefined && path !== null,
  });
}
