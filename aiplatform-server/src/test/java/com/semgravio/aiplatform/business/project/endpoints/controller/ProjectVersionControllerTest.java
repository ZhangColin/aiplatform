package com.semgravio.aiplatform.business.project.endpoints.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.web.exception.GlobalExceptionHandler;

import com.semgravio.aiplatform.business.project.application.ProjectVersionAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.VersionRollbackStartResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 版本 REST 面（swagger 契约验收，#626/#627）：ApiResponse 信封、版本列表（新→旧）、
 * 回到某版（异步受理）；PRJ_001（项目不存在）与 PRJ_028（版本不存在）→ 404。
 * 「查看当时」起/停快照端点随 #628 快照容器拆除退役。
 */
@WebMvcTest(ProjectVersionController.class)
@Import({ProjectVersionControllerTest.ExceptionAdviceConfig.class,
        com.cartisan.web.config.JacksonConfiguration.class})
class ProjectVersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectVersionAppService versionAppService;

    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "project-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_two_versions_when_list_then_newest_first_wrapped() throws Exception {
        when(versionAppService.list(any())).thenReturn(List.of(
                new VersionResponse("c2c2c2c2", "把按钮改成蓝色", 2, "222", null,
                        LocalDateTime.of(2026, 9, 5, 12, 0), true, false),
                new VersionResponse("a1a1a1a1", "首次生成了系统", 1, "111", null,
                        LocalDateTime.of(2026, 9, 5, 11, 0), false, true)));

        performAsUser(get("/api/projects/100/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].commitHash").value("c2c2c2c2"))
                .andExpect(jsonPath("$.data[0].versionNo").value(2))
                .andExpect(jsonPath("$.data[0].current").value(true))
                .andExpect(jsonPath("$.data[0].runId").value("222"))
                .andExpect(jsonPath("$.data[1].commitHash").value("a1a1a1a1"))
                .andExpect(jsonPath("$.data[1].confirmed").value(true));
    }

    @Test
    void given_missing_project_when_list_then_404() throws Exception {
        when(versionAppService.list(any())).thenThrow(
                new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(get("/api/projects/-1/versions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_valid_ref_when_rollback_then_run_id_and_label_wrapped() throws Exception {
        when(versionAppService.rollback(any(), anyString())).thenReturn(
                new VersionRollbackStartResponse("run-627", "回到 v1"));

        performAsUser(post("/api/projects/100/versions/a1a1a1a1/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-627"))
                .andExpect(jsonPath("$.data.label").value("回到 v1"));
    }

    @Test
    void given_unknown_ref_when_rollback_then_404() throws Exception {
        doThrow(new ApplicationException(ProjectMessage.VERSION_NOT_FOUND))
                .when(versionAppService).rollback(any(), anyString());

        performAsUser(post("/api/projects/100/versions/beefbeef/rollback"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(4028))
                .andExpect(jsonPath("$.message").value("版本不存在"));
    }

    @Test
    void given_run_in_flight_when_rollback_then_409() throws Exception {
        doThrow(new ApplicationException(ProjectMessage.VERSION_ROLLBACK_RUN_IN_FLIGHT))
                .when(versionAppService).rollback(any(), anyString());

        performAsUser(post("/api/projects/100/versions/a1a1a1a1/rollback"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4031))
                .andExpect(jsonPath("$.message").value("系统正在更新，请稍后再回滚"));
    }

    @Test
    void given_archived_project_when_rollback_then_409() throws Exception {
        doThrow(new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED))
                .when(versionAppService).rollback(any(), anyString());

        performAsUser(post("/api/projects/100/versions/a1a1a1a1/rollback"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(4013));
    }

    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
