import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { UploadedMaterial } from "@/lib/projects/materials";

/**
 * 上传图片物料（#286 发送框回形针真上传）：multipart 单文件 POST 落工作区物料
 * 目录——响应 path 即随话发送的附件载荷引用。每文件独立调用（Composer 按文件
 * 持 uploading/error 态，非 useMutation 单实例串行）；成功连带失效文件树域
 * （materials/ 在交付面，文件区即时可见）。守卫（五格式/10MB）后端为准，前端
 * 预检在 Composer 侧（省注定 400 的上传）。
 */
export function useUploadMaterial(projectId: string) {
  const queryClient = useQueryClient();

  return useCallback(
    async (file: File): Promise<UploadedMaterial> => {
      const form = new FormData();
      form.append("file", file);
      const uploaded = await api.post<UploadedMaterial>(
        `/projects/${projectId}/materials`,
        form,
      );
      // 文件树即时反映新物料（点看走 raw 直出）
      await queryClient.invalidateQueries({ queryKey: queryKeys.projects.files(projectId) });
      return uploaded;
    },
    [projectId, queryClient],
  );
}
