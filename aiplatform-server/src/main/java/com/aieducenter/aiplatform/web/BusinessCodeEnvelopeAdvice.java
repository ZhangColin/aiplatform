package com.aieducenter.aiplatform.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.CartisanException;
import com.cartisan.core.exception.CodeMessage;
import com.cartisan.web.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * 业务异常信封：{@code code} 装数字业务码（#169，2026-09-14 预览误报事故根因）。
 *
 * <p>cartisan-web 的 GlobalExceptionHandler 把 {@code httpStatus} 装进信封
 * {@code code}、业务字符串码（{@code WSP_012}…）不上线——前端业务码比对全为死
 * 分支，预览「未就绪（待期）」被误判真故障。本 advice 以最高序抢占
 * {@link CartisanException}（Application/Domain 同族，业务码一套口径——只拦
 * ApplicationException 会同码两形）：HTTP 状态与日志口径照旧（对齐 cartisan：
 * 5xx error、其余 warn、message 取格式化后文本），仅 {@code code} 换为
 * {@link ErrorCodePrefix#numericOf 数字业务码}——命中前缀注册表取域码×1000＋
 * 序号，未登记（cartisan 通用码等）回落 httpStatus、行为与旧信封一致。非
 * CartisanException 家族（auth/resubmit 等自有 advice）不经本类。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class BusinessCodeEnvelopeAdvice {

    @ExceptionHandler(CartisanException.class)
    public ResponseEntity<ApiResponse<Void>> handle(CartisanException exception) {
        CodeMessage codeMessage = exception.getCodeMessage();
        if (codeMessage.httpStatus() >= 500) {
            log.error("Business error: {}", exception.getMessage());
        } else {
            log.warn("Business error: {}", exception.getMessage());
        }
        return ResponseEntity
                .status(codeMessage.httpStatus())
                .body(ApiResponse.error(ErrorCodePrefix.numericOf(codeMessage), exception.getMessage())
                        .withRequestId(RequestContext.getRequestId()));
    }
}
