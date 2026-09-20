package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 生成轨道片状态（#220 生成轨道表）：片（阶段 0 / 切片）在当前计划下的执行事实
 * ——状态是「最近一次尝试的结局」（重派后再收口即覆写回已收口），不是「曾经
 * 收口过」的历史账。断点推导以已收口为准（最深收口片），失败片续跑重做。
 */
public enum GenerationSegmentStatus implements BaseEnum<GenerationSegmentStatus> {

    /** 待跑（计划落库的初始态；重产计划整组重置回此态）。 */
    PENDING(1, "待跑"),

    /** 已收口（8081 探活过的真收口；runId 锚用户面 run 身份）。 */
    CLOSED(2, "已收口"),

    /** 失败（尝试环超限转终态；「继续生成」续跑的重做对象）。 */
    FAILED(3, "失败");

    private final Integer code;
    private final String name;

    GenerationSegmentStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<GenerationSegmentStatus> {
        public JpaConverter() {
            super(GenerationSegmentStatus.class);
        }
    }
}
