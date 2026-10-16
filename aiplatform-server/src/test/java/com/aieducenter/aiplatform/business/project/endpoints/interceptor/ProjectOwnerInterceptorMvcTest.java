package com.aieducenter.aiplatform.business.project.endpoints.interceptor;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.web.config.JacksonConfiguration;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.aieducenter.aiplatform.business.project.application.ConversationHistoryAppService;
import com.aieducenter.aiplatform.business.project.application.DispatchAppService;
import com.aieducenter.aiplatform.business.project.application.GenerationAppService;
import com.aieducenter.aiplatform.business.project.application.IterationAppService;
import com.aieducenter.aiplatform.business.project.application.MainAgentAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponseFixture;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;
import com.aieducenter.aiplatform.business.project.endpoints.controller.ProjectController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 项目 owner 守卫拦截器（#325「单项目操作/详情按 owner 校验、404 不泄露存在性」
 * 的 REST 契约面）：{@code /api/projects/{id}/**} 全形态单项目寻址在进控制器前
 * 过归属判定——他人项目与不存在项目同一 404 PRJ_001 信封（不可分辨）；集合端点
 * （建项目/列表）不进守卫；畸形标识交由控制器寻址口径（Tsid.resolve 404）。
 * 归属谓词＝{@link Project#ownedBy}（无账户上下文的「无主对无主」容忍面同源）。
 */
@WebMvcTest(ProjectController.class)
@Import({ProjectOwnerInterceptorMvcTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class ProjectOwnerInterceptorMvcTest {

    /** 本夹具订阅者账户（RequestContext 直注，同 ProjectControllerTest 姿势）。 */
    private static final long VIEWER = 1L;
    private static final long OTHER = 2L;
    private static final long PROJECT_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    /** 守卫的归属取数缝（ObjectProvider 进 WebMvcConfig——切片内以 mock 装配）。 */
    @MockitoBean
    private ProjectRepository projectRepository;

    @MockitoBean
    private ProjectLifecycleAppService appService;

    @MockitoBean
    private ProjectQueryAppService queryAppService;

    @MockitoBean
    private MainAgentAppService mainAgentAppService;

    @MockitoBean
    private DispatchAppService dispatchAppService;

    @MockitoBean
    private GenerationAppService generationAppService;

    @MockitoBean
    private IterationAppService iterationAppService;

    @MockitoBean
    private ConversationHistoryAppService conversationHistoryAppService;

    /** 全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, VIEWER, "owner-test", null, null),
                () -> mockMvc.perform(request));
    }

    private void givenProjectOwnedBy(Long ownerAccountId) {
        when(projectRepository.findById(PROJECT_ID))
                .thenReturn(Optional.of(Project.create("官网 demo", null, 900L, ownerAccountId)));
    }

    // ---------- 单项目寻址：归属守卫（404 不泄露存在性） ----------

    @Test
    void given_foreign_project_when_detail_then_prj_001_and_handler_untouched() throws Exception {
        givenProjectOwnedBy(OTHER);

        performAsUser(get("/api/projects/" + PROJECT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("项目不存在"));

        verify(queryAppService, never()).detail(anyLong());
    }

    @Test
    void given_own_project_when_detail_then_passed_through() throws Exception {
        givenProjectOwnedBy(VIEWER);
        when(queryAppService.detail(PROJECT_ID)).thenReturn(
                ProjectDetailResponseFixture.detailOf(Long.toString(PROJECT_ID), "官网 demo").build());

        performAsUser(get("/api/projects/" + PROJECT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(Long.toString(PROJECT_ID)));

        verify(queryAppService).detail(PROJECT_ID);
    }

    @Test
    void given_missing_and_foreign_projects_when_detail_then_identical_envelope()
            throws Exception {
        // 不泄露存在性：不存在（库中无行）与存在但他人所有——同一 404 信封逐字节一致，
        // 调用方不可分辨（区别只在守卫内部查得到与否，不进响应面）
        when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());
        String missing = performAsUser(get("/api/projects/" + PROJECT_ID))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        givenProjectOwnedBy(OTHER);
        String foreign = performAsUser(get("/api/projects/" + PROJECT_ID))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(foreign).isEqualTo(missing);
    }

    @Test
    void given_subresource_path_when_foreign_then_guarded_before_handler() throws Exception {
        // 单项目操作全形态在守卫面内（消息/生成/归档/版本/物料/设计件……路径形态
        // 与触碰拦截器同源）——此处以对话史读口为代表钉「先守卫后控制器」
        givenProjectOwnedBy(OTHER);

        performAsUser(get("/api/projects/" + PROJECT_ID + "/conversation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001));

        verify(conversationHistoryAppService, never()).read(anyLong());
    }

    @Test
    void given_oversized_numeric_id_when_detail_then_prj_001() throws Exception {
        // 超 Long 上限的数字路径段：解析不成立即「不存在的标识」——与 Tsid.resolve
        // 寻址口径同归宿（404），不进查库
        performAsUser(get("/api/projects/99999999999999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001));

        verify(projectRepository, never()).findById(anyLong());
    }

    // ---------- 集合端点不进守卫 ----------

    @Test
    void given_collection_endpoints_when_called_then_no_ownership_lookup() throws Exception {
        when(queryAppService.list(any())).thenReturn(List.of());

        performAsUser(get("/api/projects")).andExpect(status().isOk());
        performAsUser(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"做一个官网\"}"))
                .andExpect(status().isOk());

        // 建项目/列表无 id 段——归属判定不适用（列表过滤归查询服务、建项目随会话落归属）
        verify(projectRepository, never()).findById(anyLong());
        verify(queryAppService).list(any());
    }

    /**
     * MVC 切片不含 cartisan-web autoconfig，手动注册其全局异常处理器（守卫抛的
     * ApplicationException 经此映射 404 信封——对齐生产序列化行为）。
     */
    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
