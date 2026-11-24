package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.PoolTopupAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.FlipGate;
import com.semgravio.aiplatform.business.project.application.dto.response.TrialNoticeResponse;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 试用体验提示读面（#539 访谈④，ADR-0047 判定单读点消费面）：预览网关注入脚本经
 * 网关反代打本口（workspaceId 头来自网关子域捕获）——试用态（行缺席／源选定未
 * 翻转）恒 true；付费翻转后 false（提示熄灭）；workspaceId 无对应项目如实 false
 * （不炸场、不出提示——预览子域本就只应存在于真实工作区）。
 */
@IntegrationTest
class PreviewTrialNoticeAppServiceTest {

    private static final long OWNER = 3897654321098765432L;

    @Autowired
    private PreviewTrialNoticeAppService noticeAppService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private BillingAppService billingAppService;

    @Autowired
    private PoolTopupAppService poolTopupAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM prj_projects");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
        jdbcTemplate.update("DELETE FROM bil_entries");
    }

    @Test
    void given_trial_project_without_billing_row_when_notice_then_trial_true() {
        Long workspaceId = 9701L;
        projectRepository.save(Project.create("试用项目", null, workspaceId, OWNER, null));

        TrialNoticeResponse notice = noticeAppService.trialNotice(workspaceId);

        assertThat(notice.trial()).isTrue();
    }

    @Test
    void given_source_selected_but_unflipped_when_notice_then_trial_true() {
        // 源选定≠付费：未翻转仍试用（提示不熄灭——钱的承诺事实未落）
        Long workspaceId = 9702L;
        Long projectId = projectRepository
                .save(Project.create("已选源未翻转", null, workspaceId, OWNER, null))
                .getId();
        poolTopupAppService.topup(null, projectId, 1, null, null);

        assertThat(billingAppService.trackOf(projectId).paid()).isFalse();
        assertThat(noticeAppService.trialNotice(workspaceId).trial()).isTrue();
    }

    @Test
    void given_flipped_project_when_notice_then_trial_false() {
        // 付费翻转即熄灭（判定单源＝bil_project_billing.flipped_at）
        Long workspaceId = 9703L;
        Long projectId = projectRepository
                .save(Project.create("已翻转项目", null, workspaceId, OWNER, null))
                .getId();
        poolTopupAppService.topup(null, projectId, 1, null, null);
        billingAppService.flipToPaid(projectId, FlipGate.INTERCEPT);

        assertThat(noticeAppService.trialNotice(workspaceId).trial()).isFalse();
    }

    @Test
    void given_unknown_workspace_when_notice_then_trial_false() {
        assertThat(noticeAppService.trialNotice(9999001L).trial()).isFalse();
    }

    @Test
    void given_null_workspace_when_notice_then_trial_false() {
        // 头缺席/畸形（直打后端、不经网关）如实不提示，不炸场
        assertThat(noticeAppService.trialNotice(null).trial()).isFalse();
    }
}
