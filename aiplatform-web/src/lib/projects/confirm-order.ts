/**
 * 「确认下单」可见性（#26 迭代环①）：按钮随首次生成完成常驻指令区输入条上方，
 * 零迭代即可点（满意就下单，流程不被拉长）；仅无未终结订单时显示——订单存在
 * 期间指令区转订单状态视图，下单动作本体归交易环①（#28）接出。
 *
 * <p>输入即项目事实（REST 详情 + 订单域）：{@code generatedAt} = 首次生成时点
 * （单向置位，跨会话事实）；{@code activeOrderId} = 未终结订单（待报价/已报价），
 * 交易环①前订单面未接出、恒视为无——本函数只做规则判定，不做事实拉取。</p>
 */
export function confirmOrderVisible(input: {
  /** 首次生成时点（null/undefined = 从未生成——按钮不可见）。 */
  generatedAt?: string | null;
  /** 项目已归档（只读终态，指令区关闭）。 */
  archived?: boolean;
  /** 未终结订单标识（交易环①接出；null/undefined = 无未终结订单）。 */
  activeOrderId?: string | null;
}): boolean {
  return (
    input.generatedAt != null && input.archived !== true && input.activeOrderId == null
  );
}
