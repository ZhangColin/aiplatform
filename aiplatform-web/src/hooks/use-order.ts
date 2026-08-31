import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { errorText } from "@/lib/api/api-error";
import { normalizeOrder, type OrderResponse } from "@/lib/orders/detail";
import { ORDER_STATUS } from "@/lib/orders/lock";

/**
 * 订单数据层（#28 交易环① + #29 交易环②）：详情查询 + 下单/取消两动作。下单与
 * 取消都改变「项目挂着未终结订单」这一事实（详情 activeOrder 嵌入 = 锁定式矩阵
 * 推导输入），成功即失效整项目域——指令区锁定/解锁、订单卡进出、列表四态分区
 * 随重拉自愈；订单域自身也失效（旧订单详情不再被引用）。
 */

/** 报价等待期的详情轮询间隔（v1 无推送——spec：订单状态经详情拉取）。 */
const AWAITING_REFETCH_MS = 10_000;

/**
 * 订单详情（订单卡消费）：待报价/已报价态挂着轮询（后台报/改价后订单卡与改价
 * 历史实时可见），离开未支付态即停。
 */
export function useOrder(orderId: string | null | undefined) {
  return useQuery({
    queryKey: queryKeys.orders.detail(orderId ?? ""),
    queryFn: ({ signal }) =>
      api.get<OrderResponse>(`/orders/${orderId}`, { signal }).then(normalizeOrder),
    enabled: orderId != null && orderId !== "",
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === ORDER_STATUS.pendingQuote || status === ORDER_STATUS.quoted
        ? AWAITING_REFETCH_MS
        : false;
    },
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
