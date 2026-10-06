package com.aieducenter.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 设计轨道件状态（#289 设计轨道表）：设计物（PRD 清单章条目）在设计清单下的
 * 执行事实——状态是「最近一次尝试的结局」（重派后再收口即覆写回已收口），不是
 * 「曾经收口过」的历史账。推进序跟清单走（PRD 演进即整组重产），失败件
 * 重派重做。
 */
public enum DesignItemStatus implements BaseEnum<DesignItemStatus> {

    /** 待跑（清单落库的初始态；清单重产整组重置回此态——已收口条目对照保留）。 */
    PENDING(1, "待跑"),

    /** 已收口（本场 design/ 落稿核验过的真收口；runId 锚用户面 run 身份）。 */
    CLOSED(2, "已收口"),

    /** 失败（尝试环超限转终态；重派的重做对象）。 */
    FAILED(3, "失败");

    private final Integer code;
    private final String name;

    DesignItemStatus(Integer code, String name) {
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
    public static class JpaConverter extends BaseEnumConverter<DesignItemStatus> {
        public JpaConverter() {
            super(DesignItemStatus.class);
        }
    }
}
