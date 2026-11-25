package com.semgravio.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeHit;
import com.semgravio.aiplatform.base.knowledge.domain.model.KnowledgeRetrieval;
import com.semgravio.aiplatform.base.knowledge.domain.port.KnowledgePort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识编排业务缝（v1 沉淀触发点 = 成交沉淀 PRD，#30 起消费；#639 知识命中只剩
 * 主智能体开场一处）：分块器（段落级 ~800 字符）、级联清理降级不炸、会话建立的
 * 知识命中注入块（保护句＋非约束限定语 / 空命中 / 检索降级）、命中留痕（每次检索
 * 一条、成功 0 条与降级可区分、从最近记录重建不重检索）。端口 mock——留痕存储语义
 * 与 SQL 见 KnowledgeAppServiceTest／真库恢复面见 ProjectKnowledgeRetrievalRecoveryTest。
 */
@IntegrationTest
class ProjectKnowledgeAppServiceTest {

    @Autowired
    private ProjectKnowledgeAppService appService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KnowledgePort knowledgePort;

    /** 端口 mock 上的进程内留痕替身（record → latest 可读回）。 */
    private final Map<String, KnowledgeRetrieval> recorded = new ConcurrentHashMap<>();

    /** 本次用例写过的全部留痕（顺序断言用）。 */
    private final List<KnowledgeRetrieval> records = new CopyOnWriteArrayList<>();

    @BeforeEach
    void stubKnowledgePort() {
        recorded.clear();
        records.clear();
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(KnowledgeRetrieval.empty());
        doAnswer(invocation -> {
            recorded.put(invocation.getArgument(0), invocation.getArgument(1));
            records.add(invocation.getArgument(1));
            return null;
        }).when(knowledgePort).recordRetrieval(anyString(), any());
        when(knowledgePort.latestRetrieval(anyString())).thenAnswer(invocation ->
                recorded.getOrDefault(invocation.getArgument(0), KnowledgeRetrieval.empty()));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_conversation_entries");
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
    void given_hits_when_establish_then_protective_block_recorded_and_rebuilt_without_retrieval() {
        // 命中拼装（#466 多类同池命中）：背景块接 system prompt 尾部（调用方拼）——
        // 每条带来源与类别标签；#639 块首加跨项目保护句，非约束限定语保留
        when(knowledgePort.retrieve(eq("给宠物医院做预约系统"), anyInt())).thenReturn(
                KnowledgeRetrieval.of(List.of(
                        new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                                "核心场景：主人在线选医生预约。"),
                        new KnowledgeHit("CASE", "平台沉淀", "宠物医院在线预约的典型形态",
                                "主人选医生、按时段预约、迟到作废。"),
                        new KnowledgeHit("RULE", "平台沉淀", "预约类业务的取消规则",
                                "开场前 2 小时内不可取消。"))));

        String block = appService.establishSessionInjection(9500L, "给宠物医院做预约系统");

        assertThat(block).startsWith("\n\n【平台知识库·相似知识】");
        // 块首保护句：来自其他项目、不向用户提及其他项目、不当成当前需求的结论
        assertThat(block).contains("这些内容来自平台其他项目，不要向用户提及其他项目，"
                + "也不要把它们当作当前需求的结论。");
        // 命中条带来源 kind 区分：〔来源｜类别〕标题
        assertThat(block).contains("〔宠物医院预约平台｜历史需求〕PRD·宠物医院预约")
                .contains("核心场景：主人在线选医生预约。");
        assertThat(block).contains("〔平台沉淀｜需求案例〕宠物医院在线预约的典型形态")
                .contains("迟到作废");
        assertThat(block).contains("〔平台沉淀｜业务规则〕预约类业务的取消规则")
                .contains("开场前 2 小时内不可取消");
        // 知识是背景非指令：块自带「非用户的确认信息」限定语（非约束口径保留）
        assertThat(block).contains("非用户的确认信息，不构成对当前需求的约束");
        // 命中留痕：恰一条，成功、未降级、三条命中
        assertThat(records).hasSize(1);
        assertThat(records.get(0).degraded()).isFalse();
        assertThat(records.get(0).hits()).hasSize(3);
        // 后续轮复用：从最近记录重建同一块（不重检索）
        assertThat(appService.sessionTailOf(9500L)).isEqualTo(block);
        assertThat(appService.sessionTailOf(9501L)).isEmpty(); // 别的项目不串台
        verify(knowledgePort, times(1)).retrieve(anyString(), anyInt());
    }

    @Test
    void given_unknown_kind_hit_when_establish_then_raw_kind_passed_through() {
        // kind 开放（#451）：未知类别不映射不丢条——原键透传（新类生长零改注入面）
        when(knowledgePort.retrieve(anyString(), anyInt())).thenReturn(
                KnowledgeRetrieval.of(List.of(
                        new KnowledgeHit("GLOSSARY", "平台沉淀", "新生长类别的条目", "片段正文。"))));

        String block = appService.establishSessionInjection(9506L, "做一个官网");

        assertThat(block).contains("〔平台沉淀｜GLOSSARY〕新生长类别的条目");
    }

    @Test
    void given_no_hit_when_establish_then_empty_and_recorded_zero_not_degraded() {
        // 检索成功但 0 条：不注入，但留痕照记（未降级、0 条——用于有/无命中比较）
        assertThat(appService.establishSessionInjection(9502L, "全新的想法")).isEmpty();
        assertThat(appService.sessionTailOf(9502L)).isEmpty(); // 空块（裸配置照跑）
        assertThat(records).hasSize(1);
        assertThat(records.get(0).degraded()).isFalse();
        assertThat(records.get(0).hits()).isEmpty();
        verify(knowledgePort).retrieve(eq("全新的想法"), anyInt());
    }

    @Test
    void given_blank_requirement_when_establish_then_no_retrieval_and_no_record() {
        // #639 访客没填需求描述（走平台固定开场白）：不检索、不写命中记录、不注入
        assertThat(appService.establishSessionInjection(9503L, " ")).isEmpty();
        assertThat(appService.establishSessionInjection(9503L, null)).isEmpty();

        verify(knowledgePort, never()).retrieve(anyString(), anyInt());
        verify(knowledgePort, never()).recordRetrieval(anyString(), any());
        assertThat(records).isEmpty();
    }

    @Test
    void given_retrieval_failure_when_establish_then_empty_and_recorded_degraded() {
        // 检索失败：不注入、访谈照常；留痕记降级（与「真 0 条」可区分）
        doThrow(new RuntimeException("pgvector 抖动"))
                .when(knowledgePort).retrieve(anyString(), anyInt());

        assertThat(appService.establishSessionInjection(9504L, "做一个官网")).isEmpty();
        assertThat(appService.sessionTailOf(9504L)).isEmpty();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).degraded()).isTrue();
        assertThat(records.get(0).hits()).isEmpty();
    }

    @Test
    void given_oversized_query_when_establish_then_truncated() {
        String longRequirement = "需".repeat(6000);

        appService.establishSessionInjection(9505L, longRequirement);

        verify(knowledgePort).retrieve(eq("需".repeat(2000)), anyInt()); // 截 2000 字符
    }

    @Test
    void given_latest_record_when_session_tail_then_rebuilt_from_record_without_retrieval() {
        // 重启恢复面（#639，业务侧）：命中块不再存进程内缓存——直接从该项目最近
        // 一次留痕重建（此处以端口替身模拟「新进程读到重启前的记录」）
        recorded.put("9508", KnowledgeRetrieval.of(List.of(
                new KnowledgeHit("PRD", "宠物医院预约平台", "PRD·宠物医院预约",
                        "核心场景：主人在线选医生预约。"))));

        String tail = appService.sessionTailOf(9508L);

        assertThat(tail).contains("【平台知识库·相似知识】")
                .contains("这些内容来自平台其他项目，不要向用户提及其他项目，"
                        + "也不要把它们当作当前需求的结论。")
                .contains("〔宠物医院预约平台｜历史需求〕PRD·宠物医院预约")
                .contains("核心场景：主人在线选医生预约。");
        verify(knowledgePort, never()).retrieve(anyString(), anyInt()); // 不重检索
    }
}
