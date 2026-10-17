package com.aieducenter.aiplatform.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;

import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * multipart 容器层超限 → 业务码信封（#286 物料上传；#337 付款凭证第二上传面
 * 落位）：业务上限（10MB）在应用层判定（物料 PRJ_043 / 凭证 ORD_040），multipart
 * 容器上限（application.yml 留有余量）是防大流量直灌的硬闸——越过硬闸的请求
 * 在本 advice 按请求路径归一回所属上传面的业务码信封（同一句「太大」、同一
 * 个数字码，前端判错零分叉）。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class MultipartLimitEnvelopeAdvice {

    /** 付款凭证上传面路径特征（订单付款子资源）。 */
    private static final String PAYMENT_VOUCHER_PATH_MARKER = "/payment-nodes/";

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handle(MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        // 第二上传面（#337）：按路径归位错误域——付款凭证走 ORD_040、其余维持
        // 物料面 PRJ_043（同一形制，避免凭证上传收到「单张图片」文案）
        log.warn("Multipart size exceeded: {}", exception.getMessage());
        boolean paymentVoucher = request.getRequestURI() != null
                && request.getRequestURI().contains(PAYMENT_VOUCHER_PATH_MARKER);
        if (!paymentVoucher) {
            ProjectMessage material = ProjectMessage.MATERIAL_UPLOAD_TOO_LARGE;
            return ResponseEntity
                    .status(material.httpStatus())
                    .body(ApiResponse.error(ErrorCodePrefix.numericOf(material), material.message())
                            .withRequestId(RequestContext.getRequestId()));
        }
        OrderMessage code = OrderMessage.PAYMENT_VOUCHER_INVALID;
        return ResponseEntity
                .status(code.httpStatus())
                .body(ApiResponse.error(ErrorCodePrefix.numericOf(code), code.message())
                        .withRequestId(RequestContext.getRequestId()));
    }
}
