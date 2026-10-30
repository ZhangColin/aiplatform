package com.semgravio.aiplatform.business.project.application.dto.response;

/**
 * 生成停止响应（#359 生成取消）：{@code stopped} = 停止请求是否命中在途 run——
 * true = 已置停（终态收口 run-failed(stopped=true) 随 SSE 到达，项目态转「生成
 * 中断/更新可继续」）；false = 无在途（幂等如实回——连点/与自然收口的竞态无害，
 * 不 409）。停止是异步收场：请求返回 ≠ 执行体已完全落定，以 SSE/REST 重查为准。
 *
 * @param stopped 停止请求是否命中在途 run
 */
public record GenerationStopResponse(boolean stopped) {
}
