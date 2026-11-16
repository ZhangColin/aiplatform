package com.semgravio.aiplatform.business.billing.endpoints.controller;

import java.math.BigDecimal;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.semgravio.aiplatform.backoffice.BackofficeSeamTest;
import com.semgravio.aiplatform.backoffice.BackofficeSignatures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台账户运营端点在 {@code #152} seam 上全绿（#535 计费⑤）：真过滤链（五头
 * HMAC 签名闸）＋真应用服务＋aiplatform_test 真库。夹具账户/项目用独立 id 段
 * （936xxx），与他类测试互不沾。
 *
 * <p>钉死面：</p>
 * <ul>
 * <li><b>签名负例</b>：无签名/错 secret → 401（既有签名基建回归，本类照
 * {@code BackofficeSeamContractTest} 口径在计费面复验一处）；</li>
 * <li><b>赠送</b>：入账＋流水＋动作留痕三行同事务落库（操作者透传头＋变更前后
 * 余额快照）；目标不恰一 400 BILL_001；</li>
 * <li><b>手动充值</b>：凭证三字段必填（缺失 400 BILL_021 零副作用）；核验字段
 * 随 detail JSONB 落痕（{@code ->>} 真库断言）；</li>
 * <li><b>余额调整</b>：池缺席 404 BILL_022；带符号调整行＋动作行同落；</li>
 * <li><b>扣减源调整</b>：计费态行覆盖＋动作行旧/新源快照（{@code ->>} 断言）；</li>
 * <li><b>参数调整</b>：bil_params 值与操作者两列落库。</li>
 * </ul>
 */
@BackofficeSeamTest
class BackofficeBillingSeamTest {

    private static final long PROJECT_ID = 936_001L;
    private static final long ACCOUNT_ID = 936_101L;

    /** 操作者测试身份：admin 侧管理员的 TSID＋昵称样例（同订单 seam 口径）。 */
    private static final String OPERATOR_ID = "700535";
    private static final String OPERATOR_NAME = "运营·账务";

    private static final String GRANT_PATH = "/api/backoffice/billing/grant";
    private static final String MANUAL_PATH = "/api/backoffice/billing/manual-topups";
    private static final String ADJUST_PATH = "/api/backoffice/billing/adjustments";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM bil_operator_actions");
        jdbcTemplate.update("DELETE FROM bil_entries");
        jdbcTemplate.update("DELETE FROM bil_project_billing");
        jdbcTemplate.update("DELETE FROM bil_pools");
        jdbcTemplate.update("UPDATE bil_params SET param_value = 1, operator_id = NULL,"
                + " operator_name = NULL WHERE param_key = 'conversion_rate'");
    }

    // ---------- 签名负例（既有签名基建回归） ----------

    @Test
    void given_no_or_wrong_signature_when_backoffice_billing_then_401() throws Exception {
        // 全裸请求：落到类级 @RequireSignature 强制闸
        mockMvc.perform(post(GRANT_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + ACCOUNT_ID + ",\"amountFen\":1000}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature required"));
        // 错 secret：验签 Filter 介入出 mismatch
        String body = "{\"accountId\":" + ACCOUNT_ID + ",\"amountFen\":1000}";
        mockMvc.perform(BackofficeSignatures.apply(
                        post(GRANT_PATH).contentType(MediaType.APPLICATION_JSON).content(body),
                        BackofficeSignatures.headers(GRANT_PATH, body, "wrong-secret")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Signature mismatch"));
        // 负例零副作用
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_pools", Integer.class))
                .isZero();
    }

    // ---------- 赠送 ----------

    @Test
    void given_signed_grant_when_project_target_then_three_rows_same_transaction() throws Exception {
        String body = "{\"projectId\":" + PROJECT_ID + ",\"amountFen\":10000,"
                + "\"note\":\"演示赠送\"}";

        mockMvc.perform(signed(post(GRANT_PATH), GRANT_PATH, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.actionType").value(1))
                .andExpect(jsonPath("$.data.actionTypeName").value("赠送"))
                .andExpect(jsonPath("$.data.balanceAfter").value(10000))
                .andExpect(jsonPath("$.data.entryId").isNotEmpty())
                .andExpect(jsonPath("$.data.poolId").isNotEmpty());

        // 库内事实：动作留痕行（操作者＋前后快照＋项目锚）＋流水行＋动作即选源
        Map<String, Object> action = jdbcTemplate.queryForMap(
                "SELECT action_type, amount, balance_before, balance_after, pool_id,"
                        + " operator_id, operator_name FROM bil_operator_actions"
                        + " WHERE project_id = ?", PROJECT_ID);
        assertThat(action.get("action_type")).isEqualTo(1);
        assertThat(action.get("amount")).isEqualTo(10_000L);
        assertThat(action.get("balance_before")).isEqualTo(0L);
        assertThat(action.get("balance_after")).isEqualTo(10_000L);
        assertThat(action.get("operator_id")).isEqualTo(OPERATOR_ID);
        assertThat(action.get("operator_name")).isEqualTo(OPERATOR_NAME);
        Map<String, Object> billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_operator_id FROM bil_project_billing"
                        + " WHERE project_id = ?", PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(2); // 动作即选择
        assertThat(billing.get("source_operator_id")).isEqualTo(OPERATOR_ID);
        Integer entries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_entries WHERE pool_id = ? AND kind = 1",
                Integer.class, action.get("pool_id"));
        assertThat(entries).isEqualTo(1);
    }

    @Test
    void given_grant_target_ambiguous_then_400_and_nothing_written() throws Exception {
        String both = "{\"accountId\":" + ACCOUNT_ID + ",\"projectId\":" + PROJECT_ID
                + ",\"amountFen\":1000}";
        mockMvc.perform(signed(post(GRANT_PATH), GRANT_PATH, both))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("入账目标非法（账户与项目恰选其一）"));
        String empty = "{\"amountFen\":1000}";
        mockMvc.perform(signed(post(GRANT_PATH), GRANT_PATH, empty))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_operator_actions",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_pools", Integer.class))
                .isZero();
    }

    // ---------- 手动充值（仿凭证核验留痕） ----------

    @Test
    void given_voucher_incomplete_when_manual_topup_then_400_and_nothing_written() throws Exception {
        // 缺依据／缺实到时间：400 BILL_021 必填即拒（实到金额缺失由类型绑定缺席处理）
        String noEvidence = "{\"accountId\":" + ACCOUNT_ID + ",\"receivedAmountFen\":500000,"
                + "\"receivedAt\":\"2026-11-15T09:30:00Z\"}";
        mockMvc.perform(signed(post(MANUAL_PATH), MANUAL_PATH, noEvidence))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("手动充值凭证字段不完整（实到金额/到账时间/依据必填）"));
        String noReceivedAt = "{\"accountId\":" + ACCOUNT_ID + ",\"receivedAmountFen\":500000,"
                + "\"evidence\":\"回单X\"}";
        mockMvc.perform(signed(post(MANUAL_PATH), MANUAL_PATH, noReceivedAt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("手动充值凭证字段不完整（实到金额/到账时间/依据必填）"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_operator_actions",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_pools", Integer.class))
                .isZero();
    }

    @Test
    void given_voucher_complete_when_manual_topup_then_action_detail_carries_voucher() throws Exception {
        String body = "{\"accountId\":" + ACCOUNT_ID + ",\"receivedAmountFen\":500000,"
                + "\"receivedAt\":\"2026-11-15T09:30:00Z\","
                + "\"evidence\":\"银行回单 HX20261115001\"}";

        mockMvc.perform(signed(post(MANUAL_PATH), MANUAL_PATH, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionType").value(2))
                .andExpect(jsonPath("$.data.actionTypeName").value("手动充值"))
                .andExpect(jsonPath("$.data.balanceAfter").value(500000));

        // 库内事实：凭证核验三字段随 detail JSONB 落痕（->> 真库断言）
        Map<String, Object> action = jdbcTemplate.queryForMap(
                "SELECT amount, balance_before, balance_after, operator_id,"
                        + " detail->>'receivedAt' AS received_at,"
                        + " detail->>'evidence' AS evidence FROM bil_operator_actions"
                        + " WHERE account_id = ?", ACCOUNT_ID);
        assertThat(action.get("amount")).isEqualTo(500_000L);
        assertThat(action.get("balance_before")).isEqualTo(0L);
        assertThat(action.get("balance_after")).isEqualTo(500_000L);
        assertThat(action.get("operator_id")).isEqualTo(OPERATOR_ID);
        assertThat(action.get("received_at")).isEqualTo("2026-11-15T09:30:00Z");
        assertThat(action.get("evidence")).isEqualTo("银行回单 HX20261115001");
        // 流水行同落（备注缺省措辞）
        String note = jdbcTemplate.queryForObject(
                "SELECT note FROM bil_entries WHERE project_id IS NULL AND kind = 1"
                        + " AND operator_id = ? LIMIT 1", String.class, OPERATOR_ID);
        assertThat(note).isEqualTo("对公转账手动充值");
    }

    // ---------- 余额调整 ----------

    @Test
    void given_no_pool_when_adjust_then_404_pool_not_found() throws Exception {
        String body = "{\"projectId\":" + PROJECT_ID + ",\"delta\":-1000,"
                + "\"reason\":\"平台故障返还\"}";
        mockMvc.perform(signed(post(ADJUST_PATH), ADJUST_PATH, body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("余额池不存在（目标账户/项目从未入账）"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM bil_operator_actions",
                Integer.class)).isZero();
    }

    @Test
    void given_existing_pool_when_adjust_then_signed_entry_and_action_persisted() throws Exception {
        // 夹具：先经赠送入账户池 100 元
        String grant = "{\"accountId\":" + ACCOUNT_ID + ",\"amountFen\":10000}";
        mockMvc.perform(signed(post(GRANT_PATH), GRANT_PATH, grant))
                .andExpect(status().isOk());

        // 平台故障返还：+50 元（带符号正调整）
        String refund = "{\"accountId\":" + ACCOUNT_ID + ",\"delta\":5000,"
                + "\"reason\":\"平台故障返还（#475 中断段）\"}";
        mockMvc.perform(signed(post(ADJUST_PATH), ADJUST_PATH, refund))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionType").value(3))
                .andExpect(jsonPath("$.data.actionTypeName").value("余额调整"))
                .andExpect(jsonPath("$.data.balanceAfter").value(15000));

        // 库内事实：调整流水行（kind=3 带符号）＋动作行前后快照
        Map<String, Object> action = jdbcTemplate.queryForMap(
                "SELECT action_type, amount, balance_before, balance_after FROM"
                        + " bil_operator_actions WHERE account_id = ? AND action_type = 3",
                ACCOUNT_ID);
        assertThat(action.get("amount")).isEqualTo(5_000L);
        assertThat(action.get("balance_before")).isEqualTo(10_000L);
        assertThat(action.get("balance_after")).isEqualTo(15_000L);
        Map<String, Object> entry = jdbcTemplate.queryForMap(
                "SELECT kind, amount, balance_after FROM bil_entries"
                        + " WHERE operator_id = ? AND kind = 3", OPERATOR_ID);
        assertThat(entry.get("kind")).isEqualTo(3);
        assertThat(entry.get("amount")).isEqualTo(5_000L);
        assertThat(entry.get("balance_after")).isEqualTo(15_000L);
    }

    // ---------- 扣减源调整 ----------

    @Test
    void given_trial_project_when_reselect_source_then_billing_updated_and_snapshot_action() throws Exception {
        String path = "/api/backoffice/billing/projects/" + PROJECT_ID + "/deduction-source";
        String body = "{\"source\":1,\"accountId\":" + ACCOUNT_ID + "}";

        mockMvc.perform(signed(post(path), path, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.actionType").value(4))
                .andExpect(jsonPath("$.data.actionTypeName").value("扣减源调整"))
                .andExpect(jsonPath("$.data.entryId").value(nullValue()))
                .andExpect(jsonPath("$.data.poolId").value(nullValue()))
                .andExpect(jsonPath("$.data.balanceAfter").value(nullValue()));

        // 库内事实：计费态行覆盖＋动作行旧/新源快照（旧源 NULL＝试用开局）
        Map<String, Object> billing = jdbcTemplate.queryForMap(
                "SELECT deduction_source, source_account_id FROM bil_project_billing"
                        + " WHERE project_id = ?", PROJECT_ID);
        assertThat(billing.get("deduction_source")).isEqualTo(1);
        assertThat(billing.get("source_account_id")).isEqualTo(ACCOUNT_ID);
        Map<String, Object> action = jdbcTemplate.queryForMap(
                "SELECT detail->>'oldSource' AS old_source, detail->>'newSource' AS new_source"
                        + " FROM bil_operator_actions WHERE project_id = ?"
                        + " AND action_type = 4", PROJECT_ID);
        assertThat(action.get("old_source")).isNull();
        assertThat(action.get("new_source")).isEqualTo("1");
    }

    @Test
    void given_source_code_missing_or_unknown_when_reselect_then_400_and_nothing_written()
            throws Exception {
        String path = "/api/backoffice/billing/projects/" + PROJECT_ID + "/deduction-source";
        // 源码缺失/未知：400 BILL_024（admin 输入面必填守卫——不落成 500）
        mockMvc.perform(signed(post(path), path, "{\"accountId\":" + ACCOUNT_ID + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("扣减源码无效（1=账户余额 2=项目余额）"));
        mockMvc.perform(signed(post(path), path,
                        "{\"source\":99,\"accountId\":" + ACCOUNT_ID + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("扣减源码无效（1=账户余额 2=项目余额）"));
        // 零副作用
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_project_billing", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bil_operator_actions", Integer.class)).isZero();
    }

    // ---------- 计费运营参数 ----------

    @Test
    void given_signed_param_adjust_then_value_and_operator_persisted() throws Exception {
        String path = "/api/backoffice/billing/params/conversion_rate";
        String body = "{\"value\":1.5}";

        mockMvc.perform(signed(post(path), path, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT param_value, operator_id FROM bil_params WHERE param_key = 'conversion_rate'");
        assertThat(((BigDecimal) row.get("param_value")))
                .isEqualByComparingTo("1.5");
        assertThat(row.get("operator_id")).isEqualTo(OPERATOR_ID);
    }

    // ---------- 夹具 ----------

    /** 签名 POST（body 非空即挂体＋JSON 类型）＋操作者透传头（留痕锚）。 */
    private MockHttpServletRequestBuilder signed(
            MockHttpServletRequestBuilder builder, String path, String body) {
        builder.contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(body);
        }
        return BackofficeSignatures.signed(builder, path, body)
                .header("X-User-Id", OPERATOR_ID)
                .header("X-User-Name", OPERATOR_NAME);
    }

    private static org.hamcrest.Matcher<Object> nullValue() {
        return Matchers.nullValue();
    }
}