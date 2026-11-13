package com.semgravio.aiplatform.base.metering.application.dto.query;

import com.cartisan.data.jpa.specification.Condition;
import com.cartisan.data.jpa.specification.ConditionType;

/**
 * 后台时段规则行清单检索条件（#497，照 {@code BackofficePriceEntryQuery} 形制）：
 * 字段即过滤维度，经 {@code ConditionSpecifications.fromAnnotation} 反射拼装为
 * JPA Specification 在数据库侧过滤。provider 为匹配键＝精确等值（标识符不做
 * 模糊），null 自动跳过＝全量行（含历史版本）。
 *
 * @param provider 模型提供方（精确）
 */
public record BackofficeTimeBandRuleQuery(
        @Condition(type = ConditionType.EQUAL) String provider
) {

    public BackofficeTimeBandRuleQuery {
        // 空白构造期即归一为 null（空串不当过滤值，缺省＝全量）——归一内聚在此，
        // 调用方无从做错
        provider = blankToNull(provider);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
