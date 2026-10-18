"use client";

import { X } from "lucide-react";

import { FilesPanel } from "@/components/project-page/files-panel";
import {
  Drawer,
  DrawerContent,
  DrawerDescription,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer";

/** Demo 页文件辅助抽屉：原成果区文件范式原位迁入，源码查看与下载门不变。 */
export function DemoFilesDrawer({
  projectId,
  open,
  onOpenChange,
}: {
  projectId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Drawer
      open={open}
      onOpenChange={onOpenChange}
      swipeDirection="right"
      modal={false}
    >
      <DrawerContent className="[--drawer-content-width:94vw] sm:[--drawer-content-width:72rem]">
        <DrawerHeader className="flex-row items-center justify-between gap-4 border-b border-slate-soft/70 p-4">
          <div>
            <DrawerTitle>项目文件与源码</DrawerTitle>
            <DrawerDescription>
              查看 PRD、源码与上传物料；下载权限沿用既有交付门。
            </DrawerDescription>
          </div>
          <button
            type="button"
            aria-label="关闭文件抽屉"
            onClick={() => onOpenChange(false)}
            className="rounded-lg p-2 text-ink-faint transition-colors hover:bg-surface-dawn hover:text-ink-primary"
          >
            <X className="size-4.5" aria-hidden="true" />
          </button>
        </DrawerHeader>
        <div className="min-h-0 flex-1">
          <FilesPanel projectId={projectId} hideConfirmGate />
        </div>
      </DrawerContent>
    </Drawer>
  );
}
