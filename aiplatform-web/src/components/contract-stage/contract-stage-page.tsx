"use client";

import Link from "next/link";
import { useEffect, useRef } from "react";

import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { useContract, useConfirmScope } from "@/hooks/use-contract";
import { useMe } from "@/hooks/use-me";
import { useCancelOrder, useOrder } from "@/hooks/use-order";
import { usePaymentPlan } from "@/hooks/use-payment";
import { useDeliveryStatus } from "@/hooks/use-production";
import { useProject } from "@/hooks/use-project";
import { errorText } from "@/lib/api/api-error";
import { contractStageOf } from "@/lib/orders/contract-stage";
import { useM01Store } from "@/lib/store/m01";

import { ContractHeader } from "./contract-header";
import { ContractSignCard } from "./contract-sign-card";
import { GoldenGateBanner } from "./golden-gate-banner";
import { PaymentCard } from "./payment-card";
import { ProductionTrackCard } from "./production-track-card";
import { ScopeAndQuoteCard } from "./scope-and-quote-card";

/**
 * 签约付款页 P06（#348，spec #320 ⑥ IA 全量重建——阶段页制的签约付款阶段页，
 * 替换 #346 的过渡桥）：阶段头卡＋三子步进度条＋黄金门禁横幅＋左 5 右 7 双栏
 * （左＝范围与报价/电子签名存证，右＝开工款结算节点/生产承接工作站）。
 *
 * <p>数据面＝订单＋合同＋付款计划＋交付状态四源（均挂 orders/projects 域轮询），
 * 三子步与门禁态由 {@link contractStageOf} 纯推导。M01 登录门＝进入本页时：未
 * 绑定账号（/me 无会话）即弹登录绑定（stage-gate 来路），登录后平移访客项目并
 * 自愈——续跑无需（门在进页、不在下单动作）。</p>
 */
export function ContractStagePage({ projectId }: { projectId: string }) {
  const { data: detail, isPending, isError, error, refetch } = useProject(projectId);
  // 只取未终结订单（待报价/已报价）——已取消/已归档没有签约付款动作面（取消回迭代、
  // 归档转生产承接），latestOrder 的终态单不落此页（不呈现可动作态）。
  const orderId = detail?.activeOrder?.id ?? null;
  const { data: order } = useOrder(orderId);
  const { data: contract } = useContract(orderId);
  const { data: paymentPlan } = usePaymentPlan(orderId, order?.status);
  const { data: deliveryStatus } = useDeliveryStatus(projectId);
  const confirmScope = useConfirmScope();
  const cancelOrder = useCancelOrder();

  const { data: me, isPending: mePending } = useMe();
  const openM01 = useM01Store((s) => s.openM01);

  // M01 登录门＝进入本页时（#348）：/me 无会话（访客/匿名）即弹登录绑定。登录后
  // 平移访客项目并失效 me/projects 域自愈；ref 守卫防 StrictMode 双挂载重复开门。
  const gated = useRef(false);
  useEffect(() => {
    if (gated.current || mePending) return;
    if (!me) {
      gated.current = true;
      openM01("stage-gate");
    }
  }, [me, mePending, openM01]);

  const stage = contractStageOf({ order, contract, paymentPlan });

  if (isError) {
    return (
      <div className="flex min-h-[calc(100svh-4rem)] flex-col items-center justify-center gap-3 text-body-md text-ink-faint">
        <p>{errorText(error, "项目加载失败")}</p>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => void refetch()}>
            重试
          </Button>
          <Button variant="ghost" size="sm" nativeButton={false} render={<Link href="/projects" />}>
            返回项目列表
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto w-full max-w-[1600px] px-4 py-6 lg:px-8">
      <ContractHeader projectName={detail?.name ?? ""} stage={stage} loading={isPending} />

      <div className="mt-4">
        <GoldenGateBanner
          gate={stage.gate}
          contractSigned={stage.contractSigned}
          kickoffSettled={stage.kickoffSettled}
        />
      </div>

      <div className="mt-5 grid grid-cols-1 items-start gap-5 xl:grid-cols-12">
        <div className="flex flex-col gap-5 xl:col-span-5">
          {isPending ? (
            <Skeleton className="h-80 w-full rounded-2xl" />
          ) : orderId == null ? (
            <section
              aria-label="核对范围与报价"
              className="rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1"
            >
              <p className="text-body-sm text-ink-faint">
                {detail?.archived
                  ? "项目已归档，签约付款流程已完成。"
                  : "当前没有进行中的订单；确认 Demo 版本进入签约后，订单会在这里呈现。"}
              </p>
            </section>
          ) : (
            <ScopeAndQuoteCard
              projectName={detail?.name ?? ""}
              order={order}
              contract={contract}
              paymentPlan={paymentPlan}
              confirmPending={confirmScope.isPending}
              onConfirmScope={() => {
                if (orderId) confirmScope.mutate(orderId);
              }}
              onCancelOrder={() => {
                if (orderId) cancelOrder.mutate(orderId);
              }}
              cancelPending={cancelOrder.isPending}
            />
          )}
          <ContractSignCard contract={contract ?? null} />
        </div>

        <div className="flex flex-col gap-5 xl:col-span-7">
          {paymentPlan ? (
            <PaymentCard plan={paymentPlan} />
          ) : (
            <section
              aria-label="开工款支付与核验"
              className="rounded-2xl border border-slate-soft/80 bg-white p-5 shadow-e1"
            >
              <p className="text-body-sm text-ink-faint">
                确认范围并准备合同后，付款节点会在这里生成；按节点付款并上传凭证核验到账。
              </p>
            </section>
          )}
          <ProductionTrackCard
            projectId={projectId}
            stage={stage}
            handoff={deliveryStatus?.handoff ?? null}
            stateName={deliveryStatus?.stateName}
          />
        </div>
      </div>
    </div>
  );
}
