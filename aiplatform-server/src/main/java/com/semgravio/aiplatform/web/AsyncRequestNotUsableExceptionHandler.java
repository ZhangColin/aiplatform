package com.semgravio.aiplatform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * SSE 断连的异步分发噪音静默（#479）：客户端断开后，向死连接的心跳/广播写入使
 * {@code StandardServletAsyncWebRequest} 进入 ERROR 态，随后 {@code SseChannelHub}
 * 逐出订阅的 {@code emitter.complete()} 在内部 flush 撞「响应已错」抛
 * {@link AsyncRequestNotUsableException}（"Response not usable after response
 * errors"，栈指向心跳/逐出链）——Spring 6.2 的 {@code DefaultSseEmitterHandler}
 * 不向调用方重抛，而以 {@code setErrorResult} 携异常入异步分发，dispatch 后
 * 异常解析落进全局兜底：ERROR「Unexpected error」+ 向已提交的 text/event-stream
 * 写 ApiResponse 的二次 WARN，每心跳周期一对刷屏。
 *
 * <p>本 advice 在链上以 {@code @Order(HIGHEST_PRECEDENCE)} 先于无序的全局兜底被
 * 选中，void 返回（响应已提交、连接已死，无事可做）——不写响应体，ERROR/WARN
 * 对同灭。断连是 SSE 常态生命周期（内核已逐出订阅），非服务端错误。</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AsyncRequestNotUsableExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AsyncRequestNotUsableExceptionHandler.class);

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        // debug：生产实录此为唯一可见 ERROR（误导排查）；连接已死，静默即可
        log.debug("异步响应已不可用（客户端断开，SSE 常态）：{}", ex.getMessage());
    }
}
