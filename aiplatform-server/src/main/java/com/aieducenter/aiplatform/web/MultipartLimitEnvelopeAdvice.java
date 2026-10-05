package com.aieducenter.aiplatform.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * multipart 容器层超限 → 业务码信封（#286 物料上传）：业务上限（10MB，
 * {@code PRJ_043}）在应用层判定，multipart 容器上限（application.yml 留有
 * 余量）是防大流量直灌的硬闸——越过硬闸的请求在本 advice 归一回同一业务码
 * 信封（同一句「太大」、同一个数字码，前端判错零分叉）。v1 唯一上传面＝物料
 * 上传，映射进 ProjectMessage；第二上传面出现时再议通用化。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class MultipartLimitEnvelopeAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handle(MaxUploadSizeExceededException exception) {
        ProjectMessage code = ProjectMessage.MATERIAL_UPLOAD_TOO_LARGE;
        log.warn("Multipart size exceeded: {}", exception.getMessage());
        return ResponseEntity
                .status(code.httpStatus())
                .body(ApiResponse.error(ErrorCodePrefix.numericOf(code), code.message())
                        .withRequestId(RequestContext.getRequestId()));
    }
}
