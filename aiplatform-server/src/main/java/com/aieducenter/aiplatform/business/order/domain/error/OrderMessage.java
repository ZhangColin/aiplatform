package com.aieducenter.aiplatform.business.order.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * Order Context 错误码（前缀 {@code ORD_}，#4 决议注册表位）。
 */
public enum OrderMessage implements CodeMessage {

    ORDER_NOT_FOUND(404, "ORD_001", "订单不存在"),

    ORDER_FIELDS_INCOMPLETE(400, "ORD_002", "订单字段不完整"),

    ORDER_ALREADY_ACTIVE(409, "ORD_003", "该项目已有未终结订单（取消或完成后再下单）"),

    ORDER_PROJECT_ARCHIVED(409, "ORD_004", "项目已归档，无法下单（新需求请新建项目）");

    private final int httpStatus;
    private final String code;
    private final String message;

    OrderMessage(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
