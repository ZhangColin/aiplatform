package com.semgravio.aiplatform.business.project.domain.port;

/**
 * 远端模型清单拉取端口（#368，spec #364-T4，ADR-0031「模型清单手动同步」）：
 * 按通道行参数（baseUrl＋apiKey）拉取该供应商的远端模型清单——OpenAI 兼容
 * {@code GET {baseUrl}/models}（方舟端点实测同形状）。凭证与地址来自通道行
 * （库内唯一正本），端口不持有供应商状态——换通道换 key 不动此处。
 *
 * <p>实现面唯一（OpenAI 兼容适配器）；非 OpenAI 协议通道出现时再立适配器
 * 选择（同 {@link WebSearchProvider} 备案口径）。</p>
 */
public interface ModelCatalogFetcher {

    /**
     * 拉取远端模型清单。
     *
     * @param baseUrl 通道 baseUrl（通道行值；容忍尾斜杠）
     * @param apiKey  通道凭证（调用方保证非空——空 key 是调用方前置错误）
     * @return 成功带条目列表；失败带如实理由（不抛错，理由转错误信封）
     */
    ModelCatalog fetch(String baseUrl, String apiKey);
}
