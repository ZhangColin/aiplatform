package com.aieducenter.aiplatform.base.metering.application.dto.query;

import com.cartisan.data.jpa.specification.Condition;
import com.cartisan.data.jpa.specification.ConditionType;

/**
 * 后台单价行清单检索条件（#160）：字段即过滤维度，经
 * {@code ConditionSpecifications.fromAnnotation} 反射拼装为 JPA Specification
 * 在数据库侧过滤。provider/model 均为匹配键成分＝精确等值（标识符不做模糊），
 * null 字段自动跳过＝该维不参与过滤（缺省＝全量行，含历史行）。
 *
 * @param provider 模型提供方（精确）
 * @param model    模型（精确）
 */
public record BackofficePriceEntryQuery(
        @Condition(type = ConditionType.EQUAL) String provider,
        @Condition(type = ConditionType.EQUAL) String model
) {

    public BackofficePriceEntryQuery {
        // 空白构造期即归一为 null（空串不当过滤值，缺省＝全量）——归一内聚在此，
        // 调用方无从做错
        provider = blankToNull(provider);
        model = blankToNull(model);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
