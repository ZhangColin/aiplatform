package com.aieducenter.aiplatform.base.metering.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.metering 错误定义（前缀 METER_，ADR-0001 注册表预留位）。
 */
public enum MeteringMessage implements CodeMessage {

    TOKEN_USAGE_NEGATIVE(400, "METER_001", "token 用量不能为负数"),

    USAGE_EVENT_FIELDS_INCOMPLETE(400, "METER_002", "用量事件字段不完整"),

    USAGE_SUBJECT_REQUIRED(400, "METER_003", "用量查询必须指定 subject"),

    PRICE_ENTRY_FIELDS_INCOMPLETE(400, "METER_004", "单价行字段不完整"),

    PRICE_ENTRY_CLOSE_INVALID(400, "METER_005", "关行时点非法（空或早于生效起点）"),

    PRICE_ENTRY_NOT_FOUND(404, "METER_006", "单价行不存在"),

    PRICE_ENTRY_NOT_CURRENT(409, "METER_007", "单价行非当前行（已关行不可改价或停用）"),

    PRICE_ENTRY_INTERVAL_OVERLAPPED(409, "METER_008", "同匹配键生效区间重叠（跨区间或同起点）"),

    PRICE_ENTRY_FILTER_UNKNOWN(400, "METER_009", "无效的单价行过滤参数"),

    PRICE_ENTRY_CURRENCY_UNKNOWN(400, "METER_010", "单价币种非 ISO 4217 代码"),

    COST_WINDOW_INVALID(400, "METER_011", "无效的成本查询时间窗参数");

    private final int httpStatus;
    private final String code;
    private final String message;

    MeteringMessage(int httpStatus, String code, String message) {
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
