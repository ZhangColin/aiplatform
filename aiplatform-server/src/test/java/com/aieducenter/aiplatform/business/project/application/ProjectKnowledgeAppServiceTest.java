package com.aieducenter.aiplatform.business.project.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.aieducenter.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识编排业务缝（v1 沉淀触发点 = 成交沉淀 PRD，#30 起消费；命中注入在需求环/
 * 生成环接线）：分块器（段落级 ~800 字符）、级联清理降级不炸、BA 会话建立的
 * 知识命中注入块（命中拼装 / 空命中 / 检索失败降级）。端口 mock——入库/
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

    @Test
    void given_hits_when_establish_session_injection_then_background_block_composed_and_cached() {
        // 命中拼装：背景块接 system prompt 尾部（调用方拼）——含来源项目名、标题、片段
        when(knowledgePort.retrieve(eq("给宠物医院做预约系统"), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"),
                new KnowledgeHit("PRD", "连锁诊所系统", "PRD·连锁诊所管理",
                        "范围边界：不含库存。")));

        String block = appService.establishSessionInjection(9500L, "给宠物医院做预约系统");

        assertThat(block).startsWith("\n\n【平台知识库·相似历史需求】");
        assertThat(block).contains("宠物医院预约平台").contains("PRD·宠物医院预约")
                .contains("核心场景：主人在线选医生预约。");
        assertThat(block).contains("连锁诊所系统").contains("不含库存");
        // 知识是背景非指令：块自带「非用户的确认信息」限定语
        assertThat(block).contains("非用户的确认信息");
        // 会话缓存：后续轮复用同一块（一次切入一次注入），且不再触检索
        assertThat(appService.sessionTailOf(9500L)).isEqualTo(block);
        assertThat(appService.sessionTailOf(9501L)).isEmpty(); // 别的项目不串台
        verify(knowledgePort, org.mockito.Mockito.times(1)).retrieve(anyString(), anyInt());
    }

    @Test
    void given_no_hit_or_blank_query_when_establish_then_empty_and_not_cached() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of());

        assertThat(appService.establishSessionInjection(9502L, "全新的想法")).isEmpty(); // 空命中
        assertThat(appService.sessionTailOf(9502L)).isEmpty(); // 不落缓存（裸角色卡照跑）
        assertThat(appService.establishSessionInjection(9502L, " ")).isEmpty(); // 空 query 不检索
        assertThat(appService.establishSessionInjection(9502L, null)).isEmpty();
        verify(knowledgePort).retrieve(eq("全新的想法"), anyInt()); // 空 query 未触检索
    }

    @Test
    void given_retrieval_failure_when_establish_then_degraded_empty() {
        // 检索失败降级为空注入：访谈照常开始，不阻断主链
        doThrow(new RuntimeException("pgvector 抖动"))
                .when(knowledgePort).retrieve(anyString(), anyInt());

        assertThat(appService.establishSessionInjection(9503L, "做一个官网")).isEmpty();
        assertThat(appService.sessionTailOf(9503L)).isEmpty();
    }

    @Test
    void given_oversized_query_when_establish_then_truncated() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of());
        String longRequirement = "需".repeat(6000);

        appService.establishSessionInjection(9504L, longRequirement);

        verify(knowledgePort).retrieve(eq("需".repeat(2000)), anyInt()); // 截 2000 字符
    }

    @Test
    void given_established_tail_when_purge_then_tail_evicted_with_chunks() {
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(List.of(
                new KnowledgeHit("PRD", "某项目", "PRD", "片段")));
        assertThat(appService.establishSessionInjection(9505L, "做一个官网")).isNotEmpty();

        appService.purgeByProject(9505L);

        assertThat(appService.sessionTailOf(9505L)).isEmpty(); // 项目删除连注入口一起清
        verify(knowledgePort).purgeByProject("9505");
    }
}
