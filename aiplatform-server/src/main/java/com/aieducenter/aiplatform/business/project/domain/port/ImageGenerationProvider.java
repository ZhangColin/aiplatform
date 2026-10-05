package com.aieducenter.aiplatform.business.project.domain.port;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * 图片生成供数口（#288 出图工具件内核的南向缝，对偶 {@link WebSearchProvider}
 * 博查先例）：文生图供应商 API 的端口——四家适配器（智谱/火山方舟/OpenAI/阿里
 * DashScope，ADR-0026 四家全接）实现本口，按配置选其一（换供数方＝换配置＋加
 * 适配器，主链路零改动）。端口契约不渗任何供数方 specifics（base-url/key/model
 * 是实例化配置）。
 *
 * <p><b>工具面接入非模型面</b>（ADR-0026）：core Model 接口纯 chat，图片模型不进
 * ModelRef 白名单——本口是出图工具件背后的 API，不是模型面。单次调用＝一张图
 * （各供应商对批量参数支持不一，多张由调用方循环、每次调用各自计量——「每次调用
 * 报恰一条按张计量事件」的口径落在调用侧）。</p>
 *
 * <p>降级契约对齐博查先例：非 200／业务错误码（含审核拦截）／坏响应／连接异常
 * 一律 {@link ImageProviderResult.Failed} 带如实理由，不抛错不炸。</p>
 */
@Port(PortType.CLIENT)
public interface ImageGenerationProvider {

    /**
     * 供数方标识（计量 {@code UsageEvent.provider} 与适配器选择的同一词表：
     * zhipu／volcano／openai／dashscope）。
     */
    String providerKey();

    /**
     * 出一张图（单次供应商调用）。
     *
     * @param request 出图请求（prompt 必填；size 可空＝供应商缺省）
     * @return 成功带恰好一张图（URL 或 base64 二选一＋扩展名提示）；失败带如实理由
     */
    ImageProviderResult generate(ImageGenerationRequest request);
}
