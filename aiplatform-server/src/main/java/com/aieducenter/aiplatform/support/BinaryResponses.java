package com.aieducenter.aiplatform.support;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 二进制流端点响应装配（#298 收单点——「第四处出现时收 helper」备案触发器兑现，
 * 先正本＝#297 备案）：下载件端点不走 ApiResponse JSON 信封，统一形＝
 * Content-Type（真实媒体类型）＋Content-Disposition attachment 文件名＋原始字节，
 * 收口时顺带统一 {@code X-Content-Type-Options: nosniff}（附件面防 MIME 混淆的
 * 加固头，行为零改——此前部分端点已有、其余为缺省未设，#287/#294 起的既定取向）。
 *
 * <p>inline 直出面（{@code ProjectController#fileRaw}——CSP 两键随 HTML 判定分岔）
 * 形制独有，不入本单点。</p>
 */
public final class BinaryResponses {

    private BinaryResponses() {
    }

    /** 附件下载响应：{@code mediaType} 原样解析（如 application/gzip、image/png）、
     *  {@code filename} 进 Content-Disposition。 */
    public static ResponseEntity<ByteArrayResource> attachment(byte[] content,
            String mediaType, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(mediaType));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename).build());
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(content));
    }
}
