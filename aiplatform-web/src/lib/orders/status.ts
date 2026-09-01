import { ORDER_STATUS } from "@/lib/orders/lock";

/**
 * 订单态变化 toast 文案（#30 交易环③，纯函数）：`order-status-changed` SSE 的
 * 用户面话术唯一推导点——桥（sse/bridge）按载荷 status 取文案，点击直达项目页。
 * 文案口径随状态细分（用户语言、说清楚发生了什么/该做什么），未知态回落状态名。
 */
export function orderStatusToastText(status?: number, statusName?: string): string {
  switch (status) {
    case ORDER_STATUS.pendingQuote:
      return "订单已提交，后台将尽快报价";
    case ORDER_STATUS.quoted:
      return "报价已出，可以支付了";
    case ORDER_STATUS.paid:
    case ORDER_STATUS.archived:
      return "支付完成，项目已归档";
    case ORDER_STATUS.cancelled:
      return "订单已取消，项目已恢复迭代";
    default:
      return statusName ? `订单状态更新：${statusName}` : "订单状态已更新";
  }
}
