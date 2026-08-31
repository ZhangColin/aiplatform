/**
 * 锁定式矩阵（#28 交易环①，#13 spec 前端节）：订单态 × UI 可用性的纯函数——
 * 指令区输入态与成果区只读口径的唯一推导点，消费方（项目页装配/指令区/订单卡）
 * 只读结果不做判定。行定义：
 *
 * | 态                 | 指令区       | 成果区     | 订单卡主操作面       |
 * |--------------------|--------------|------------|----------------------|
 * | 进行中（无未终结单）| 全功能       | 可操作     | 无卡（确认下单常驻） |
 * | 待报价/待支付       | 禁用+锁定提示| 只读可看   | 等待文案 + 取消      |
 * | 已支付/已归档终态   | 关闭         | 全只读终态 | 完整记录（#30 接线） |
 *
 * 输入即 REST 事实（项目详情的 archived + activeOrder 嵌入）；status 为
 * OrderStatus code（1=待报价 2=已报价 3=已支付 4=已归档 5=已取消）。
 */

/** 订单状态 code（后端 OrderStatus，BaseEnum REST 以 Integer code 传递）。 */
export const ORDER_STATUS = {
  pendingQuote: 1,
  quoted: 2,
  paid: 3,
  archived: 4,
  cancelled: 5,
} as const;

/** 未终结订单事实（项目详情/列表嵌入的 activeOrder，缺省字段防御归一后）。 */
export type ActiveOrderFact = {
  id: string;
  status?: number;
  statusName?: string;
};

/** 指令区输入态：open 可用 / locked 订单锁定（禁用+提示）/ closed 终态关闭。 */
export type ChatInputMode = "open" | "locked" | "closed";

/** 锁定式矩阵一行（UI 可用性的推导结果）。 */
export type LockRow = {
  chatInput: ChatInputMode;
  /** 指令区锁定/关闭提示文案（open 时不设）。 */
  chatHint?: string;
  /** 成果区只读（锁定与终态下仅可看；进行中随各模式自有交互）。 */
  outputsLocked: boolean;
};

/** 订单存在的锁定提示（spec 原文口径）。 */
export const ORDER_LOCK_HINT = "订单处理中——如需继续修改，请取消订单";

/**
 * 矩阵推导：已归档 → 终态行；未终结订单在 → 锁定行（待报价/待支付同锁定口径，
 * 未知态按锁定兜底——订单存在即冻结）；否则进行中全功能。
 */
export function lockRowOf(input: {
  archived?: boolean;
  activeOrder?: ActiveOrderFact | null;
}): LockRow {
  if (input.archived) {
    return {
      chatInput: "closed",
      chatHint: "项目已归档，指令区已关闭",
      outputsLocked: true,
    };
  }
  const order = input.activeOrder;
  if (order == null) {
    return { chatInput: "open", outputsLocked: false };
  }
  if (order.status === ORDER_STATUS.paid || order.status === ORDER_STATUS.archived) {
    return {
      chatInput: "closed",
      chatHint: "订单已完成，项目已归档",
      outputsLocked: true,
    };
  }
  return { chatInput: "locked", chatHint: ORDER_LOCK_HINT, outputsLocked: true };
}
