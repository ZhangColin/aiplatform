package com.aieducenter.aiplatform.business.order.endpoints.controller;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.CodeMessage;

import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 订单 REST 寻址解析：路径段（TSID 十进制字符串）→ Long。非数值/非正数即
 * 不存在的标识，语义上同 404（与 project 侧 parseId 口径一致）；订单寻址与
 * 项目寻址各自抛本上下文的不存在错误码。
 */
final class OrderIds {

    private OrderIds() {
    }

    /** 订单寻址（/api/orders/{id}）：畸形标识 → 404 ORD_001。 */
    static Long parseOrder(String orderId) {
        return parse(orderId, OrderMessage.ORDER_NOT_FOUND);
    }

    /** 项目寻址（/api/projects/{id}/orders）：畸形标识 → 404 PRJ_001（同项目端点口径）。 */
    static Long parseProject(String projectId) {
        return parse(projectId, ProjectMessage.PROJECT_NOT_FOUND);
    }

    private static Long parse(String raw, CodeMessage notFound) {
        try {
            long parsed = Long.parseLong(raw);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        throw new ApplicationException(notFound);
    }
}
