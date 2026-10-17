package com.aieducenter.aiplatform.business.order.application.dto.query;

import java.time.LocalDateTime;
import java.util.List;

import com.cartisan.data.jpa.specification.Condition;
import com.cartisan.data.jpa.specification.ConditionType;

import com.aieducenter.aiplatform.business.order.domain.enums.ContractStatus;

/**
 * 后台合同清单检索条件（#336，仿 {@link BackofficeOrderQuery} 形制）：字段即
 * 过滤维度，经 {@code ConditionSpecifications.fromAnnotation} 反射拼装为 JPA
 * Specification 在数据库侧过滤。null 字段自动跳过＝该维不参与过滤；合同号
 * 精确串不进检索结构以外的换算（无 TSID 解析，直接等值）。
 *
 * @param status      状态多选（IN；null 或空清单＝全量）
 * @param createdFrom 创建时间下界（createdAt ≥，含端点）
 * @param createdTo   创建时间上界（createdAt ≤，含端点）
 * @param orderId     所属订单精确（订单 TSID id）
 * @param contractNo  合同号精确（SG- 串）
 */
public record BackofficeContractQuery(
        @Condition(type = ConditionType.IN) List<ContractStatus> status,
        @Condition(propName = "createdAt", type = ConditionType.GREATER_EQUAL) LocalDateTime createdFrom,
        @Condition(propName = "createdAt", type = ConditionType.LESS_EQUAL) LocalDateTime createdTo,
        @Condition(type = ConditionType.EQUAL) Long orderId,
        @Condition(type = ConditionType.EQUAL) String contractNo
) {

    public BackofficeContractQuery {
        // 空状态清单构造期即归一为 null（空选＝全量）——注解机制不跳过空集合，
        // 归一内聚在此，调用方无从做错
        status = status == null || status.isEmpty() ? null : status;
        contractNo = contractNo != null && contractNo.isBlank() ? null : contractNo.trim();
    }
}
