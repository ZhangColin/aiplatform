package com.aieducenter.aiplatform.base.knowledge.domain.model;

import java.util.List;

/**
 * 素材清单检索结果（#166）：SQL 侧分页——items 当页行＋total 全量计数。素材量
 * 随成交沉淀单调增长，检索在数据库侧 LIMIT/OFFSET（照成本读面聚合的存储侧
 * 收口口径），不在内存做全量切片。
 *
 * @param items 当页登记行（沉淀时间倒序、id 倒序稳定）
 * @param total 过滤命中的全量行数（分页元数据）
 */
public record MaterialSearchResult(List<MaterialRecord> items, long total) {
}
