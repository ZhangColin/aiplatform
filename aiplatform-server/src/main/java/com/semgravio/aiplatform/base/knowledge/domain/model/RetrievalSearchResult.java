package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.util.List;

/**
 * 命中留痕读面查询结果（#639）：当页行＋全量计数（同
 * {@link MaterialSearchResult} 形状）。
 */
public record RetrievalSearchResult(List<RetrievalRecord> items, long total) {
}
