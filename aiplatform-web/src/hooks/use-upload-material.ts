import { useCallback } from "react";

import { api } from "@/lib/api/client";
import type { UploadedMaterial } from "@/lib/projects/materials";

/**
 * 上传图片物料（#286 发送框回形针真上传）：multipart 单文件 POST 落工作区物料
 * 目录——响应 path 即随话发送的附件载荷引用。每文件独立调用（Composer 按文件
 * 持 uploading/error 态，非 useMutation 单实例串行）；无文件树失效面（文件树
 * 面板已拆，旧 projects.files 键随之清场）。守卫（五格式/10MB）后端为准，前端
 * 预检在 Composer 侧（省注定 400 的上传）。
 */
export function useUploadMaterial(projectId: string) {
  return useCallback(
    async (file: File): Promise<UploadedMaterial> => {
      const form = new FormData();
      form.append("file", file);
      const uploaded = await api.post<UploadedMaterial>(
        `/projects/${projectId}/materials`,
        form,
        // 慢上行大文件不吃默认 30s 超时（#652 覆盖口径），2min 仍有界
        { timeoutMs: 120_000 },
      );
      return uploaded;
    },
    [projectId],
  );
}
