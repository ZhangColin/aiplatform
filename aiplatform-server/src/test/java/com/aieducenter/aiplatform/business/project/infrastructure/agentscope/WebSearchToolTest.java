package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolCallParam;

import com.aieducenter.aiplatform.business.project.domain.port.SearchResult;
import com.aieducenter.aiplatform.business.project.domain.port.WebSearchProvider;

/**
 * {@link WebSearchTool}（#215 调研闭环）：工具面映射——供数口成功回结果文本、失败把
 * 理由如实回给模型（error block，不抛错）；空 query 如实报错。
 */
class WebSearchToolTest {

    private final WebSearchProvider provider = mock(WebSearchProvider.class);

    @Test
    void given_results_when_call_then_text_block_with_hits() {
        when(provider.search("AI 建站平台")).thenReturn(new SearchResult.Results(List.of(
                new SearchResult.Hit("Lovable", "https://lovable.dev", "AI 建站平台"),
                new SearchResult.Hit("Replit", "https://replit.com", "在线 IDE"))));

        ToolResultBlock block = new WebSearchTool(provider).callAsync(
                ToolCallParam.builder().input(Map.of("query", "AI 建站平台")).build()).block();

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).contains("Lovable", "https://lovable.dev", "Replit");
    }

    @Test
    void given_empty_results_when_call_then_text_block_says_no_results() {
        when(provider.search("无结果")).thenReturn(new SearchResult.Results(List.of()));

        ToolResultBlock block = new WebSearchTool(provider).callAsync(
                ToolCallParam.builder().input(Map.of("query", "无结果")).build()).block();

        assertThat(block.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).contains("未搜到结果");
    }

    @Test
    void given_failed_when_call_then_error_block_with_reason() {
        when(provider.search("查询")).thenReturn(new SearchResult.Failed("搜索失败（连接异常）：拒绝连接"));

        ToolResultBlock block = new WebSearchTool(provider).callAsync(
                ToolCallParam.builder().input(Map.of("query", "查询")).build()).block();

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(block)).contains("连接异常");
    }

    @Test
    void given_blank_query_when_call_then_error_block() {
        ToolResultBlock block = new WebSearchTool(provider).callAsync(
                ToolCallParam.builder().input(Map.of("query", " ")).build()).block();

        assertThat(block.getState()).isEqualTo(ToolResultState.ERROR);
    }

    private static String textOf(ToolResultBlock block) {
        return ((TextBlock) block.getOutput().get(0)).getText();
    }
}
