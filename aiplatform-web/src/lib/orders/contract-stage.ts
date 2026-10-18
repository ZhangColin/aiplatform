import { CONTRACT_STATUS } from "@/lib/orders/contract";
import { ORDER_STATUS } from "@/lib/orders/lock";
import { PAYMENT_NODE_STATUS } from "@/lib/orders/payment";

/**
 * 签约付款页 P06 的三子步与黄金门禁推导（#348，spec #320 ⑥）：唯一判定点——
 * 消费方（阶段头卡进度条 / 黄金门禁横幅 / 各子步卡）只读结果不做判定。
 * 输入即真实订单/合同/付款状态机事实（REST 归一后的消费口径，缺省字段防御），
 * 不接聚合投影（deliveryStatus.state 是后端四源推导的旁证，非本函数的输入）。
 *
 * <p>三子步（核对范围与报价 / 签署合同 / 开工款支付与核验）各态：
 * {@code pending}=尚未可达、{@code current}=进行中、{@code done}=已完成。
 * 门禁公式照原型：{@code 【允许进入生产】= 合同要求的全部签署完成 && 合同约定的
 * 开工付款条件已满足}——右项＝开工门节点全部到账（status=received 才算足额，
 * 「已核到账但不足」的 pending 节点不算）。</p>
 */

export type SubstepState = "pending" | "current" | "done";

export type GoldenGateState = "locked" | "permitted";

export type ContractStage = {
  /** 子步一 核对范围与报价（范围冻结＝合同进场即 done）。 */
  scope: SubstepState;
  /** 子步二 签署电子合同（签署完成即 done）。 */
  contract: SubstepState;
  /** 子步三 开工款支付与核验（开工门节点全部到账即 done）。 */
  payment: SubstepState;
  /** 黄金门禁 DISPATCH LOCKED / GATE PERMITTED。 */
  gate: GoldenGateState;
  /** 门禁公式左项：合同签署完成。 */
  contractSigned: boolean;
  /** 门禁公式右项：开工付款条件已满足（开工门节点全部足额到账）。 */
  kickoffSettled: boolean;
  /** 有节点处「线下转账待核验」（Step 03 当前阶段「核验中」pill 的判据）。 */
  verifying: boolean;
};

/** 最小消费形状（消费方直接传 order/contract/paymentPlan 即可，结构兼容）。 */
export type ContractStageInput = {
  order?: { status?: number } | null;
  contract?: { status?: number } | null;
  paymentPlan?: { nodes: { kickoffGate?: boolean; status?: number }[] } | null;
};

/**
 * 开工门节点是否全部足额到账（门禁右项）：开工门节点＝kickoffGate 标记的节点；
 * 无标记不臆断为满足（后台录入是唯一事实源）。全到账＝每节点 status=received
 * （「已核到账但不足」的 pending 节点不算——差额呈现归付款卡）。
 */
function kickoffSettledOf(
  nodes: { kickoffGate?: boolean; status?: number }[] | null | undefined,
): boolean {
  const list = nodes ?? [];
  const kickoffNodes = list.filter((node) => node.kickoffGate);
  if (kickoffNodes.length === 0) {
    return false;
  }
  return kickoffNodes.every((node) => node.status === PAYMENT_NODE_STATUS.received);
}

/** 三子步 + 门禁推导（缺省输入防御归一：无单/无合同按全 pending、门禁锁定）。 */
export function contractStageOf(input: ContractStageInput): ContractStage {
  const contractStatus = input.contract?.status;
  const contractExists = contractStatus != null;
  const contractSigned = contractStatus === CONTRACT_STATUS.signed;
  const nodes = input.paymentPlan?.nodes ?? [];
  const kickoffSettled = kickoffSettledOf(nodes);
  // 订单在场且非终结（待报价/已报价；未知态按在场兜底——订单在即冻结）＝核对中。
  const orderStatus = input.order?.status;
  const orderTerminal =
    orderStatus === ORDER_STATUS.paid ||
    orderStatus === ORDER_STATUS.archived ||
    orderStatus === ORDER_STATUS.cancelled;
  const orderActive = input.order != null && !orderTerminal;

  // 子步一：订单在场且非终结即「核对范围与报价」进行中；合同进场（范围冻结）即完成。
  const scope: SubstepState = contractExists ? "done" : orderActive ? "current" : "pending";
  // 子步二：合同在场但未签署＝进行中；签署完成＝done；无合同＝不可达。
  const contract: SubstepState = !contractExists
    ? "pending"
    : contractSigned
      ? "done"
      : "current";
  // 子步三：签署完成前不可达；签署后未足额到账＝进行中；开工门足额到账＝done。
  const payment: SubstepState = !contractSigned
    ? "pending"
    : kickoffSettled
      ? "done"
      : "current";

  const gate: GoldenGateState =
    contractSigned && kickoffSettled ? "permitted" : "locked";
  const verifying = nodes.some((node) => node.status === PAYMENT_NODE_STATUS.awaitingVerification);

  return { scope, contract, payment, gate, contractSigned, kickoffSettled, verifying };
}
