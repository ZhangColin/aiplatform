package com.aieducenter.aiplatform.business.project.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 知识编排业务缝（v1 沉淀触发点 = 成交沉淀 PRD，#30 起消费；命中注入在需求环/
 * 生成环接线）：分块器（段落级 ~800 字符）与级联清理降级不炸。端口 mock——入库/
 * 检索的 SQL 与降级语义见 KnowledgeAppServiceTest。
 */
@SpringBootTest
class ProjectKnowledgeAppServiceTest {

    @Autowired
    private ProjectKnowledgeAppService appService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KnowledgePort knowledgePort;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
    }

    @Test
    void given_long_text_when_chunk_then_paragraph_level_with_hard_split() {
        String paragraph800 = "甲".repeat(800);
        String shortTail = "结尾段";
        String longParagraph = "乙".repeat(2000);

        List<String> chunks = ProjectKnowledgeAppService.chunkByParagraph(
                paragraph800 + "\n\n" + shortTail + "\n\n" + longParagraph);

        // 800 段独立成块（并入短尾会超目标长度）；超长段按 800 硬切三块（800/800/400）
        assertThat(chunks).hasSize(5);
        assertThat(chunks.get(0)).hasSize(800);
        assertThat(chunks.get(1)).isEqualTo(shortTail);
        assertThat(chunks.get(2)).hasSize(800);
        assertThat(chunks.get(3)).hasSize(800);
        assertThat(chunks.get(4)).hasSize(400);
        assertThat(ProjectKnowledgeAppService.chunkByParagraph("  ")).isEmpty();
        assertThat(ProjectKnowledgeAppService.chunkByParagraph(null)).isEmpty();
    }

    @Test
    void given_purge_when_delete_cascade_then_port_called_and_failure_degraded() {
        doThrow(new RuntimeException("清理失败"))
                .when(knowledgePort).purgeByProject(anyString());

        appService.purgeByProject(9408L); // 不抛（残留可重删收敛）
        verify(knowledgePort).purgeByProject("9408");
    }
}
