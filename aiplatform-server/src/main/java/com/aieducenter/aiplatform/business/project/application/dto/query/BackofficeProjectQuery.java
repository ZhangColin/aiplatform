package com.aieducenter.aiplatform.business.project.application.dto.query;

import java.time.LocalDateTime;

import com.cartisan.data.jpa.specification.Condition;
import com.cartisan.data.jpa.specification.ConditionType;

/**
 * 后台项目清单检索条件（#159）：字段即过滤维度，经
 * {@code ConditionSpecifications.fromAnnotation} 反射拼装为 JPA Specification
 * 在数据库侧过滤（用户面 findAll 内存过滤不适用于后台新增维度，检索走数据库
 * 查询路径）。null 字段自动跳过＝该维不参与过滤。externalId/项目 id 两个入参
 * 形态不在此——服务端先换算成 ownerAccountId/projectId 再构造本条件，identity
 * 换算与 TSID 解析不进检索结构（同 BackofficeOrderQuery 收口口径）。
 *
 * <p>状态三档单选亦不在此：进行中/已归档是 archivedAt 的派生谓词
 * （IS NULL / IS NOT NULL），注解机制无对应条件类型，由服务端手工拼合。</p>
 *
 * @param createdFrom    创建时间下界（createdAt ≥，含端点）
 * @param createdTo      创建时间上界（createdAt ≤，含端点）
 * @param ownerAccountId 归属账号（externalId 换算所得）
 * @param projectId      项目 id 精确（项目 TSID id）
 */
public record BackofficeProjectQuery(
        @Condition(propName = "createdAt", type = ConditionType.GREATER_EQUAL) LocalDateTime createdFrom,
        @Condition(propName = "createdAt", type = ConditionType.LESS_EQUAL) LocalDateTime createdTo,
        @Condition(type = ConditionType.EQUAL) Long ownerAccountId,
        @Condition(propName = "id", type = ConditionType.EQUAL) Long projectId
) {
}
