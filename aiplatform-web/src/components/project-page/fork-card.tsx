"use client";

import { MessageCircle, MessagesSquare, PlayCircle, Rocket } from "lucide-react";
import { useEffect, useRef } from "react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Spinner } from "@/components/ui/spinner";
import { useContinueDiscussion } from "@/hooks/use-continue-discussion";
import { useMe } from "@/hooks/use-me";
import { useResumeGeneration } from "@/hooks/use-resume-generation";
import { useCheckoutStore } from "@/lib/store/checkout";
import { useM01Store } from "@/lib/store/m01";

/**
 * 岔路口三选卡（#542 访谈②＋#544 访谈③引导流落位，ADR-0048/0049——自动派发两
 * 入口退役、首建点火归用户）：试用态访谈自然收口（判定明确含提前明确——非快道）
 * 后的选择卡，持久可回（关页回来卡仍在，呈现条件＝GET /build-gate forkCardPending
 * 服务端单源）。三选：
 *
 * 「继续讨论（由我的账户付费）」／「继续讨论（由本项目付费）」＝付费轨引导入口
 * （#544，翻转门①三选上卡压平版——扣减源随选择一次选定）：命令回执分岔——
 * flipped＝按所选池判定余额为正、已翻转回访谈（卡随读面熄灭、五问额度解除）；
 * rechargeRequired＝源池非正、引导充值（源已选定不可自助改口）——开模拟收银台
 * （只充当前扣减源池），到账回执（onDone）即 settle 重判（空 source）翻转、无需
 * 重走选择。访客编排（ADR-0048：选付费项才走 M01 登录＋项目平移——平移归登录
 * 编排 bindOnLogin）：账户付费未登录先 M01（resume 续跑引导命令）；引导充值未
 * 登录先 M01、resume 重开收银台。后台已充项目池的项目＝源已选定（入账即选择），
 * 「由本项目付费」一次命令直达翻转＝免登录直接翻转（销售演示动线）；autoSettle
 * （读面 forkSettleDue 信号：卡∧源已选∧池正）在场即挂载自动 settle——「到账
 * 即翻转」不止收银台 onDone 单缝：他处到账（账户页/项目账单节/他端）后卡回读
 * 即翻转、无需重走选择。中途放弃（关收银台/关 M01）＝不落翻转、卡留岔路口，
 * 随时改选「立即生成」。
 *
 * 「立即生成」＝触发首个构建派发（POST /generate，平台承担本次——快道〔收敛令/
 * 零必答短路〕不经本卡直派）。
 */
export function ForkCard({
  projectId,
  onSeePrd,
  autoSettle = false,
}: {
  projectId: string;
  /** 「查看 PRD」跳转口（归装配层；缺席不渲染该按钮）。 */
  onSeePrd?: () => void;
  /** 到账自动翻转信号（GET /build-gate forkSettleDue——卡在场∧源已选定∧源池
   *  余额为正；装配层读面单源传入，本件只在场时发一次空 source 重判）。 */
  autoSettle?: boolean;
}) {
  const generate = useResumeGeneration(projectId);
  const guide = useContinueDiscussion(projectId);
  const me = useMe();
  const loggedIn = me.data != null;
  const busy = guide.isPending;
  const autoSettledRef = useRef(false);

  /** 最新引导命令的 ref 面（随渲染刷新归无依赖 effect——refs 渲染期禁读规则；
   *  命令闭包每渲染重建、effect 依赖只留信号与 busy）。 */
  const startGuideRef = useRef(startGuide);
  useEffect(() => {
    startGuideRef.current = startGuide;
  });

  /** 到账自动 settle（#544——只发一次：翻转后读面回源、信号随试用态熄灭）。 */
  useEffect(() => {
    if (!autoSettle || autoSettledRef.current || busy) return;
    autoSettledRef.current = true;
    startGuideRef.current({});
  }, [autoSettle, busy]);

  /** 引导命令（随卡选源/到账 settle 同口）——成功分岔＝翻转回执 / 引导充值。 */
  function startGuide(vars: { source?: 1 | 2 }) {
    guide.mutate(vars, {
      onSuccess: (result) => {
        if (result.flipped) {
          toast.success("已转为付费，继续讨论不再受试用额度限制");
        } else if (result.rechargeRequired) {
          openRecharge();
        }
      },
    });
  }

  /** 到账后重判（settle）：源已随选择一次选定，空 source 直打——源池回正即翻转。 */
  function settle() {
    startGuide({});
  }

  /**
   * 引导充值：开模拟收银台（#534——只充当前扣减源池，目标池推导归账单读面），
   * 到账回执（onDone）即 settle。访客先 M01（fork-login 来路——登录＋项目平移归
   * 登录编排），验码成功 resume 重开收银台。
   */
  function openRecharge() {
    if (loggedIn) {
      useCheckoutStore.getState().openCheckout(projectId, settle);
    } else {
      useM01Store.getState().openM01("fork-login", () => {
        useCheckoutStore.getState().openCheckout(projectId, settle);
      });
    }
  }

  /** 「由我的账户付费」：访客先 M01（验码成功 resume 续跑命令——平移后账户定格带得上；服务端 BILL_009 双保险指路）。 */
  function continueByAccount() {
    if (busy) return;
    if (loggedIn) {
      startGuide({ source: 1 });
    } else {
      useM01Store.getState().openM01("fork-login", () => startGuide({ source: 1 }));
    }
  }

  /** 「由本项目付费」：后台已充项目池＝源已选定（入账即选择），服务端直达翻转＝免登录直翻转；池空＝引导充值支（访客先 M01——收银台须登录）。 */
  function continueByProject() {
    if (busy) return;
    startGuide({ source: 2 });
  }

  return (
    <div
      data-slot="fork-card"
      className="flex w-full max-w-lg flex-col gap-3 rounded-2xl border border-primary/25 bg-primary/[0.04] p-4 shadow-xs"
    >
      <div className="flex items-start gap-2">
        <Rocket className="mt-0.5 size-4 shrink-0 text-primary" />
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="text-sm font-semibold">
            需求初稿已就绪，接下来想怎么继续？
          </span>
          <span className="text-xs leading-relaxed text-muted-foreground">
            您的 PRD 已整理好，可以先回看再决定；继续提问讨论会转为付费，立即生成本次由平台承担。
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-1.5" data-fork-choices>
        <ChoiceRow
          icon={<MessagesSquare className="size-3.5" />}
          title="继续讨论（由我的账户付费）"
          hint="还想再聊聊？继续提问讨论，按用量从我的账户余额扣减"
          action={
            <Button
              size="sm"
              data-testid="fork-continue-account"
              disabled={busy}
              onClick={continueByAccount}
            >
              {busy ? <Spinner className="size-3.5" /> : null}
              继续讨论
            </Button>
          }
        />
        <ChoiceRow
          icon={<MessageCircle className="size-3.5" />}
          title="继续讨论（由本项目付费）"
          hint="继续提问讨论，按用量从本项目余额扣减"
          action={
            <Button
              size="sm"
              data-testid="fork-continue-project"
              disabled={busy}
              onClick={continueByProject}
            >
              {busy ? <Spinner className="size-3.5" /> : null}
              继续讨论
            </Button>
          }
        />
        <ChoiceRow
          icon={<PlayCircle className="size-3.5" />}
          title="立即生成"
          hint="按当前 PRD 立即生成系统先体验，想修改再付费继续"
          action={
            <Button
              size="sm"
              data-fork-generate
              disabled={generate.isPending}
              onClick={() => generate.mutate(undefined)}
            >
              {generate.isPending ? <Spinner className="size-3.5" /> : null}
              立即生成
            </Button>
          }
        />
      </div>

      {onSeePrd ? (
        <Button
          size="sm"
          variant="outline"
          data-fork-see-prd
          className="self-start"
          onClick={onSeePrd}
        >
          查看 PRD
        </Button>
      ) : null}
    </div>
  );
}

/** 一条选择行（三选皆可点火：付费两选＝#544 引导流入口，立即生成＝首建点火）。 */
function ChoiceRow({
  icon,
  title,
  hint,
  action,
}: {
  icon: React.ReactNode;
  title: string;
  hint: string;
  action: React.ReactNode;
}) {
  return (
    <div
      data-fork-choice={title}
      className="flex items-center gap-2 rounded-lg border border-primary/30 bg-background px-3 py-2"
    >
      <span className="shrink-0 text-muted-foreground">{icon}</span>
      <div className="flex min-w-0 flex-col">
        <span className="text-xs font-medium">{title}</span>
        <span className="text-xs text-muted-foreground">{hint}</span>
      </div>
      <div className="ml-auto shrink-0">{action}</div>
    </div>
  );
}
