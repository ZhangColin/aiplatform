package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.business.project.application.DesignProcessAppService;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设计物 REST 面（#291 定稿机制的 swagger 契约验收）：ApiResponse 信封＋定稿锚
 * 与成版 hash 双字段；守卫矩阵的 HTTP 面（PRJ_046/047/048/049/020、ORD_006、
 * 归档）；路径必填校验。
 */
@WebMvcTest(ProjectDesignItemController.class)
@Import(com.cartisan.web.config.JacksonConfiguration.class)
class ProjectDesignItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DesignProcessAppService designProcessAppService;

    /** 全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "design-item-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_finalization_when_finalize_then_run_id_and_version_hash_returned() throws Exception {
        when(designProcessAppService.finalizeDesignItem(eq(100L), eq(2), eq("/design/home-2.html")))
                .thenReturn(new DesignProcessAppService.DesignFinalization("run-91", "abc123"));

        performAsUser(post("/api/projects/100/design-items/2/finalize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\": \"/design/home-2.html\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-91"))
                .andExpect(jsonPath("$.data.versionHash").value("abc123"));
    }

    @Test
    void given_unversioned_finalization_when_finalize_then_null_version_hash() throws Exception {
        // 成版失败不反噬定稿：versionHash 缺省（null）——收尾卡同口径缺 version 键
        when(designProcessAppService.finalizeDesignItem(eq(100L), eq(1), eq("/design/a.html")))
                .thenReturn(new DesignProcessAppService.DesignFinalization("run-92", null));

        performAsUser(post("/api/projects/100/design-items/1/finalize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\": \"/design/a.html\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-92"))
                .andExpect(jsonPath("$.data.versionHash").doesNotExist());
    }

    @Test
    void given_blank_path_when_finalize_then_bad_request() throws Exception {
        performAsUser(post("/api/projects/100/design-items/1/finalize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_guards_when_finalize_then_error_envelopes() throws Exception {
        // 守卫矩阵的 HTTP 面（错误信封 code＝数字业务码）：轨道在途 409 PRJ_046、
        // 件不存在 404 PRJ_047、件未产出稿 409 PRJ_048、稿不在 404 PRJ_049、路径
        // 不可浏览 400 PRJ_020、订单冻结 409 ORD_006、归档 409 PRJ_013
        assertGuard(ProjectMessage.DESIGN_FINALIZE_IN_FLIGHT, 409, 4046);
        assertGuard(ProjectMessage.DESIGN_ITEM_NOT_FOUND, 404, 4047);
        assertGuard(ProjectMessage.DESIGN_ITEM_NOT_READY, 409, 4048);
        assertGuard(ProjectMessage.DESIGN_DRAFT_NOT_FOUND, 404, 4049);
        assertGuard(ProjectMessage.FILE_PATH_INVALID, 400, 4020);
        assertGuard(OrderMessage.ORDER_FROZEN, 409, 5006);
        assertGuard(ProjectMessage.PROJECT_ALREADY_ARCHIVED, 409, 4013);
    }

    private void assertGuard(com.cartisan.core.exception.CodeMessage code, int httpStatus,
            int businessCode) throws Exception {
        when(designProcessAppService.finalizeDesignItem(eq(100L), eq(1), eq("/design/a.html")))
                .thenThrow(new ApplicationException(code));
        performAsUser(post("/api/projects/100/design-items/1/finalize")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\": \"/design/a.html\"}"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(businessCode));
    }
}
