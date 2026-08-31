import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { errorText } from "@/lib/api/api-error";
import { normalizeOrder, type OrderResponse } from "@/lib/orders/detail";

/**
 * 订单数据层（#28 交易环①）：详情查询 + 下单/取消两动作。下单与取消都改变
 * 「项目挂着未终结订单」这一事实（详情 activeOrder 嵌入 = 锁定式矩阵推导输入），
 * 成功即失效整项目域——指令区锁定/解锁、订单卡进出、列表四态分区随重拉自愈；
 * 订单域自身也失效（旧订单详情不再被引用）。
 */

/** 订单详情（订单卡消费；金额与价目留痕随 #29 增补）。 */
export function useOrder(orderId: string | null | undefined) {
  return useQuery({
    queryKey: queryKeys.orders.detail(orderId ?? ""),
    queryFn: ({ signal }) =>
      api.get<OrderResponse>(`/orders/${orderId}`, { signal }).then(normalizeOrder),
    enabled: orderId != null && orderId !== "",
  });
}

/** 确认下单（纯按钮零输入）：冻结当前 PRD 快照入单，项目转待报价锁定态。 */
export function usePlaceOrder(projectId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<OrderResponse>(`/projects/${projectId}/orders`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "下单失败，请稍后重试"));
    },
  });
}

/** 取消订单（未支付态）：取消即解冻回迭代态，指令区恢复受理意见。 */
export function useCancelOrder() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: string) => api.post<OrderResponse>(`/orders/${orderId}/cancel`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "取消订单失败，请稍后重试"));
    },
  });
}
