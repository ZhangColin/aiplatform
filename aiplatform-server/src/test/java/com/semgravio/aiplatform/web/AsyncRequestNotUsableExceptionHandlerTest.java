package com.semgravio.aiplatform.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSE 断连噪音静默的 advice 行为验证（#479）：异步分发侧送来的
 * {@link AsyncRequestNotUsableException}（客户端断开，响应已提交不可写）由本
 * advice 以 void 语义吞掉——不 500、不写 ApiResponse 响应体。advice 相对全局
 * 兜底的选中优先级在真机缝回归（AgentEventDeadSocketTest 心跳腿用例）。
 */
class AsyncRequestNotUsableExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new AsyncRequestNotUsableExceptionHandler())
                .build();
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/response-not-usable")
        public void notUsable() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("Response not usable after response errors.");
        }
    }

    @Test
    void given_response_not_usable_when_advice_handles_then_swallowed_without_body() throws Exception {
        mockMvc.perform(get("/probe/response-not-usable"))
                .andExpect(status().isOk())   // void：响应已由 advice 收口
                .andExpect(content().string(""));   // 不写 ApiResponse
    }
}
