package com.aieducenter.aiplatform.base.skills.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 技能状态（#246）：停用⇄启用可逆开关——停用技能退出装配合成（T3 指派面的
 * 生效语义），再启用恢复参与；与素材状态（MaterialStatus）同款可逆治理，无
 * 「历史不漂移」约束。内置技能非库行、无状态迁移（恒启用）。
 */
public enum SkillStatus implements BaseEnum<SkillStatus> {

    ENABLED(1, "启用"),

    DISABLED(2, "停用");

    private final Integer code;
    private final String name;

    SkillStatus(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * JPA Converter（框架自动应用，实体字段无需 @Convert）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<SkillStatus> {
        public JpaConverter() {
            super(SkillStatus.class);
        }
    }
}
