package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;

import com.aieducenter.aiplatform.business.project.domain.port.ExternalContentFetcher;
import com.aieducenter.aiplatform.business.project.domain.port.FetchResult;

/**
 * {@link FetchUrlTool}（#213 抓取闭环）：工具面映射——取数口成功回内容文本、被拒/
 * 失败把理由如实回给模型（error block，不抛错）；空 url 如实报错。
 */
class FetchUrlToolTest {

    private final ExternalContentFetcher fetcher = mock(ExternalContentFetcher.class);

    @Test
    void given_content_when_call_then_text_block() {
        when(fetcher.fetch("https://example.com/a")).thenReturn(
                new FetchResult.Content("【不可信外部内容】\n正文", false, false));

        ToolResultBlock block = new FetchUrlTool(fetcher).callAsync(
                ToolCallParam.builder().input(Map.of("url", "https://example.com/a")).build()).block();

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).contains("正文");
    }

    @Test
    void given_rejected_when_call_then_error_block_with_reason() {
        when(fetcher.fetch("http://127.0.0.1/x")).thenReturn(
                new FetchResult.Rejected("拒绝抓取该地址（目标为环回/内网/云元数据地址）：127.0.0.1"));

        ToolResultBlock block = new FetchUrlTool(fetcher).callAsync(
                ToolCallParam.builder().input(Map.of("url", "http://127.0.0.1/x")).build()).block();

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).contains("拒绝抓取该地址");
    }

    @Test
    void given_blank_url_when_call_then_error_block() {
        ToolResultBlock block = new FetchUrlTool(fetcher).callAsync(
                ToolCallParam.builder().input(Map.of("url", " ")).build()).block();

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
    }

    private static String textOf(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }
}
