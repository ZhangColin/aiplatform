package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.ModelRegistry;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import reactor.core.publisher.Flux;

/**
 * Seam C（#365／spec #364 测试决议，SDK 边界）：volcengine 前缀平台工厂——
 * fake HttpTransport 注入（SDK 原生传输层组件缝），断言最终 HTTP 请求打向
 * <b>通道行 baseUrl</b>、Bearer 带<b>库内 key</b>、thinking 参数如设、SSE 解析
 * 不变。不打真 API、不用真 key——凭证与地址全由创建上下文带入（ADR-0031 装配
 * 链的 SDK 半边）。
 */
class VolcengineModelFactoryTest {

    private static final String ARK_BASE_URL = "https://ark.example/api/v3";

    private CapturingTransport transport;

    @BeforeEach
    void registerPlatformFactory() {
        VolcengineModelRegistrar.register();
        transport = new CapturingTransport();
    }

    @Test
    void given_channel_context_when_stream_then_request_hits_channel_url_with_db_key_and_thinking_off() {
        Model model = ModelRegistry.resolve("volcengine:deepseek-v4-flash-ga-260731",
                context(false));

        List<ChatResponse> responses = model
                .stream(List.of(UserMessage.builder().textContent("你好").build()), List.of(), null)
                .collectList()
                .block();

        HttpRequest request = transport.captured.get(0);
        // 通道行 baseUrl 直达（版本号 baseUrl 原生拼接：/api/v3 命中 /v1 剥离）
        assertThat(request.getUrl())
                .isEqualTo(ARK_BASE_URL + "/chat/completions");
        // 凭证来自创建上下文（库内 key），非环境变量
        assertThat(request.getHeaders().get("Authorization")).isEqualTo("Bearer sk-from-db");
        String body = request.getBody();
        assertThat(body).contains("\"model\":\"deepseek-v4-flash-ga-260731\"");
        // enableThinking=false → thinking 显式关（方舟 DeepSeek V4 标深度思考，
        // 不传参默认未确证——装配不留给模型默认）
        assertThat(body).contains("\"thinking\":{\"type\":\"disabled\"}");
        // SSE 解析不变：data 行照走 OpenAI chunk 形
        assertThat(responses).isNotNull();
        String text = responses.stream()
                .flatMap(r -> r.getContent().stream())
                .map(block -> block instanceof io.agentscope.core.message.TextBlock tb
                        ? tb.getText() : "")
                .reduce("", String::concat);
        assertThat(text).isEqualTo("你好，火山");
    }

    @Test
    void given_blank_api_key_when_resolve_then_explicit_error_no_env_fallback() {
        ModelCreationContext context = ModelCreationContext.builder()
                .baseUrl(ARK_BASE_URL)
                .component(io.agentscope.core.model.transport.HttpTransport.class, transport)
                .build();

        assertThatThrownBy(() -> ModelRegistry.resolve("volcengine:deepseek-v4-flash-ga-260731",
                context))
                .hasMessageContaining("apiKey")
                .hasMessageContaining("volcengine");
    }

    private ModelCreationContext context(boolean thinkingEnabled) {
        return ModelCreationContext.builder()
                .apiKey("sk-from-db")
                .baseUrl(ARK_BASE_URL)
                .enableThinking(thinkingEnabled)
                .component(HttpTransport.class, transport)
                .build();
    }

    /** 伪传输层：捕获请求（URL/头/体），回放预制 SSE data 行。 */
    private static final class CapturingTransport implements HttpTransport {

        private final List<HttpRequest> captured = new CopyOnWriteArrayList<>();

        @Override
        public HttpResponse execute(HttpRequest request) {
            throw new UnsupportedOperationException("流式断言不走同步 execute");
        }

        @Override
        public Flux<String> stream(HttpRequest request) {
            captured.add(request);
            return Flux.just(
                    "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好，\"},"
                            + "\"finish_reason\":null}]}",
                    "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"火山\"},"
                            + "\"finish_reason\":null}]}",
                    "[DONE]");
        }

        @Override
        public void close() {
            // 无资源可释放
        }
    }
}
