package com.semgravio.aiplatform.base.workspace.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 一键回收范围（#490，ADR-0045 域词「一键回收」两级）：项目级（删后四面
 * 对账——注册表行/项目行/容器/卷）与工作区级（单台三清，项目其余部分不动
 * ——在册失联行的人工裁决出口，#488）。落 {@code wsp_reclaim_actions.scope}
 * （INT code：1=项目级 2=工作区级）。
 */
public enum ReclaimScope implements BaseEnum<ReclaimScope> {

    /** 项目级：删除＋清残＋四面报告（含 prj_* 级联与在飞 run 级联停止）。 */
    PROJECT(1, "项目级"),

    /** 工作区级：单台工作区三清，项目行与其余资产不动。 */
    WORKSPACE(2, "工作区级");

    private final Integer code;
    private final String name;

    ReclaimScope(Integer code, String name) {
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
     * JPA Converter（autoApply，实体字段零注解——code 列与 V65 迁移注释同基）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<ReclaimScope> {
        public JpaConverter() {
            super(ReclaimScope.class);
        }
    }
}
