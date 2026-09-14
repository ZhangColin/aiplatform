package com.aieducenter.aiplatform.base.workspace.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 工作区期望态（ADR-0016）：DB 只记意图（运行/休眠/封存），不镜像 docker 实态——
 * 漂移以实态探查为准收敛（#168 教训：DB 记 ready、容器实死无人知）。休眠器（#171）
 * 与封存（#172）是变更方；唤醒编排只看实态，不依赖本状态决策。
 */
public enum DesiredState implements BaseEnum<DesiredState> {

    RUNNING(1, "运行"),
    HIBERNATED(2, "休眠"),
    SEALED(3, "封存");

    private final Integer code;
    private final String name;

    DesiredState(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<DesiredState> {
        public JpaConverter() {
            super(DesiredState.class);
        }
    }
}
