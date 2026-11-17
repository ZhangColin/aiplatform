"use client";

import { BadgeCheck, CircleAlert, Landmark, ShieldCheck } from "lucide-react";
import { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { useCheckoutTopup, useProjectBilling, useValidateAccessCode } from "@/hooks/use-billing";
import { ApiError, errorText } from "@/lib/api/api-error";
import { useCheckoutStore } from "@/lib/store/checkout";
import { cn } from "@/lib/utils";

/**
 * 模拟收银台弹窗（#534 计费④，ADR-0047「模拟收银台」词条兑现）：四步流程——
 * ①准入码（错误/缺失被拦并如实引导——后端 BILL_019 message 指路联系平台，正确
 * 码才进金额选择）→ ②选金额（预设档＋自定义，元输入分落账）→ ③模拟通道确认页
 * （「模拟支付」徽标——渠道真接前的形态，渠道接入后只换通道、流程形态不变）→
 * ④即时到账回执（新余额＋流水已落）。只充当前上下文的扣减源池：目标池由项目
 * 账单读面 checkoutTarget 推导（账户源项目引导充我的账户——非源池选项不呈现，
 * 入账口 BILL_020 服务端双保险）。须先登录归触发侧（M01 先行＋resume 重开）。
 *
 * <p>全局唯一宿主挂 root layout（邻 M01）；开合与上下文归 {@link useCheckoutStore}。
 * 表单态随开门重挂归零（M01 先例）。</p>
 */

/** 预设充值档（分）——自定义输入补齐长尾（元→分换算归本组件）。 */
const PRESET_TIERS = [5_000, 20_000, 50_000, 200_000] as const;

/** 自定义金额上限（分＝¥100,000）——防呆输入，非定价主张。 */
const MAX_AMOUNT_FEN = 10_000_000;

/** 分 → 元呈现（Long 分不进 Number 运算精度事故——单次除法展示级换算）。 */
function yuan(fen: number): string {
  return (fen / 100).toFixed(2);
}

export function CheckoutDialog() {
  const open = useCheckoutStore((s) => s.open);
  const projectId = useCheckoutStore((s) => s.projectId);
  const closeCheckout = useCheckoutStore((s) => s.closeCheckout);

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) closeCheckout();
      }}
    >
      {/* 开门才挂表单：每次开门从准入码步起（重挂归零，免 effect 复位） */}
      {open ? <CheckoutForm key={projectId ?? "account"} projectId={projectId} /> : null}
    </Dialog>
  );
}

function CheckoutForm({ projectId }: { projectId: string | null }) {
  const closeCheckout = useCheckoutStore((s) => s.closeCheckout);
  const projectBilling = useProjectBilling(projectId ?? "", projectId != null);
  const validateCode = useValidateAccessCode();
  const topup = useCheckoutTopup();

  /** 目标池推导：账户上下文或项目 checkoutTarget=1（我的账户）→ 充账户池；否则本项目。 */
  const targetAccount =
    projectId == null || (projectBilling.data && projectBilling.data.checkoutTarget === 1);
  const targetLabel = !projectId
    ? "我的账户（账户余额）"
    : targetAccount
      ? "我的账户（该项目的扣减来源）"
      : "本项目余额";

  const [step, setStep] = useState<"code" | "amount" | "confirm" | "done">("code");
  const [code, setCode] = useState("");
  const [picked, setPicked] = useState<number | null>(null);
  const [custom, setCustom] = useState("");
  const [error, setError] = useState<string | null>(null);
  /** 到账回执（新余额＋实充金额——回执步呈现）。 */
  const [receipt, setReceipt] = useState<{ amountFen: number; balanceFen: number } | null>(null);

  /** 选定金额（分）：预设档或自定义换算；自定义非法返回 null。 */
  function amountFen(): number | null {
    if (picked != null) return picked;
    const yuanValue = Number(custom);
    if (custom.trim() === "" || Number.isNaN(yuanValue) || yuanValue <= 0) return null;
    return Math.round(yuanValue * 100);
  }

  async function onValidateCode() {
    setError(null);
    try {
      await validateCode.mutateAsync(code.trim());
      setStep("amount");
    } catch (e) {
      setError(errorText(e, "准入码校验失败，请稍后重试"));
    }
  }

  function onToConfirm() {
    const amount = amountFen();
    if (amount == null || amount > MAX_AMOUNT_FEN) {
      setError(amount != null && amount > MAX_AMOUNT_FEN ? "单次充值不能超过 ¥100,000" : "请输入有效充值金额");
      return;
    }
    setError(null);
    setStep("confirm");
  }

  async function onPay() {
    const amount = amountFen();
    if (amount == null) return;
    setError(null);
    try {
      const result = await topup.mutateAsync({
        // 账户源项目＝充我的账户（projectId 归 null 走账户端点——死钱防线的
        // 消费面一致性：UI 说我的账户，请求就打账户端点，不碰 BILL_020）
        projectId: targetAccount ? null : projectId,
        amountFen: amount,
        accessCode: code.trim(),
      });
      setReceipt({ amountFen: amount, balanceFen: result?.pool?.balance ?? 0 });
      setStep("done");
      // #544 引导流「到账即翻转」：续跑回调随到账触发（空 source 重判——免用户
      // 重走选择）；未传（无续跑语义来路）零影响
      useCheckoutStore.getState().onDone?.();
    } catch (e) {
      // BILL_019/BILL_020/BILL_002 message 含处置语义——就地呈现，可改口重试
      setError(errorText(e, "充值失败，请稍后重试"));
      if (e instanceof ApiError && e.code === 8019) setStep("code");
    }
  }

  return (
    <DialogContent className="sm:max-w-md" data-checkout>
      <DialogHeader>
        <DialogTitle>模拟收银台</DialogTitle>
        <DialogDescription>
          {step === "done"
            ? "充值已到账"
            : `充值到：${targetLabel}——只充当前扣减源池，向其他池充值无法用于本项目扣减。`}
        </DialogDescription>
      </DialogHeader>

      {step === "code" ? (
        <div className="space-y-2">
          <label htmlFor="checkout-code" className="text-sm font-medium">
            充值准入码
          </label>
          <Input
            id="checkout-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="向平台索取的充值准入码"
            disabled={validateCode.isPending}
            onKeyDown={(e) => {
              if (e.key === "Enter" && code.trim() !== "") void onValidateCode();
            }}
          />
          <p className="text-xs text-muted-foreground">
            准入码控制自助充值资格（销售/活动发码）——没有准入码？联系平台索取。
          </p>
        </div>
      ) : null}

      {step === "amount" ? (
        <div className="space-y-3">
          <div className="flex flex-wrap gap-2" data-checkout-tiers>
            {PRESET_TIERS.map((tier) => (
              <button
                key={tier}
                type="button"
                data-tier={tier}
                aria-pressed={picked === tier}
                onClick={() => {
                  setPicked(tier);
                  setCustom("");
                  setError(null);
                }}
                className={cn(
                  "rounded-lg border px-3 py-1.5 text-sm transition-colors",
                  picked === tier
                    ? "border-primary bg-primary/5 font-medium"
                    : "hover:border-primary/50",
                )}
              >
                ¥{yuan(tier)}
              </button>
            ))}
          </div>
          <div className="space-y-1.5">
            <label htmlFor="checkout-custom" className="text-sm font-medium">
              自定义金额（元）
            </label>
            <Input
              id="checkout-custom"
              value={custom}
              inputMode="decimal"
              placeholder="如 123.45"
              onChange={(e) => {
                setCustom(e.target.value);
                setPicked(null);
                setError(null);
              }}
            />
          </div>
        </div>
      ) : null}

      {step === "confirm" ? (
        <div className="space-y-3" data-checkout-confirm>
          <div className="flex items-center gap-2 rounded-lg border border-dashed border-primary/40 bg-primary/5 px-3 py-2">
            <Landmark className="size-4 text-primary" />
            <div className="flex min-w-0 flex-col">
              <span className="text-sm font-semibold">¥{yuan(amountFen() ?? 0)}</span>
              <span className="text-xs text-muted-foreground">{targetLabel}</span>
            </div>
            <span
              data-checkout-simulated-badge
              className="ml-auto inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
            >
              <ShieldCheck className="size-3" />
              模拟支付
            </span>
          </div>
          <p className="text-xs text-muted-foreground">
            确认后即时到账并记入流水（渠道真接后只换通道、流程不变）。
          </p>
        </div>
      ) : null}

      {step === "done" && receipt ? (
        <div className="space-y-2" data-checkout-done>
          <div className="flex items-center gap-2 text-sm font-medium text-green-700">
            <BadgeCheck className="size-4" />
            已到账 ¥{yuan(receipt.amountFen)}
          </div>
          <p className="text-sm text-muted-foreground">
            {targetLabel}当前余额：<span className="font-semibold text-foreground">¥{yuan(receipt.balanceFen)}</span>
            ，流水已记录——可在「我的账户」查看。
          </p>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="flex items-start gap-1.5 text-sm text-destructive">
          <CircleAlert className="mt-0.5 size-3.5 shrink-0" />
          {error}
        </p>
      ) : null}

      <DialogFooter>
        <Button variant="ghost" onClick={closeCheckout} disabled={topup.isPending}>
          {step === "done" ? "完成" : "取消"}
        </Button>
        {step === "code" ? (
          <Button
            data-checkout-next
            onClick={() => void onValidateCode()}
            disabled={validateCode.isPending || code.trim() === ""}
          >
            {validateCode.isPending ? <Spinner className="size-3.5" /> : null}
            验证并选择金额
          </Button>
        ) : null}
        {step === "amount" ? (
          <Button data-checkout-next onClick={onToConfirm} disabled={amountFen() == null}>
            去支付
          </Button>
        ) : null}
        {step === "confirm" ? (
          <Button data-checkout-pay onClick={() => void onPay()} disabled={topup.isPending}>
            {topup.isPending ? <Spinner className="size-3.5" /> : null}
            确认支付 ¥{yuan(amountFen() ?? 0)}
          </Button>
        ) : null}
      </DialogFooter>
    </DialogContent>
  );
}
