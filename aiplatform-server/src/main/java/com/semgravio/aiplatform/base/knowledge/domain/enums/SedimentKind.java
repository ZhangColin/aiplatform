package com.semgravio.aiplatform.base.knowledge.domain.enums;

import java.util.Locale;
import java.util.Optional;

import com.cartisan.core.domain.BaseEnum;

/**
 * 沉淀物类别（#460 数据飞轮采集层，照技能链 SkillSlot 键形先例）：一期三类——
 * 需求案例 CASE（某类用户/场景的真实需求形态）、功能模块 MODULE（可复用的功能
 * 形态）、业务规则 RULE（某类业务公认的规则口径）。键形稳定串落库（VARCHAR），
 * 大小写不敏感解析归一到大写键。库面无 CHECK 限死——「kind 开放＋载荷 JSONB 靠
 * 自己生长」（#451 决策正本）：新类别生长只需写入校验放行，不动表结构。code 是
 * 框架约定码（BaseEnum 自动转换面；落库与工具入参均用字符串键，code 不经任何面）。
 */
public enum SedimentKind implements BaseEnum<SedimentKind> {

    /** 需求案例：某类用户/场景的真实需求形态（访谈中反复出现的同构需求）。 */
    CASE(1),

    /** 功能模块：可复用的功能形态（多项目同构出现、值得模式化的功能块）。 */
    MODULE(2),

    /** 业务规则：某类业务公认的规则口径（领域常识、行业惯例、约束口径）。 */
    RULE(3);

    private final Integer code;

    SedimentKind(Integer code) {
        this.code = code;
    }

    /** 框架约定码（BaseEnum 自动转换面；不落库不经工具入参——用字符串键）。 */
    @Override
    public Integer getCode() {
        return code;
    }

    /** 展示名（BaseEnum 约定面；读取面回显用字符串键）。 */
    @Override
    public String getName() {
        return name();
    }

    /** 稳定键（落库值，即枚举名大写）。 */
    public String key() {
        return name();
    }

    /**
     * 按键解析（大小写不敏感，trim）：空键/未知键返回空，由调用方定拒因语义
     * （写入校验拒——回执可读可修正重提）。
     */
    public static Optional<SedimentKind> byKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SedimentKind.valueOf(key.trim().toUpperCase(Locale.ROOT)));
        }
        catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
