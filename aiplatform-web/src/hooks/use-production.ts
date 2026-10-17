import { useQuery } from "@tanstack/react-query";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { ApiError } from "@/lib/api/api-error";
import {
  normalizeDeliveryStatus,
  type DeliveryStatusResponse,
} from "@/lib/orders/production";

/**
 * 交付状态数据层（#338 前台六态纯推导）：项目维度投影查询（订单＋合同＋付款
 * ＋承接四源一次读齐，后端现算不存储）。无未终结订单（404 ORD_001/PRJ_001）
 * 归一为 null＝生产状态卡缺席不渲染；开工判定转已支付经 order-status-changed
 * SSE 随 orders 域失效，承接推进/标志置位无推送——卡在场即挂轮询兜底（后台
 * 人工承接面变化实时可见，同合同卡口径）。
 */

/** 承接推进等待期的轮询间隔（后台人工推进/进展更新，状态变化实时可见）。 */
const DELIVERY_REFETCH_MS = 10_000;

/** 无未终结订单业务码（ORD_001＝5001，域码 5×1000＋1；PRJ_001＝4001 同缺席）。 */
const NO_ORDER_CODES = [5001, 4001];

/**
 * 项目交付状态（生产状态卡消费）：无未终结订单（或项目缺档）归一为 null——
 * 「无交付生命周期」是一等状态不是错误；卡在场（data 非 null）挂 10s 轮询
 * （六态与承接字段随后台动作刷新），缺席即停。
 */
export function useDeliveryStatus(projectId: string | null | undefined) {
  return useQuery({
    queryKey: queryKeys.orders.deliveryStatus(projectId ?? ""),
    queryFn: async ({ signal }) => {
      try {
        return normalizeDeliveryStatus(
          await api.get<DeliveryStatusResponse>(`/projects/${projectId}/delivery-status`, {
            signal,
          }),
        );
      } catch (error) {
        if (error instanceof ApiError && error.code != null && NO_ORDER_CODES.includes(error.code)) {
          return null;
        }
        throw error;
      }
    },
    enabled: projectId != null && projectId !== "",
    refetchInterval: (query) => (query.state.data ? DELIVERY_REFETCH_MS : false),
  });
}
