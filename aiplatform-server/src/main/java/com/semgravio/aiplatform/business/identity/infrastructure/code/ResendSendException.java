package com.semgravio.aiplatform.business.identity.infrastructure.code;

/**
 * Resend 发信失败（#376）：不重试、不吞错——异常上抛、接口如实报错（demo 口径），
 * 状态码与错误体带出供日志定位（形态照 TokenExchangeException）。
 */
public class ResendSendException extends RuntimeException {

    private final Integer httpStatus;
    private final String responseBody;

    public ResendSendException(String message, Integer httpStatus, String responseBody,
            Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    /** Resend 发信 API 的 HTTP 状态码；连接/超时等无状态码时为 {@code null} */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** Resend 错误响应体（{@code {name, message}}）；无则为 {@code null} */
    public String responseBody() {
        return responseBody;
    }
}
