package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 生产承接状态（#338 两态，spec #320 实施序④）：待安排生产 → 生产中 单向推进，
 * 全部后台人工推进（每次迁移 append-only 留痕，操作人/时间/依据必填）。无终态
 * 概念——生产承接是交付过程的状态承接页（平台内生产机制是备案的未来演化，
 * v1 只承载状态与人工措辞的进展），「挂项目、只建一次」因此用全生命周期一行
 * （project_id 普通唯一索引，非合同域的部分唯一索引——那里终态放行重建有语义、
 * 这里没有）。
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）。</p>
 */
public enum ProductionHandoffStatus implements BaseEnum<ProductionHandoffStatus> {

    SCHEDULED(1, "待安排生产"),

    IN_PRODUCTION(2, "生产中");

    private final Integer code;
    private final String name;

    ProductionHandoffStatus(Integer code, String name) {
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
     * JPA Converter（框架自动应用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<ProductionHandoffStatus> {
        public JpaConverter() {
            super(ProductionHandoffStatus.class);
        }
    }
}
