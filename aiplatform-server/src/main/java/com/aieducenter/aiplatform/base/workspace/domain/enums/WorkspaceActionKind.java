package com.aieducenter.aiplatform.base.workspace.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 后台沙箱动作（#174，ADR-0016 资源面的管理干预）：管理员对单台沙箱的四动作，
 * append-only 落 {@code wsp_workspace_actions} 供审计。run 在途守卫口径见
 * {@code WorkspaceActionAppService}（唤醒不受限）。
 */
public enum WorkspaceActionKind implements BaseEnum<WorkspaceActionKind> {

    WAKE(1, "唤醒"),
    HIBERNATE(2, "强制休眠"),
    REBUILD(3, "强制重建"),
    SEAL(4, "封存");

    private final Integer code;
    private final String name;

    WorkspaceActionKind(Integer code, String name) {
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
     * JPA Converter（autoApply，实体字段零注解）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<WorkspaceActionKind> {
        public JpaConverter() {
            super(WorkspaceActionKind.class);
        }
    }
}
