import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { ApiError, errorText } from "@/lib/api/api-error";
import {
  CONTRACT_TERMINAL_CODES,
  normalizeContract,
  type ContractResponse,
} from "@/lib/orders/contract";

/**
 * 合同数据层（#336 合同域六态）：订单名下最近合同查询（404 ORD_020 归一为
 * null＝无合同缺席态，不渲染卡片）＋「确认范围并准备合同」动作（范围快照冻结
 * ＋合同创建）。后台人工推进无推送——非终态挂轮询（同订单详情报价等待期口径），
 * 终态即停。
 */

/** 合同推进等待期的轮询间隔（后台人工推进留痕，状态变化实时可见）。 */
const CONTRACT_REFETCH_MS = 10_000;

/** 无合同业务码（ORD_020＝5020，域码 5×1000＋20）。 */
const NO_CONTRACT_CODE = 5020;

/**
 * 订单名下最近合同（合同预览卡消费）：无合同（404 ORD_020）归一为 null——
 * 「还没有合同」是一等状态不是错误；非终态挂轮询（六态后台人工推进），终态
 * 即停（终态合同永不删除、呈现定格）。
 */
export function useContract(orderId: string | null | undefined) {
  return useQuery({
    queryKey: queryKeys.orders.contract(orderId ?? ""),
    queryFn: async ({ signal }) => {
      try {
        return normalizeContract(
          await api.get<ContractResponse>(`/orders/${orderId}/contract`, { signal }),
        );
      } catch (error) {
        if (error instanceof ApiError && error.code === NO_CONTRACT_CODE) {
          return null;
        }
        throw error;
      }
    },
    enabled: orderId != null && orderId !== "",
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status != null && !CONTRACT_TERMINAL_CODES.includes(status)
        ? CONTRACT_REFETCH_MS
        : false;
    },
  });
}

/**
 * 确认范围并准备合同（三子步骤「核对范围与报价」的确认动作）：确认即冻结
 * 范围快照＋创建合同（准备中起步）。前置门拦截（ORD_025 尚无报价/ORD_026
 * 范围未录/ORD_024 已有活跃合同）直出后端中文 message toast；成功失效订单与
 * 项目域（订单详情与合同卡随重拉进场）。
 */
export function useConfirmScope() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: string) =>
      api.post<ContractResponse>(`/orders/${orderId}/confirm-scope`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "确认范围失败，请稍后重试"));
    },
  });
}
