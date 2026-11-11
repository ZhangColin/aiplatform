package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.util.List;

/**
 * 标注队列检索结果（#464 后台「数据标注」面）：SQL 侧分页——items 当页行＋total
 * 全量计数（照 {@link MaterialSearchResult} 先例：沉淀量随采集单调增长，检索在
 * 数据库侧 LIMIT/OFFSET，不在内存做全量切片）。
 *
 * @param items 当页草稿行（自荐时间倒序、id 倒序稳定）
 * @param total 过滤命中的全量行数（分页元数据）
 */
public record SedimentDraftQueueResult(List<SedimentDraftRecord> items, long total) {
}
