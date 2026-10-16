package com.aieducenter.aiplatform.business.identity.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * business.identity 错误定义（前缀 IDN_，ADR-0001 注册表）。
 *
 * <p>认证流程错误（换 token / 验签失败）不直接以 REST 错误暴露——callback 统一
 * 302 回 {@code /?error=exchange_failed}，具体原因（本枚举 code）只进日志（照
 * identity demo 姿态，不漏内部细节给前端）。例外两处 REST 直出：后台查询读口
 * （IDN_004，机机签名面契约）与验证码登录端点（IDN_005–IDN_008，M01 弹窗要给
 * 用户可读的原因，均为不含内部细节的输入侧事实）。</p>
 */
public enum IdentityMessage implements CodeMessage {

    ACCOUNT_FIELDS_INCOMPLETE(400, "IDN_001", "账号字段不完整"),

    ID_TOKEN_REJECTED(401, "IDN_002", "id_token 校验未通过（验签 / iss / aud / exp / nonce）"),

    TOKEN_EXCHANGE_FAILED(502, "IDN_003", "identity 令牌端点调用失败"),

    ACCOUNT_NOT_FOUND(404, "IDN_004", "账号不存在"),

    /** #327 验证码登录：目标不是可收码形态（邮箱/手机号） */
    VERIFICATION_TARGET_INVALID(400, "IDN_005", "请输入有效的邮箱或手机号"),

    /** #327 验证码登录：通道机制位在但未开放（上线先邮箱，开关走配置） */
    VERIFICATION_CHANNEL_DISABLED(403, "IDN_006", "该验证通道暂未开放"),

    /** #327 验证码登录：发码频控（同目标冷却窗口内重复发码） */
    VERIFICATION_SEND_TOO_FREQUENT(429, "IDN_007", "发送太频繁，请稍后再试"),

    /** #327 验证码登录：验证码错误或已过期（含超次试错作废、无在途码） */
    VERIFICATION_CODE_MISMATCH(400, "IDN_008", "验证码错误或已过期"),

    /** #327 绑定聚合：字段不完整（编程性守卫，不经 REST 直出） */
    BINDING_FIELDS_INCOMPLETE(400, "IDN_009", "账号绑定字段不完整");

    private final int httpStatus;
    private final String code;
    private final String message;

    IdentityMessage(int httpStatus, String code, String message) {
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
