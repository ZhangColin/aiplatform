package com.semgravio.aiplatform.business.billing.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * Billing Context 错误码（前缀 {@code BILL_}，#531 注册表位）。
 */
public enum BillingMessage implements CodeMessage {

    POOL_TARGET_INVALID(400, "BILL_001", "入账目标非法（账户与项目恰选其一）"),

    TOPUP_AMOUNT_INVALID(400, "BILL_002", "入账金额无效（须为正整数，单位分）"),

    ADJUSTMENT_AMOUNT_INVALID(400, "BILL_003", "余额调整金额无效（须为非零整数，单位分）"),

    DEDUCTION_AMOUNT_INVALID(400, "BILL_004", "扣减金额无效（须为正整数，单位分）"),

    ENTRY_FIELDS_INCOMPLETE(400, "BILL_005", "流水行字段不完整"),

    ENTRY_NOTE_TOO_LONG(400, "BILL_006", "流水备注超长（至多 500 字）"),

    PROJECT_BILLING_FIELDS_INCOMPLETE(400, "BILL_007", "项目计费态字段不完整"),

    SOURCE_ALREADY_SELECTED(409, "BILL_008", "扣减源已选定，不可更改（如需调整请联系平台）"),

    SOURCE_ACCOUNT_REQUIRED(400, "BILL_009", "选择账户余额为扣减源须先登录绑定账号"),

    FLIP_SOURCE_REQUIRED(409, "BILL_010", "开始付费前须先选定扣减源"),

    FLIP_BALANCE_NOT_POSITIVE(409, "BILL_011", "扣减源余额不足（请先充值，到账后继续）"),

    FLIP_ALREADY_PAID(409, "BILL_012", "项目已是付费状态（付费状态不可逆）"),

    SETTLEMENT_SEGMENT_INVALID(400, "BILL_013", "结算段参数非法（段型/段址/用量寻址必填）"),

    FIXED_ITEM_INVALID(400, "BILL_014", "固定单价项参数非法（提供方/型号/件数必填，件数为正）"),

    PARAM_KEY_UNKNOWN(404, "BILL_015", "计费参数不存在"),

    PARAM_VALUE_INVALID(400, "BILL_016", "计费参数值无效（须为正数）"),

    INTERCEPT_NOT_PENDING(409, "BILL_017", "没有待确认的拦截卡（输入可能已被更新的拦截取代）"),

    INTERCEPT_FIELDS_INCOMPLETE(400, "BILL_018", "拦截留存字段不完整"),

    ACCESS_CODE_INVALID(400, "BILL_019", "充值准入码不正确（无码用户请联系平台索取）"),

    TOPUP_TARGET_NOT_SOURCE_POOL(409, "BILL_020", "只能充值当前扣减源池（向非源池充值无法用于本项目扣减）");

    private final int httpStatus;
    private final String code;
    private final String message;

    BillingMessage(int httpStatus, String code, String message) {
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
