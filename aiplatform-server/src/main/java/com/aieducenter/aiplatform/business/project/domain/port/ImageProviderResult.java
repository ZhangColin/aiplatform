package com.aieducenter.aiplatform.business.project.domain.port;

/**
 * 出图供数结果（{@link ImageGenerationProvider} 协议形，形制对偶
 * {@link SearchResult}）：成功＝恰一张图（URL／base64 二选一）＋模型标识（计量
 * {@code UsageEvent.model} 匹配键）；失败＝如实理由（调用方有限重试后如实回给
 * 智能体）。
 *
 * <p><b>URL 只中转</b>（ADR-0027 转存口径）：供应商 URL 时效 24h（智谱 30 天），
 * 平台即时转存落盘产物目录——URL 不入库不透出前端，本结果只在调用栈内短暂存活。</p>
 */
public sealed interface ImageProviderResult {

    /** 成功：模型标识（单价表/计量匹配键，适配器自报——换模型配置计量随改）＋一张图。 */
    record Generated(String model, Image image) implements ImageProviderResult {
    }

    /** 失败：如实理由（HTTP 状态／业务错误码／审核拦截／连接异常，不抛错）。 */
    record Failed(String reason) implements ImageProviderResult {
    }

    /**
     * 一张生成图：{@code url} 与 {@code base64} 恰一非空（URL 形＝容器内下载转存；
     * base64 形＝字节经 stdin 灌入落盘）；{@code extension} 落盘扩展名提示
     * （小写、不带点；URL 形按地址路径段推、base64 形按供应商出图格式参数，
     * 推不出＝png）。
     */
    record Image(String url, String base64, String extension) {

        public Image {
            if ((url == null || url.isBlank()) == (base64 == null || base64.isBlank())) {
                throw new IllegalArgumentException("生成图必须恰带 url 或 base64 之一");
            }
            extension = extension == null || extension.isBlank()
                    ? "png" : extension.toLowerCase();
        }

        /** URL 形判定（转存走容器内下载；否则字节已在手上）。 */
        public boolean isUrlForm() {
            return url != null && !url.isBlank();
        }
    }
}
