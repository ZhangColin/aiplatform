package com.aieducenter.aiplatform.business.project.endpoints.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

import com.aieducenter.aiplatform.business.project.application.ProjectVersionAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionResponse;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 版本 REST 面（swagger 契约验收，#91）：ApiResponse 信封、版本列表（新→旧）、
 * 版本详情（锚定收尾卡载荷）；PRJ_001（项目不存在）与 PRJ_028（版本不存在）
 * → 404。
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
                new VersionResponse("c2c2c2c2", "更新了系统", "222", LocalDateTime.of(2026, 9, 5, 12, 0)),
                new VersionResponse("a1a1a1a1", "首次生成了系统", "111", LocalDateTime.of(2026, 9, 5, 11, 0))));

        performAsUser(get("/api/projects/100/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].commitHash").value("c2c2c2c2"))
                .andExpect(jsonPath("$.data[0].runId").value("222"))
                .andExpect(jsonPath("$.data[1].commitHash").value("a1a1a1a1"));
    }

    @Test
    void given_version_with_closing_when_detail_then_anchored_payload() throws Exception {
        when(versionAppService.detail(any(), anyString())).thenReturn(
                new VersionDetailResponse("a1a1a1a1", "更新了系统", "222",
                        LocalDateTime.of(2026, 9, 5, 12, 0),
                        Map.of("summary", "更新了系统", "systemChanged", true)));

        performAsUser(get("/api/projects/100/versions/a1a1a1a1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commitHash").value("a1a1a1a1"))
                .andExpect(jsonPath("$.data.runId").value("222"))
                .andExpect(jsonPath("$.data.closing.summary").value("更新了系统"))
                .andExpect(jsonPath("$.data.closing.systemChanged").value(true));
    }

    @Test
    void given_missing_project_when_list_then_404() throws Exception {
        when(versionAppService.list(any())).thenThrow(
                new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));

        performAsUser(get("/api/projects/-1/versions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("项目不存在"));
    }

    @Test
    void given_unknown_ref_when_detail_then_404() throws Exception {
        doThrow(new ApplicationException(ProjectMessage.VERSION_NOT_FOUND))
                .when(versionAppService).detail(any(), anyString());

        performAsUser(get("/api/projects/100/versions/beefbeef"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("版本不存在"));
    }

    static class ExceptionAdviceConfig {

        @Bean
        public GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
