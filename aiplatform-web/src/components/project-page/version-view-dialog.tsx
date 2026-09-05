"use client";

import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Spinner } from "@/components/ui/spinner";

/**
 * 「查看当时」快照预览弹窗（#92/#93）：把快照容器预览嵌进 iframe 的呈现面——
 * 只逛不换的当时系统（当时代码 + 现在数据），关窗即销毁（快照容器随可写层消失）。
 * 本组件纯呈现：起/停快照的动作与在途态归收尾卡（ClosingCard）持有，这里只消费
 * pending / error / previewUrl 三态（起服中 → 失败 → 可浏览）。
 */
export function VersionViewDialog({
  open,
  onOpenChange,
  pending,
  error,
  previewUrl,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  pending: boolean;
  error: boolean;
  previewUrl?: string;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex h-[80vh] max-w-4xl flex-col gap-0 overflow-hidden p-0">
        <DialogHeader className="shrink-0 border-b px-4 py-3">
          <DialogTitle>查看当时</DialogTitle>
        </DialogHeader>
        <div className="min-h-0 flex-1 bg-white">
          {pending ? (
            <div className="flex h-full items-center justify-center gap-2 text-sm text-muted-foreground">
              <Spinner />
              正在准备当时系统…
            </div>
          ) : error ? (
            <div className="flex h-full items-center justify-center text-sm text-destructive">
              快照起服失败，请关闭后重试
            </div>
          ) : previewUrl ? (
            <iframe src={previewUrl} title="当时系统快照" className="h-full w-full border-0 bg-white" />
          ) : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
