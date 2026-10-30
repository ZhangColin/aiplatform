package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;
import com.semgravio.aiplatform.business.project.application.ProductionHandoffAppService;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台生产承接端点在 #152 seam 上全绿（#338）：真过滤链（五头 HMAC 签名闸/
 * 会话豁免/强制拦截器）＋真应用服务＋aiplatform_test 真库——承接详情 → 信息
 * 更新 → 推进 → 需用户补充置位全链经真签名 HTTP 驱动，留痕行与标志位落库以
 * JdbcTemplate 直查为证；无签名请求被闸拒（新控制器挂上签名面的活体证明）。
 * 承接夹具经开工判定建单口直落（系统触发面归 KickoffAppServiceTest）。
 */
@BackofficeSeamTest
class BackofficeProductionSeamTest {

    /** 每次换新工作区号（一项目一工作区——测试直建项目不撞真实工作区）。 */
    private static final AtomicLong WORKSPACE_SEQ = new AtomicLong(973600L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductionHandoffAppService handoffAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> projectIds = new java.util.ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long projectId : projectIds) {
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
    }

    @Test
    void given_handoff_when_detail_update_advance_flag_then_all_green_via_signed_http()
            throws Exception {
        Long projectId = projectRepository.save(Project.create("承接 seam 项目" + WORKSPACE_SEQ.get(),
                null, null, WORKSPACE_SEQ.incrementAndGet(), 1L, null)).getId();
        projectIds.add(projectId);
        handoffAppService.createForKickoff(projectId, 900600L);
        String base = "/api/backoffice/projects/" + projectId + "/production-handoff";

        // 详情：承接字段＋创建留痕行（系统操作者）
        mockMvc.perform(BackofficeSignatures.signed(get(base), base, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.contact").doesNotExist())
                .andExpect(jsonPath("$.data.transitions[0].action").value(1))
                .andExpect(jsonPath("$.data.transitions[0].operatorId").value("system"));

        // 信息更新：联系人/下一步/进展/预计开始时间整组落值（操作者为审计
        // 正身——带 X-User-Id/X-User-Name 透传头，缺头被 PRJ_059 拒）
        String updateBody = "{\"contact\":\"李经理（微信 ligoods）\",\"nextStep\":\"准备服务器环境与域名备案\","
                + "\"progressNote\":\"已完成技术方案拆解\",\"estimatedStartAt\":\"2026-10-20T09:00:00\","
                + "\"basis\":\"首次排期与联系人录入\"}";
        mockMvc.perform(BackofficeSignatures.signed(
                        post(base + "/update").contentType(MediaType.APPLICATION_JSON).content(updateBody),
                        base + "/update", updateBody)
                        .header("X-User-Id", "700100").header("X-User-Name", "运营·小刘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contact").value("李经理（微信 ligoods）"))
                .andExpect(jsonPath("$.data.estimatedStartAt").value("2026-10-20T09:00:00"));

        // 推进：待安排生产 → 生产中
        String advanceBody = "{\"basis\":\"排期落定，今日开工\"}";
        mockMvc.perform(BackofficeSignatures.signed(
                        post(base + "/advance").contentType(MediaType.APPLICATION_JSON).content(advanceBody),
                        base + "/advance", advanceBody)
                        .header("X-User-Id", "700100").header("X-User-Name", "运营·小刘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.statusName").value("生产中"));

        // 需用户补充置位：项目行标志落库
        String flagBody = "{\"required\":true}";
        mockMvc.perform(BackofficeSignatures.signed(
                        post(base + "/needs-user-supplement").contentType(MediaType.APPLICATION_JSON)
                                .content(flagBody),
                        base + "/needs-user-supplement", flagBody))
                .andExpect(status().isOk());

        // 库内定真：承接两态推进＋三行留痕 append-only＋标志位
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM prj_production_handoffs WHERE project_id = ?",
                Integer.class, projectId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM prj_production_handoff_transitions h"
                        + " JOIN prj_production_handoffs p ON p.id = h.handoff_id WHERE p.project_id = ?",
                Integer.class, projectId)).isEqualTo(3); // 创建＋信息更新＋推进
        assertThat(jdbcTemplate.queryForObject(
                "SELECT needs_user_supplement FROM prj_projects WHERE id = ?",
                Boolean.class, projectId)).isTrue();
    }

    @Test
    void given_unsigned_request_when_detail_then_rejected_by_signature_gate() throws Exception {
        Long projectId = projectRepository.save(Project.create("承接 seam 无签名项目",
                null, null, WORKSPACE_SEQ.incrementAndGet(), 1L, null)).getId();
        projectIds.add(projectId);

        mockMvc.perform(get("/api/backoffice/projects/" + projectId + "/production-handoff"))
                .andExpect(status().isUnauthorized());
    }
}
