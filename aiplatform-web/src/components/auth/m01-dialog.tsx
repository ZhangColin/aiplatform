"use client";

import { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";

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
import { ApiError, errorText } from "@/lib/api/api-error";
import { UNAUTHORIZED_EVENT } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { sendCode, verifyCode } from "@/lib/auth/code-login";
import { useM01Store, type M01Trigger } from "@/lib/store/m01";

/**
 * M01 登录与项目绑定弹窗（#327 后端面 / #328 落地本体）：两步式验证码登录——
 * 输入邮箱/手机号 → 发码 → 输六位码 → 验证并继续。登录即注册、登录即自动平移
 * 访客项目（后端 #328）。开合与来路归 {@link useM01Store}；本组件是唯一宿主，
 * 挂全局 layout 一份（顶栏「保存项目」、签约门、401 出口三处触发共用）。
 *
 * <p>三处触发：①「保存项目」开 save-project 文案；②签约门（下单入口接到
 * ORD_017/5017）开 stage-gate 文案并携 resume 续跑回调，验码成功即回调续跑本单
 * （「绑定后放行」）；③401 全局出口（{@link UNAUTHORIZED_EVENT}）开登录——
 * 无续跑语义（请求保持 error 态，登录后前端按域失效自愈）。</p>
 *
 * <p>表单态随时开重挂（{@link M01Form} 仅在开门时挂载）自然归零，不用 effect
 * 复位。码验证错误（ApiError 6005–6008）就地出后端中文 message，不 toast。</p>
 */
export function M01Dialog() {
  const open = useM01Store((s) => s.open);
  const trigger = useM01Store((s) => s.trigger);
  const openM01 = useM01Store((s) => s.openM01);
  const closeM01 = useM01Store((s) => s.closeM01);

  // 401 全局出口：弹登录（无续跑语义——登录后各域失效自愈）
  useEffect(() => {
    const onUnauthorized = () => openM01("save-project");
    window.addEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, onUnauthorized);
  }, [openM01]);

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) closeM01();
      }}
    >
      {/* 开门才挂表单：每次开门从第一步起（重挂归零，免 effect 复位） */}
      {open && trigger ? <M01Form trigger={trigger} /> : null}
    </Dialog>
  );
}

function M01Form({ trigger }: { trigger: M01Trigger }) {
  const closeM01 = useM01Store((s) => s.closeM01);
  const queryClient = useQueryClient();

  const [step, setStep] = useState<"target" | "code">("target");
  const [target, setTarget] = useState("");
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const copy = COPY[trigger];

  async function onSendCode() {
    setBusy(true);
    setError(null);
    try {
      await sendCode(target.trim());
      setStep("code");
    } catch (e) {
      setError(errorText(e, "验证码发送失败，请稍后重试"));
    } finally {
      setBusy(false);
    }
  }

  async function onVerify() {
    setBusy(true);
    setError(null);
    try {
      await verifyCode(target.trim(), code.trim());
      // 登录态与项目域自愈（平移后项目归账号、顶栏头像转登录态）
      void queryClient.invalidateQueries({ queryKey: queryKeys.me });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      const { resume } = useM01Store.getState();
      closeM01();
      resume?.(); // 签约门「绑定后放行」：续跑被拦的本单
    } catch (e) {
      setError(errorText(e, "登录失败，请稍后重试"));
      if (e instanceof ApiError && e.code === 6008) {
        setCode(""); // 码错或过期：清空重输
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <DialogContent className="sm:max-w-md">
      <DialogHeader>
        <DialogTitle>{copy.title}</DialogTitle>
        <DialogDescription>{copy.description}</DialogDescription>
      </DialogHeader>

      {step === "target" ? (
        <div className="space-y-2">
          <label htmlFor="m01-target" className="text-sm font-medium">
            邮箱 / 手机号
          </label>
          <Input
            id="m01-target"
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            placeholder="邮箱或手机号"
            autoComplete="email"
            disabled={busy}
            onKeyDown={(e) => {
              if (e.key === "Enter" && target.trim() !== "") void onSendCode();
            }}
          />
        </div>
      ) : (
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">验证码已发送至 {target}</p>
          <label htmlFor="m01-code" className="text-sm font-medium">
            验证码
          </label>
          <Input
            id="m01-code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            inputMode="numeric"
            maxLength={6}
            placeholder="六位验证码"
            autoComplete="one-time-code"
            disabled={busy}
            onKeyDown={(e) => {
              if (e.key === "Enter" && code.trim().length === 6) void onVerify();
            }}
          />
          <button
            type="button"
            className="text-xs text-muted-foreground underline underline-offset-3 hover:text-foreground"
            onClick={() => {
              setStep("target");
              setCode("");
              setError(null);
            }}
            disabled={busy}
          >
            重新发送 / 修改输入
          </button>
        </div>
      )}

      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}

      <DialogFooter>
        <Button variant="ghost" onClick={closeM01} disabled={busy}>
          取消
        </Button>
        {step === "target" ? (
          <Button onClick={() => void onSendCode()} disabled={busy || target.trim() === ""}>
            {busy ? "发送中…" : "获取验证码"}
          </Button>
        ) : (
          <Button onClick={() => void onVerify()} disabled={busy || code.trim().length !== 6}>
            {busy ? "验证中…" : "验证并继续"}
          </Button>
        )}
      </DialogFooter>
    </DialogContent>
  );
}

/** 来路文案（save-project 缺省；stage-gate 签约门）。 */
const COPY = {
  "save-project": {
    title: "登录保存项目",
    description: "登录后当前项目保存到你的账号，换设备也能继续；未登录的项目只在本浏览器可见。",
  },
  "stage-gate": {
    title: "登录后进入签约付款",
    description:
      "进入签约付款流程需要先登录绑定账号——你已保存的项目会一并归入账号，跨设备可继续；签约联系人与签约主体可在账户内维护。",
  },
} as const;
