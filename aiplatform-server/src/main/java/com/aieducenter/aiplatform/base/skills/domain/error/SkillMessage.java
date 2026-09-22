package com.aieducenter.aiplatform.base.skills.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.skills 错误定义（前缀 SKL_，ADR-0001 注册表登记）。
 */
public enum SkillMessage implements CodeMessage {

    SKILL_NOT_FOUND(404, "SKL_001", "技能不存在");

    private final int httpStatus;
    private final String code;
    private final String message;

    SkillMessage(int httpStatus, String code, String message) {
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
