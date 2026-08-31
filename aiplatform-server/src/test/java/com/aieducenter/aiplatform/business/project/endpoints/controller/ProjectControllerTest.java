package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.format.FormatterRegistry;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.DomainException;
import com.cartisan.web.config.BaseEnumConverter;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;
import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;
import com.aieducenter.aiplatform.business.project.application.BaInterviewAppService;
import com.aieducenter.aiplatform.business.project.application.GenerationAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目 REST 面（swagger 契约验收）：ApiResponse 信封、建项目响应携带自动 BA
 * runId、类型 Integer code 双向、PRJ_001 → 404、参数校验；归档/用量/源码包/
 * 列表过滤的契约面。
 */
@WebMvcTest(ProjectController.class)
@Import({ProjectControllerTest.ExceptionAdviceConfig.class,
        ProjectControllerTest.EnumParamBindingConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectLifecycleAppService appService;

    @MockitoBean
    private ProjectQueryAppService queryAppService;

    @MockitoBean
    private BaInterviewAppService baInterviewAppService;

    @MockitoBean
    private GenerationAppService generationAppService;

    /** 全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "project-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_valid_command_when_create_then_wrapped_with_ba_run() throws Exception {
        when(appService.create(any())).thenReturn(
                new ProjectCreatedResponse(detailOf("100", ProjectStatus.IN_PROGRESS, false),
                        "run-1"));

        // 一句话创建：请求体只传 requirement（name/type/engine 从契约面消失）
        performAsUser(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"做一个官网\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.project.id").value("100"))
                .andExpect(jsonPath("$.data.project.type").value(1)) // BaseEnum → Integer code
                .andExpect(jsonPath("$.data.project.status").value(1)) // IN_PROGRESS → code
                .andExpect(jsonPath("$.data.project.statusName").value("进行中"))
                .andExpect(jsonPath("$.data.runId").value("run-1"));

        verify(appService).create(argThat(cmd -> "做一个官网".equals(cmd.requirement())));
    }

    @Test
    void given_oversized_requirement_when_create_then_rejected_as_400() throws Exception {
        // requirement 是创建唯一入参（可空）；长度上限 5000 仍守门
        performAsUser(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"" + "长".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_projects_when_list_then_wrapped_array() throws Exception {
        when(queryAppService.list(null)).thenReturn(List.of(new ProjectResponse("100", "官网",
                ProjectType.WEBSITE, "官网", "900",
                ProjectStatus.IN_PROGRESS, "进行中", false, null, null)));

        performAsUser(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("100"))
                .andExpect(jsonPath("$.data[0].status").value(1)) // IN_PROGRESS → code
                .andExpect(jsonPath("$.data[0].statusName").value("进行中"));
    }

    @Test
    void given_status_filter_when_list_then_passed_through() throws Exception {
        when(queryAppService.list(ProjectStatusFilter.ACTIVE)).thenReturn(List.of());

        performAsUser(get("/api/projects").param("status", "1")) // ACTIVE → Integer code
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        verify(queryAppService).list(ProjectStatusFilter.ACTIVE);
    }

    @Test
    void given_unknown_filter_when_list_then_prj_014_as_400() throws Exception {
        // 非法取值（未知 code / 非数值）在绑定层即 400 PRJ_014，应用服务不被触达
        performAsUser(get("/api/projects").param("status", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("无效的项目列表状态过滤参数"));
        performAsUser(get("/api/projects").param("status", "active"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("无效的项目列表状态过滤参数"));
        verify(queryAppService, never()).list(any());
    }

    @Test
    void given_detail_when_get_then_fields_returned() throws Exception {
        when(queryAppService.detail(100L)).thenReturn(
                detailOf("100", ProjectStatus.IN_PROGRESS, false));

        performAsUser(get("/api/projects/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("100"))
                .andExpect(jsonPath("$.data.name").value("官网 demo"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.archived").value(false));
    }

    @Test
    void given_unknown_project_when_get_then_prj_001_mapped_to_404() throws Exception {
        when(queryAppService.detail(404L))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(get("/api/projects/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_non_numeric_id_when_get_then_404() throws Exception {
        performAsUser(get("/api/projects/abc"))
                .andExpect(status().isNotFound());
    }

    @Test
    void given_project_when_delete_then_ok_without_data() throws Exception {
        performAsUser(delete("/api/projects/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(appService).delete(100L);
    }

    @Test
    void given_unarchived_when_archive_then_detail_returned() throws Exception {
        when(appService.archive(100L)).thenReturn(
                detailOf("100", ProjectStatus.ARCHIVED, true));

        performAsUser(post("/api/projects/100/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(3)) // ARCHIVED → code
                .andExpect(jsonPath("$.data.statusName").value("已归档"))
                .andExpect(jsonPath("$.data.archived").value(true));
    }

    @Test
    void given_already_archived_when_archive_again_then_prj_013_as_409() throws Exception {
        // 真实路径是聚合不变量的 DomainException（CartisanException 统一按 CodeMessage 映射 409）
        when(appService.archive(100L)).thenThrow(
                new DomainException(ProjectMessage.PROJECT_ALREADY_ARCHIVED));

        performAsUser(post("/api/projects/100/archive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("项目已归档（归档是单向终点）"));
    }

    @Test
    void given_project_when_generate_then_run_id_returned() throws Exception {
        // 「开始做系统」纯动作无入参：异步提交即返回首试 runId（过程经 SSE）
        when(generationAppService.startGeneration(100L)).thenReturn(
                new GenerationAppService.GenerationRun("run-gen-1"));

        performAsUser(post("/api/projects/100/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.runId").value("run-gen-1"));
        verify(generationAppService).startGeneration(100L);
    }

    @Test
    void given_generated_or_in_flight_project_when_generate_then_prj_017_as_409() throws Exception {
        // 重复发起守卫（已生成或生成在途）——调整入口是指令区意见，不是再生成
        when(generationAppService.startGeneration(100L)).thenThrow(
                new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED));

        performAsUser(post("/api/projects/100/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("系统已生成或正在生成中，请勿重复发起"));
    }

    @Test
    void given_prd_never_produced_when_generate_then_prj_018_as_409() throws Exception {
        // 前置事实守卫：PRD 从未产出（前端入口以 PRD 产出为呈现条件，拦直连调用）
        when(generationAppService.startGeneration(100L)).thenThrow(
                new ApplicationException(ProjectMessage.GENERATION_PRD_NOT_PRODUCED));

        performAsUser(post("/api/projects/100/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("PRD 尚未产出，先和需求分析师聊出 PRD 再开始做系统"));
    }

    @Test
    void given_valid_name_when_rename_then_detail_returned() throws Exception {
        // 动作端点风格同 archive；响应与详情端点同构（前端 invalidate 后刷新列表/顶栏）
        when(appService.rename(100L, "品牌官网")).thenReturn(
                new ProjectDetailResponse("100", "品牌官网", ProjectType.WEBSITE, "官网",
                        "900", ProjectStatus.IN_PROGRESS, "进行中", false,
                        LocalDateTime.of(2026, 8, 22, 10, 0), null, null, null));

        performAsUser(post("/api/projects/100/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"品牌官网\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("品牌官网"));
        verify(appService).rename(100L, "品牌官网");
    }

    @Test
    void given_oversized_name_when_rename_then_rejected_as_400() throws Exception {
        // 长度上限 100 归命令层守门（与取名净化同限，DB 列长同源）
        performAsUser(post("/api/projects/100/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "长".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(appService, never()).rename(any(), any());
    }

    @Test
    void given_blank_name_when_rename_then_prj_005_as_400() throws Exception {
        // 空白拒绝在聚合（PRJ_005，与建项目同口径——DomainException 按 CodeMessage 映射 400）
        when(appService.rename(eq(100L), argThat(" "::equals)))
                .thenThrow(new DomainException(ProjectMessage.PROJECT_NAME_BLANK));

        performAsUser(post("/api/projects/100/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("项目名不能为空白"));
    }

    @Test
    void given_unknown_project_when_rename_then_prj_001_mapped_to_404() throws Exception {
        when(appService.rename(404L, "任意名"))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(post("/api/projects/404/rename")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"任意名\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_usage_when_get_then_aggregations_returned() throws Exception {
        TokenUsage tokens = new TokenUsage(100, 200, 30, 0, 0);
        when(queryAppService.usage(100L)).thenReturn(new ProjectUsageResponse("100", tokens,
                Map.of("USD", new BigDecimal("0.003")),
                List.of(new ProjectUsageResponse.UnpricedUsage("testprov", "m-none",
                        TokenKind.INPUT, TokenKind.INPUT.getName())),
                List.of(new ProjectUsageResponse.ModelUsage("deepseek", "deepseek-v4-pro",
                        tokens)),
                List.of(new ProjectUsageResponse.AgentKindUsage("coder", "编码智能体",
                        tokens))));

        performAsUser(get("/api/projects/100/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value("100"))
                .andExpect(jsonPath("$.data.total.input").value(100))
                // 平台成本：币种分桶（键 = 币种码）
                .andExpect(jsonPath("$.data.cost.USD").value(0.003))
                // 未配价标注：档位 Integer code + 名称随附
                .andExpect(jsonPath("$.data.unpriced[0].tokenKind").value(1))
                .andExpect(jsonPath("$.data.unpriced[0].tokenKindName").value("输入"))
                .andExpect(jsonPath("$.data.byModel[0].model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.data.byAgentKind[0].agentKind").value("coder"))
                .andExpect(jsonPath("$.data.byAgentKind[0].agentKindLabel").value("编码智能体"));
    }

    @Test
    void given_workspace_when_source_package_then_binary_file_returned() throws Exception {
        byte[] bytes = {0x1f, (byte) 0x8b, 0x08, 0x00, 0x74, 0x61, 0x72};
        when(appService.sourcePackage(100L)).thenReturn(bytes);

        byte[] body = performAsUser(get("/api/projects/100/source-package"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/gzip"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(body).containsExactly(bytes); // 真实文件字节（不走 JSON 信封）
    }

    @Test
    void given_project_when_preview_then_url_returned() throws Exception {
        when(appService.preview(100L)).thenReturn(new ProjectPreviewResponse(
                "http://localhost:30080"));

        performAsUser(get("/api/projects/100/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("http://localhost:30080"));
    }

    @Test
    void given_prd_in_workspace_when_get_prd_then_content_and_updated_at_returned() throws Exception {
        when(queryAppService.prd(100L)).thenReturn(new PrdResponse("100",
                "# 官网 PRD\n\n目标：三页官网。\n", Instant.ofEpochSecond(1756100000)));

        performAsUser(get("/api/projects/100/prd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value("100"))
                .andExpect(jsonPath("$.data.content").value("# 官网 PRD\n\n目标：三页官网。\n"))
                // Instant → ISO-8601（Jackson2ObjectMapperBuilder 缺省关时间戳）
                .andExpect(jsonPath("$.data.updatedAt")
                        .value(Instant.ofEpochSecond(1756100000).toString()));
    }

    @Test
    void given_prd_not_produced_when_get_prd_then_prj_015_as_404() throws Exception {
        // 「未产出」口径：404 PRJ_015（区别于项目不存在的 PRJ_001）——前端区分「还没产出」
        when(queryAppService.prd(100L))
                .thenThrow(new ApplicationException(ProjectMessage.PRD_NOT_PRODUCED));

        performAsUser(get("/api/projects/100/prd"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("PRD 尚未产出"));
    }

    // ---------- 指令区发言 / 问答卡作答（#19 需求环①） ----------

    @Test
    void given_message_when_post_then_run_id_returned() throws Exception {
        when(baInterviewAppService.runInterviewTurn(100L, "目标用户主要是海外客户"))
                .thenReturn(new BaInterviewAppService.InterviewRun("run-9"));

        performAsUser(post("/api/projects/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"目标用户主要是海外客户\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.runId").value("run-9"));
    }

    @Test
    void given_blank_or_oversized_message_when_post_then_rejected_as_400() throws Exception {
        performAsUser(post("/api/projects/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest());
        performAsUser(post("/api/projects/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "长".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
        verify(baInterviewAppService, never()).runInterviewTurn(any(), any());
    }

    @Test
    void given_archived_project_when_post_message_then_prj_013_as_409() throws Exception {
        // 归档即指令区关闭（只读终态）
        when(baInterviewAppService.runInterviewTurn(100L, "再改改"))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED));

        performAsUser(post("/api/projects/100/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"再改改\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("项目已归档（归档是单向终点）"));
    }

    @Test
    void given_unknown_project_when_post_message_then_prj_001_as_404() throws Exception {
        when(baInterviewAppService.runInterviewTurn(404L, "你好"))
                .thenThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(post("/api/projects/404/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"你好\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_question_answer_when_answer_then_ok_and_facts_passed() throws Exception {
        // qid（路径）= 挂起帧 engineRef；请求体回传 runId + data.toolCalls 原样 + 答复
        performAsUser(post("/api/projects/100/questions/reply-7/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"run-9",
                                 "toolCalls":[{"id":"tc-1","name":"ask_user",
                                               "input":{"question":"目标用户是谁？"}}],
                                 "answer":"海外企业客户"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(baInterviewAppService).answerQuestion(100L, "run-9", "reply-7",
                List.of(Map.of("id", "tc-1", "name", "ask_user",
                        "input", Map.of("question", "目标用户是谁？"))),
                "海外企业客户");
    }

    @Test
    void given_blank_answer_when_answer_then_rejected_as_400() throws Exception {
        performAsUser(post("/api/projects/100/questions/reply-7/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"run-9","toolCalls":[{"id":"tc-1","name":"ask_user"}],
                                 "answer":" "}
                                """))
                .andExpect(status().isBadRequest());
        performAsUser(post("/api/projects/100/questions/reply-7/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"run-9","toolCalls":[],"answer":"有"}
                                """))
                .andExpect(status().isBadRequest());
        verify(baInterviewAppService, never()).answerQuestion(any(), any(), any(), any(), any());
    }

    @Test
    void given_archived_or_unknown_project_when_answer_then_mapped() throws Exception {
        doThrow(new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED))
                .when(baInterviewAppService).answerQuestion(eq(100L), any(), any(), any(), any());

        performAsUser(post("/api/projects/100/questions/reply-7/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"run-9","toolCalls":[{"id":"tc-1","name":"ask_user"}],
                                 "answer":"企业客户"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("项目已归档（归档是单向终点）"));

        doThrow(new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND))
                .when(baInterviewAppService).answerQuestion(eq(404L), any(), any(), any(), any());
        performAsUser(post("/api/projects/404/questions/reply-7/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"run-9","toolCalls":[{"id":"tc-1","name":"ask_user"}],
                                 "answer":"企业客户"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_prd_produced_when_detail_then_prd_produced_at_returned() throws Exception {
        // 成果区长出判据透出：闲聊期 null（指令区占满全宽），产出后有时点
        when(queryAppService.detail(100L)).thenReturn(
                detailOf("100", ProjectStatus.IN_PROGRESS, false,
                        LocalDateTime.of(2026, 8, 31, 9, 0)));

        performAsUser(get("/api/projects/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prdProducedAt").value("2026-08-31T09:00:00"));

        when(queryAppService.detail(101L)).thenReturn(
                detailOf("101", ProjectStatus.IN_PROGRESS, false, null));
        performAsUser(get("/api/projects/101"))
                .andExpect(jsonPath("$.data.prdProducedAt").value((Object) null));
    }

    // ---------- 夹具 ----------

    /** 详情夹具（列表字段全量的最小可用形态；prdProducedAt/generatedAt 缺省未产出）。 */
    private ProjectDetailResponse detailOf(String id, ProjectStatus status, boolean archived) {
        return detailOf(id, status, archived, null);
    }

    private ProjectDetailResponse detailOf(String id, ProjectStatus status, boolean archived,
            LocalDateTime prdProducedAt) {
        return new ProjectDetailResponse(id, "官网 demo", ProjectType.WEBSITE, "官网",
                "900", status, status.getName(), archived,
                LocalDateTime.of(2026, 8, 22, 10, 0), null, prdProducedAt, null);
    }

    /**
     * MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器
     * （BaseEnum Jackson 序列化经类级 @Import 引入，对齐生产序列化行为）。
     */
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    /**
     * query param 的 BaseEnum 按 code 绑定走 CartisanWebAutoConfiguration
     * 注册的 converter factory（切片不含该 autoconfig，此处对齐注册——
     * status=1 → ProjectStatusFilter.ACTIVE）。
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class EnumParamBindingConfig implements WebMvcConfigurer {

        @Override
        public void addFormatters(FormatterRegistry registry) {
            registry.addConverterFactory(new BaseEnumConverter());
        }
    }
}
