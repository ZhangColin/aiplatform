package com.aieducenter.aiplatform.base.skills.domain.error;

import com.cartisan.core.exception.CodeMessage;

/**
 * base.skills 错误定义（前缀 SKL_，ADR-0001 注册表登记）。
 */
public enum SkillMessage implements CodeMessage {

    SKILL_NOT_FOUND(404, "SKL_001", "技能不存在"),

    // ========== 安装（#248 快照安装，失败整体不入库——fail-fast） ==========
    SKILL_INSTALL_URL_REQUIRED(400, "SKL_002", "安装仓库地址不能为空"),

    SKILL_SOURCE_ALREADY_INSTALLED(409, "SKL_003", "该技能仓库已安装过，不能重复安装（同源去重，更新走显式更新）"),

    SKILL_REPOSITORY_CLONE_FAILED(502, "SKL_004", "技能仓库克隆失败"),

    SKILL_NO_SKILLS_PARSED(400, "SKL_005", "仓库未解析到任何技能（无 SKILL.md 或全部被排除）"),

    SKILL_MD_INVALID(400, "SKL_006", "仓库内 SKILL.md 不合格（须含 name、description 与正文）"),

    SKILL_NAME_CONFLICT_IN_PACKAGE(400, "SKL_007", "同一仓库内存在重名技能"),

    // ========== 卸载守卫（#248 定码；#249 指派表落地接真检查） ==========
    SKILL_ASSIGNED(409, "SKL_008", "技能有指派在身，先解绑再卸载"),

    // ========== 操作者（#248 全程留痕，知识治理同款无落空通道） ==========
    SKILL_OPERATOR_REQUIRED(400, "SKL_009", "操作者不能为空"),

    // ========== 槽位指派（#249） ==========
    SKILL_SLOT_NOT_FOUND(404, "SKL_010", "职能槽位不存在"),

    SKILL_BUILTIN_NOT_ASSIGNABLE(400, "SKL_011", "内置技能不可指派（内置随平台发版，装配合成按配置挂载，无需指派）");

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
