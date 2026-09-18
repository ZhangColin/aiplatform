package com.aieducenter.aiplatform.business.project.domain.port;

import java.util.List;

/**
 * 搜索供数结果（#215 调研闭环的端口契约）：成功带标题/URL/摘要列表；失败带如实
 * 理由（非 200、坏响应、连接异常、缺 key 等）——不抛错不炸，理由由调用方（搜索
 * 工具）如实回给智能体。与 {@link WebSearchProvider} 同源：查询进 → 结果/理由出，
 * 好测试只测这一外部行为，不测供数方内部解析细节。
 */
public sealed interface SearchResult {

    /**
     * 搜索成功：{@link #hits} 为结果列表（标题 + URL + 摘要，供数方无关的最小
     * 契约面——换供数方不换此形状）。
     */
    record Results(List<Hit> hits) implements SearchResult {
    }

    /**
     * 搜索失败/被拒：{@link #reason} 如实返回给智能体（非 200、坏响应、连接异常、
     * 缺 key 等）。
     */
    record Failed(String reason) implements SearchResult {
    }

    /** 单条搜索结果：标题 + URL + 摘要（供数方无关的最小契约面）。 */
    record Hit(String title, String url, String snippet) {
    }
}
