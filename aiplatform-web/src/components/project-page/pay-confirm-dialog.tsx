"use client";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

/**
 * mock 支付确认弹窗（#30 交易环③；#203 起订单面板与报价卡共用）：确认即同步
 * 成功——订单与项目一并归档。开闭状态位与支付动作归调用方持有。
 */
export function PayConfirmDialog({
  open,
  onOpenChange,
  price,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 价格文案（formatPrice 已格式化，如 ¥1,280；缺省 = 价格待定）。 */
  price?: string;
  onConfirm: () => void;
}) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>确认支付 {price ?? ""}？</AlertDialogTitle>
          <AlertDialogDescription>
            支付成功后订单与项目将一并归档，项目转入只读终态——完整记录会保留在这里，
            源码包可随时下载；如还需修改，请先取消订单。
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>再想想</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm}>
            确认支付
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
