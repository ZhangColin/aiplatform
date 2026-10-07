import { toast } from "sonner";

import type { ApiEnvelopeMeta } from "@/lib/api/api-error";

/**
 * 工作区文件下载的浏览器腿单点（#287 通用下载；#294 预览弹窗下载接入收口）：
 * 同源直链 fetch 取 blob → 临时锚点落盘。支付门判定在后端——前端不预判门态，
 * 被拦时直出后端信封 message 如实告知（402 ORD_015）；二进制不走 api client
 * （其响应一律按 JSON 解包——file-download 先例）。blob 锚点落盘不走响应头
 * （Content-Disposition 的后端消毒对它无效），落盘名由调用侧给出（已消毒口径）。
 * pending 态归调用侧（各自 UI 形态不同）。
 */
export async function downloadWorkspaceFile(url: string, fileName: string): Promise<void> {
  try {
    const res = await fetch(url);
    if (!res.ok) {
      // 错误面与 api client 同形（信封 message 直出——支付门语义如实告知）
      const payload: ApiEnvelopeMeta | null = await res.json().catch(() => null);
      toast.error(payload?.message ?? `下载失败（HTTP ${res.status}）`);
      return;
    }
    const blob = await res.blob();
    const objectUrl = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = objectUrl;
    anchor.download = fileName || "download";
    anchor.click();
    URL.revokeObjectURL(objectUrl);
  } catch {
    // 网络层失败（fetch 抛出）：如实提示，不假装成功
    toast.error("下载失败，请稍后重试");
  }
}
