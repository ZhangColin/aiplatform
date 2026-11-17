package com.semgravio.aiplatform.business.project.domain.enums;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;
import jakarta.persistence.Converter;

/**
 * 项目岔路口状态位（#542 访谈②，ADR-0048/0049）：试用态访谈<b>自然收口</b>
 * （判定明确含提前明确——非快道）时置 {@link #PENDING}，岔路口三选卡
 * （继续讨论·由我的账户付费／继续讨论·由本项目付费／立即生成）的在场事实。
 *
 * <p>单值即全集：卡的<b>呈现</b>是派生读（试用态 ∧ 本位 PENDING ∧ 首建未派发
 * ∧ 未归档——{@code GET /build-gate} 单读面），点火后不回清本位（隐藏由
 * {@code generation_requested_at} 派生承担——失败重试走「继续生成」、卡不复现
 * 不复活确认门）；快道（收敛令/零必答短路/付费「直接开始做」）直生成<b>不经
 * 本位</b>（不出卡）。付费态不走岔路口（确认门读面同源派生），状态位留值无害
 * ——呈现条件吃付费状态单读点。</p>
 */
public enum ForkState implements BaseEnum<ForkState> {

    /** 岔路口卡在场（自然收口置位，持久可回——未选择前关页回来卡仍在）。 */
    PENDING(1, "岔路口在场");

    private final Integer code;
    private final String name;

    ForkState(Integer code, String name) {
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
     * JPA Converter（框架自动应用，实体字段无需 @Convert）——落库 Integer code，
     * 同聚合 {@link ProjectType}/{@link ProjectEndpointType} 先例。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<ForkState> {
        public JpaConverter() {
            super(ForkState.class);
        }
    }
}
