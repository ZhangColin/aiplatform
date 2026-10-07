package com.aieducenter.aiplatform.business.order.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * Order Context 错误码（前缀 {@code ORD_}，#4 决议注册表位）。
 */
public enum OrderMessage implements CodeMessage {

    ORDER_NOT_FOUND(404, "ORD_001", "订单不存在"),

    ORDER_FIELDS_INCOMPLETE(400, "ORD_002", "订单字段不完整"),

    ORDER_ALREADY_ACTIVE(409, "ORD_003", "该项目已有未终结订单（取消或完成后再下单）"),

    ORDER_PROJECT_ARCHIVED(409, "ORD_004", "项目已归档，无法下单（新需求请新建项目）"),

    ORDER_CANCEL_NOT_ALLOWED(409, "ORD_005", "订单已支付或已终结，无法取消"),

    ORDER_FROZEN(409, "ORD_006", "订单处理中——如需继续修改，请取消订单"),

    ORDER_QUOTE_NOT_ALLOWED(409, "ORD_007", "订单已支付或已终结，无法报价或改价"),

    ORDER_QUOTE_AMOUNT_INVALID(400, "ORD_008", "报价金额无效（须为正整数，单位分）"),

    ORDER_QUOTE_NOTE_TOO_LONG(400, "ORD_009", "报价备注超长（至多 1000 字）"),

    // ORD_010 曾是「无效的订单过滤参数」，随过滤参数绑定失败骑框架（#199）注销，码位不复用

    ORDER_PAY_NOT_ALLOWED(409, "ORD_011", "订单非待支付状态，无法支付"),

    ORDER_ARCHIVE_NOT_ALLOWED(409, "ORD_012", "订单非已支付状态，无法归档"),

    ORDER_CANCEL_REASON_REQUIRED(400, "ORD_013", "取消原因必填（运营取消须填写原因）"),

    ORDER_CANCEL_REASON_TOO_LONG(400, "ORD_014", "取消原因超长（至多 1000 字）"),

    /** 下载支付门守卫（#287，ADR-0027「平台上随便体验、带走才付费」）：项目名下从未出现已支付/已归档订单——门只盖用户面下载（单文件＋源码包＋设计资产包），点看/预览自由、后台运营面不受门。 */
    ORDER_DOWNLOAD_NOT_PAID(402, "ORD_015", "还未支付，暂不能下载：平台上可随意浏览和预览，带走文件需先完成订单支付"),

    /** 设计资产包取件的类型面（#297）：系统单交付物不含设计资产包（源码包实时取）——订单寻址成功而交付物类型不符，如实 404 不假装有包。 */
    ORDER_DESIGN_PACKAGE_NOT_DELIVERABLE(404, "ORD_016", "该订单的交付物不含设计资产包（系统单交付物是源码包）");

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
