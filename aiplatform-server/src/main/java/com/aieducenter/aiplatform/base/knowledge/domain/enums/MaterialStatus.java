package com.aieducenter.aiplatform.base.knowledge.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 知识素材状态（#153）：停用⇄启用可逆开关——停用素材的全部块退出检索命中，
 * 再启用恢复参与。与单价表单向关行有意不同：知识命中无「历史不漂移」约束。
 */
public enum MaterialStatus implements BaseEnum<MaterialStatus> {

    ENABLED(1, "启用"),

    DISABLED(2, "停用");

    private final Integer code;
    private final String name;

    MaterialStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<MaterialStatus> {
        public JpaConverter() {
            super(MaterialStatus.class);
        }
    }
}
