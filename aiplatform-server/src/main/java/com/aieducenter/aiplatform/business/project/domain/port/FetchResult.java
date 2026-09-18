package com.aieducenter.aiplatform.business.project.domain.port;

/**
 * 外部资料抓取结果（#213 抓取闭环的端口契约）：成功带已转文本、已按上限截断、
 * 已带「不可信外部内容」标注的内容；被拒/失败带如实理由（环回/内网/云元数据
 * 目标、非 200、连接异常、非法地址等）——不抛错不炸，理由由调用方（抓取工具）
 * 如实回给智能体。
 *
 * <p>与 {@link ExternalContentFetcher} 同源：URL 进 → 内容/降级标志/拒绝理由出，
 * 好测试只测这一外部行为，不测内部解析细节。</p>
 */
public sealed interface FetchResult {

    /**
     * 抓取成功：{@link #text} 为可直接进上下文的文本（已含不可信标注与降级/截断
     * 说明，前缀标注恒在首）；{@link #truncated} 表示响应超大小上限被截断；
     * {@link #initialHtmlOnly} 表示来源为 HTML（只取初始 HTML、未执行 JavaScript，
     * 动态加载的部分可能未取得）。
     */
    record Content(String text, boolean truncated, boolean initialHtmlOnly) implements FetchResult {
    }

    /**
     * 抓取被拒/失败：{@link #reason} 如实返回给智能体（环回/内网/云元数据目标、
     * 非 200、连接异常、非法地址等）。
     */
    record Rejected(String reason) implements FetchResult {
    }
}
