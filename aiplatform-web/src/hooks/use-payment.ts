import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";

import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import { ApiError, errorText } from "@/lib/api/api-error";
import { normalizePaymentPlan, type PaymentPlanResponse } from "@/lib/orders/payment";
import { ORDER_STATUS } from "@/lib/orders/lock";

/**
 * 付款数据层（#337 付款计划与凭证核验）：订单名下活跃付款计划查询（无计划
 * 404 归一为 null＝面板缺席不渲染）＋转账凭证上传动作。节点态变化无推送
 * （后台人工核验）——有未到账节点挂轮询（同合同卡口径），全部到账即停。
 */

/** 付款节点推进等待期的轮询间隔（后台人工核验，状态变化实时可见）。 */
const PAYMENT_REFETCH_MS = 10_000;

/** 无付款计划业务码（ORD_036＝5036，域码 5×1000＋36）。 */
const NO_PLAN_CODE = 5036;

/**
 * 订单名下活跃付款计划（付款面板消费）：无计划（404 ORD_036——尚无合同即无
 * 计划）归一为 null；有未到账节点挂轮询（凭证核验后台人工推进），全部到账
 * 即停（呈现定格）。订单已终结（已归档/已取消）也停——终态不再有支付活动。
 */
export function usePaymentPlan(orderId: string | null | undefined, orderStatus?: number) {
  return useQuery({
    queryKey: queryKeys.orders.paymentPlan(orderId ?? ""),
    queryFn: async ({ signal }) => {
      try {
        return normalizePaymentPlan(
          await api.get<PaymentPlanResponse>(`/orders/${orderId}/payment-plan`, { signal }),
        );
      } catch (error) {
        if (error instanceof ApiError && error.code === NO_PLAN_CODE) {
          return null;
        }
        throw error;
      }
    },
    enabled: orderId != null && orderId !== "",
    refetchInterval: (query) => {
      const plan = query.state.data;
      if (!plan || plan.nodes.length === 0) {
        return false; // 无计划/待录入不轮询（变化经合同域与订单详情面感知）
      }
      const settled = orderStatus === ORDER_STATUS.archived || orderStatus === ORDER_STATUS.cancelled;
      if (settled) {
        return false;
      }
      const allReceived = plan.nodes.every((node) => node.status === 5);
      return allReceived ? false : PAYMENT_REFETCH_MS;
    },
  });
}

/**
 * 上传转账凭证（凭证核验路径第一跳）：multipart 单文件 POST——节点翻待核验、
 * 凭证追加保留。前端预检（四格式/10MB）在面板侧，此处只收 File（省注定 400
 * 的上传）。成功失效订单域（付款面板与订单卡随重拉刷新）。
 */
export function useUploadVoucher(orderId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ nodeId, file }: { nodeId: string; file: File }) => {
      const form = new FormData();
      form.append("file", file);
      return api.post<PaymentPlanResponse>(
        `/orders/${orderId}/payment-nodes/${nodeId}/vouchers`,
        form,
      );
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all });
    },
    onError: (error) => {
      toast.error(errorText(error, "凭证上传失败，请稍后重试"));
    },
  });
}
