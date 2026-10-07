package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.CodeMessage;

import com.aieducenter.aiplatform.business.project.application.DesignProcessAppService;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.order.domain.error.OrderMessage;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设计稿 REST 面（#293 悬卡删除的 swagger 契约验收）：DELETE 动作＋path 查询参
 * （收尾卡 drafts/画布稿卡同形的工作区锚定形路径）；守卫矩阵的 HTTP 面（PRJ_020/
 * PRJ_050、ORD_006、归档）；缺 path 参数 400。纯动作无数据体（幂等成功即 ok）。
 */
@WebMvcTest(ProjectDesignDraftController.class)
@Import(com.cartisan.web.config.JacksonConfiguration.class)
class ProjectDesignDraftControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DesignProcessAppService designProcessAppService;

    /** 全 /api/** 拦截——MVC 契约测试不走登录链，夹具直接注 RequestContext。 */
    private ResultActions performAsUser(RequestBuilder request) throws Exception {
        return RequestContext.runFor(
                new RequestContext(null, null, null, null, 1L, "design-draft-test", null, null),
                () -> mockMvc.perform(request));
    }

    @Test
    void given_candidate_path_when_delete_then_ok_envelope() throws Exception {
        doNothing().when(designProcessAppService).deleteDesignDraft(eq(100L), eq("/design/a.html"));

        performAsUser(delete("/api/projects/100/design-drafts").param("path", "/design/a.html"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(designProcessAppService).deleteDesignDraft(eq(100L), eq("/design/a.html"));
    }

    @Test
    void given_missing_path_when_delete_then_bad_request() throws Exception {
        performAsUser(delete("/api/projects/100/design-drafts"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void given_guards_when_delete_then_error_envelopes() throws Exception {
        // 守卫矩阵的 HTTP 面（错误信封 code＝数字业务码）：非 design 锚定/不可浏览
        // 400 PRJ_020、定稿稿 409 PRJ_050、订单冻结 409 ORD_006、归档 409 PRJ_013
        assertGuard(ProjectMessage.FILE_PATH_INVALID, 400, 4020);
        assertGuard(ProjectMessage.DESIGN_DRAFT_FINALIZED, 409, 4050);
        assertGuard(OrderMessage.ORDER_FROZEN, 409, 5006);
        assertGuard(ProjectMessage.PROJECT_ALREADY_ARCHIVED, 409, 4013);
    }

    private void assertGuard(CodeMessage code, int httpStatus, int businessCode) throws Exception {
        doThrow(new ApplicationException(code))
                .when(designProcessAppService).deleteDesignDraft(eq(100L), eq("/design/a.html"));
        performAsUser(delete("/api/projects/100/design-drafts").param("path", "/design/a.html"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.code").value(businessCode));
    }
}
