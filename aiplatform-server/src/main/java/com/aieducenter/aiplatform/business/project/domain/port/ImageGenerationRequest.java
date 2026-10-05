package com.aieducenter.aiplatform.business.project.domain.port;

/**
 * 出图请求（{@link ImageGenerationProvider} 协议形）：{@code prompt} 画面正向描述
 * （必填非空）；{@code size} 画幅（可空＝供应商缺省——各供应商取值词表与默认
 * 推荐不一，按原样透传、供应商侧校验拒收，端口不做统一词表）。
 */
public record ImageGenerationRequest(String prompt, String size) {
}
