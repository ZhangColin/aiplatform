package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.semgravio.aiplatform.IntegrationTest;
import com.semgravio.aiplatform.base.agentscope.ModelChannel;
import com.semgravio.aiplatform.business.project.application.ModelTierAppService.ResolvedModel;
import com.cartisan.core.exception.ApplicationException;

/**
 * Seam B（#365／spec #364 测试决议）：档位解析服务层——档位键 → 档位行 → 通道行 →
 * 模型串（前缀:实际模型名）＋创建上下文（baseUrl/apiKey/enableThinking）的解析
 * 单点。只经 {@link ModelTierAppService#resolve} 公共面观测（外部可观测行为＝解析
 * 结果与报错路径），不打真 API。fixture 用独立键（t- 前缀）不与 V44 种子行
 * （volcengine/flash——其他测试的真链路依赖）串场。
 */
@IntegrationTest
class ModelTierAppServiceTest {

    private static final String BASE_URL = "https://ark.example/api/v3";

    @Autowired
    private ModelTierAppService modelTiers;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanFixtures() {
        jdbcTemplate.update("DELETE FROM prj_model_tiers WHERE tier_key LIKE 't-%'");
        jdbcTemplate.update("DELETE FROM prj_model_providers WHERE provider_key LIKE 't-%'");
    }

    @Test
    void given_tier_row_and_enabled_channel_when_resolve_then_model_string_and_context() {
        seedChannel("t-ark", BASE_URL, "sk-ark-test", true);
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", true);

        ResolvedModel resolved = modelTiers.resolve("t-flash");

        assertThat(resolved.modelString()).isEqualTo("t-ark:deepseek-v4-flash-ga-260731");
        assertThat(resolved.baseUrl()).isEqualTo(BASE_URL);
        assertThat(resolved.apiKey()).isEqualTo("sk-ark-test");
        // 深度思考显式关（ADR-0031：方舟 DeepSeek V4 标深度思考，不传参默认开/关未确证
        // ——装配显式关，终值冒烟实测后定）
        assertThat(resolved.thinkingEnabled()).isFalse();
        // 命令构建消费面：base 层通道注入件（baseUrl/key/thinking）
        assertThat(resolved.channel())
                .isEqualTo(new ModelChannel(BASE_URL, "sk-ark-test", false));
    }

    @Test
    void given_missing_tier_when_resolve_then_explicit_error_no_invented_default() {
        seedChannel("t-ark", BASE_URL, "sk-ark-test", true);

        assertThatThrownBy(() -> modelTiers.resolve("t-nonexistent"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("t-nonexistent");
    }

    @Test
    void given_disabled_tier_when_resolve_then_explicit_error() {
        seedChannel("t-ark", BASE_URL, "sk-ark-test", true);
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", false);

        assertThatThrownBy(() -> modelTiers.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void given_disabled_provider_when_resolve_then_explicit_error_no_silent_rechannel() {
        seedChannel("t-ark", BASE_URL, "sk-ark-test", false);
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", true);

        assertThatThrownBy(() -> modelTiers.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void given_blank_provider_api_key_when_resolve_then_explicit_error_no_env_fallback() {
        seedChannel("t-ark", BASE_URL, null, true);
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", true);

        assertThatThrownBy(() -> modelTiers.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void given_v44_seed_rows_when_resolve_then_volcengine_primary_shape() {
        // V44 种子链路（volcengine 主用通道 + flash 档）——主链路的真实形状钉死：
        // 三座缺省档 flash 经真解析产 volcengine 前缀模型串（活体走查前的结构面）。
        // key 值随测试环境（env 注入＝真值〔#369 真模型冒烟〕/未注入＝ModelTierSeed
        // 补测试假值）——断言守「非空不回落＋明文不入指纹」，不钉 fixture 字面
        ModelTierSeed.ensureChannelKey(jdbcTemplate);

        ResolvedModel resolved = modelTiers.resolve("flash");

        assertThat(resolved.modelString())
                .isEqualTo("volcengine:deepseek-v4-flash-ga-260731");
        assertThat(resolved.baseUrl()).isEqualTo("https://ark.cn-beijing.volces.com/api/v3");
        assertThat(resolved.apiKey()).isNotBlank();
        assertThat(resolved.channel().fingerprint()).doesNotContain(resolved.apiKey());
    }

    private void seedChannel(String providerKey, String baseUrl, String apiKey, boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO prj_model_providers (provider_key, base_url, api_key, enabled) "
                        + "VALUES (?, ?, ?, ?)",
                providerKey, baseUrl, apiKey, enabled);
    }

    private void seedTier(String tierKey, String providerKey, String modelName, boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO prj_model_tiers (tier_key, provider_key, model_name, enabled) "
                        + "VALUES (?, ?, ?, ?)",
                tierKey, providerKey, modelName, enabled);
    }
}
