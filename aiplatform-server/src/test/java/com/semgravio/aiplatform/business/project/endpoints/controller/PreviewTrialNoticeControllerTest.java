package com.semgravio.aiplatform.business.project.endpoints.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.semgravio.aiplatform.business.project.application.PreviewTrialNoticeAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.TrialNoticeResponse;

/**
 * 试用体验提示端点契约（#539 访谈④）：{@code /.aiplatform/trial-notice} 裸前缀
 * （有意落在 /api/** 拦截面外——试用预览的访客无会话，提示脚本必须匿名可达）、
 * 只读 GET、workspaceId 经 X-Preview-Workspace 头（网关子域捕获注入、覆写客户端
 * 同名字头）。信封按统一 ApiResponse，no-store（翻转熄灭不得被缓存压住）。
 */
@WebMvcTest(PreviewTrialNoticeController.class)
class PreviewTrialNoticeControllerTest {

    private static final String ENDPOINT = "/.aiplatform/trial-notice";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreviewTrialNoticeAppService noticeAppService;

    @Test
    void given_workspace_header_when_notice_then_trial_and_no_store() throws Exception {
        when(noticeAppService.trialNotice(7701L))
                .thenReturn(new TrialNoticeResponse(true));

        mockMvc.perform(get(ENDPOINT).header("X-Preview-Workspace", "7701"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trial").value(true))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(noticeAppService).trialNotice(7701L);
    }

    @Test
    void given_paid_project_when_notice_then_trial_false() throws Exception {
        when(noticeAppService.trialNotice(7702L))
                .thenReturn(new TrialNoticeResponse(false));

        mockMvc.perform(get(ENDPOINT).header("X-Preview-Workspace", "7702"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trial").value(false));
    }

    @Test
    void given_missing_header_when_notice_then_null_workspace_and_trial_false() throws Exception {
        // 头缺席（直打后端）如实不提示、不炸场
        when(noticeAppService.trialNotice(null))
                .thenReturn(new TrialNoticeResponse(false));

        mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trial").value(false));

        verify(noticeAppService).trialNotice(null);
    }

    @Test
    void given_malformed_header_when_notice_then_trial_false_not_500() throws Exception {
        // 头畸形（非数值）不 500——提示面永不阻断预览页
        when(noticeAppService.trialNotice(null))
                .thenReturn(new TrialNoticeResponse(false));

        mockMvc.perform(get(ENDPOINT).header("X-Preview-Workspace", "not-a-number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trial").value(false));

        verify(noticeAppService).trialNotice(null);
    }
}
