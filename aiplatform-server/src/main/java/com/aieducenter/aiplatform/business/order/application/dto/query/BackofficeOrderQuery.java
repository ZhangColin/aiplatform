package com.aieducenter.aiplatform.business.order.application.dto.query;

import java.time.LocalDateTime;
import java.util.List;

import com.cartisan.data.jpa.specification.Condition;
import com.cartisan.data.jpa.specification.ConditionType;

import com.aieducenter.aiplatform.business.order.domain.enums.OrderStatus;

/**
 * 后台订单清单检索条件（#156 四维）：字段即过滤维度，经
 * {@code ConditionSpecifications.fromAnnotation} 反射拼装为 JPA Specification
 * 在数据库侧过滤（不入内存全量）。null 字段自动跳过＝该维不参与过滤。externalId/
 * 订单号两个入参形态不在此——服务端先换算成 ownerAccountId/orderId 再构造本
 * 条件，identity 换算与 TSID 解析不进检索结构。
 *
 * @param status         状态多选（IN；null 或空清单＝全量）
 * @param createdFrom    创建时间下界（createdAt ≥，含端点）
 * @param createdTo      创建时间上界（createdAt ≤，含端点）
 * @param ownerAccountId 下单账号（externalId 换算所得）
 * @param orderId        订单号精确（订单 TSID id）
 */
public record BackofficeOrderQuery(
        @Condition(type = ConditionType.IN) List<OrderStatus> status,
        @Condition(propName = "createdAt", type = ConditionType.GREATER_EQUAL) LocalDateTime createdFrom,
        @Condition(propName = "createdAt", type = ConditionType.LESS_EQUAL) LocalDateTime createdTo,
        @Condition(type = ConditionType.EQUAL) Long ownerAccountId,
        @Condition(propName = "id", type = ConditionType.EQUAL) Long orderId
) {

    public BackofficeOrderQuery {
        // 空状态清单构造期即归一为 null（空选＝全量）——注解机制不跳过空集合，
        // 归一内聚在此，调用方无从做错
        status = status == null || status.isEmpty() ? null : status;
    }
}
