package com.semgravio.aiplatform.business.billing.application;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.business.billing.application.dto.response.AccountBillingView;
import com.semgravio.aiplatform.business.billing.application.dto.response.BillingEntryView;
import com.semgravio.aiplatform.business.billing.application.dto.response.ProjectBillingView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账单读面积成测试（#534 计费④「我的账户」）：账户视图（无池零态不造假池＋
 * 流水新先序＋种类中文名随行）与项目视图（计费态投影＋充值目标推导三态——
 * 源未选/项目源＝PROJECT、账户源＝ACCOUNT）。
 */
@IntegrationTest
class BillingQueryAppServiceTest {

    private static final long PROJECT_ID = 923_001L;
    private static final long ACCOUNT_ID = 923_101L;

    @Autowired
    private BillingQueryAppService queryAppService;

    @Autowired
    private PoolTopupAppService poolTopupAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
    }

    @Test
    void given_never_topped_up_when_account_view_then_null_pool_and_empty_entries() {
        AccountBillingView view = queryAppService.accountView(ACCOUNT_ID);

        assertThat(view.accountPool()).isNull(); // 零态＝无池，不造假零余额池
        assertThat(view.entries()).isEmpty();
    }

    @Test
    void given_account_pool_when_account_view_then_balance_and_newest_first_entries() {
        poolTopupAppService.topup(ACCOUNT_ID, null, 3_000, null, "第一笔");
        poolTopupAppService.topup(ACCOUNT_ID, null, 2_000, null, "第二笔");

        AccountBillingView view = queryAppService.accountView(ACCOUNT_ID);

        assertThat(view.accountPool().balance()).isEqualTo(5_000L);
        List<BillingEntryView> entries = view.entries();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).amount()).isEqualTo(2_000L); // 新先序
        assertThat(entries.get(0).balanceAfter()).isEqualTo(5_000L);
        assertThat(entries.get(0).kind()).isEqualTo(1);
        assertThat(entries.get(0).kindName()).isEqualTo("入账");
        assertThat(entries.get(0).note()).isEqualTo("第二笔");
        assertThat(entries.get(1).amount()).isEqualTo(3_000L);
        assertThat(entries.get(1).balanceAfter()).isEqualTo(3_000L);
    }

    @Test
    void given_trial_project_when_project_view_then_target_project_and_unselected() {
        ProjectBillingView view = queryAppService.projectView(PROJECT_ID);

        assertThat(view.pool()).isNull();
        assertThat(view.sourceSelected()).isFalse();
        assertThat(view.deductionSource()).isNull();
        assertThat(view.paid()).isFalse();
        assertThat(view.checkoutTarget()).isEqualTo(2); // 充值入账即选定项目源
        assertThat(view.checkoutTargetName()).isEqualTo("项目余额");
        assertThat(view.entries()).isEmpty();
    }

    @Test
    void given_project_source_when_project_view_then_target_project_with_pool() {
        poolTopupAppService.topup(null, PROJECT_ID, 7_500, null, null);

        ProjectBillingView view = queryAppService.projectView(PROJECT_ID);

        assertThat(view.pool().balance()).isEqualTo(7_500L);
        assertThat(view.sourceSelected()).isTrue();
        assertThat(view.deductionSource()).isEqualTo(2);
        assertThat(view.deductionSourceName()).isEqualTo("项目余额");
        assertThat(view.paid()).isFalse();
        assertThat(view.checkoutTarget()).isEqualTo(2);
        assertThat(view.checkoutTargetName()).isEqualTo("项目余额");
        assertThat(view.entries()).hasSize(1);
    }

    @Test
    void given_account_source_when_project_view_then_target_account() {
        jdbcTemplate.update("INSERT INTO bil_project_billing (id, project_id, deduction_source,"
                + " source_account_id, source_selected_at, created_at, updated_at)"
                + " VALUES (?, ?, 1, ?, now(), now(), now())", 923_900L, PROJECT_ID, ACCOUNT_ID);

        ProjectBillingView view = queryAppService.projectView(PROJECT_ID);

        assertThat(view.sourceSelected()).isTrue();
        assertThat(view.deductionSourceName()).isEqualTo("账户余额");
        assertThat(view.checkoutTarget()).isEqualTo(1); // 引导充我的账户（非源池不呈现）
        assertThat(view.checkoutTargetName()).isEqualTo("账户余额");
        assertThat(view.entries()).isEmpty(); // 项目池未建（向账户池充值）
    }
}
