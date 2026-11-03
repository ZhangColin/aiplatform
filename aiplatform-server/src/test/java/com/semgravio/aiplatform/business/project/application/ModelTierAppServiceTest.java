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
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", true, null);

        ResolvedModel resolved = modelTiers.resolve("t-flash");

        assertThat(resolved.modelString()).isEqualTo("t-ark:deepseek-v4-flash-ga-260731");
        assertThat(resolved.baseUrl()).isEqualTo(BASE_URL);
        assertThat(resolved.apiKey()).isEqualTo("sk-ark-test");
        // 深度思考不配置＝显式关（#370：缺省 false 与 #369 活体结论一致——不配置
        // ＝显式关，「深度思考不默认拖慢首 token」承诺不破）
        assertThat(resolved.thinkingEnabled()).isFalse();
        // 命令构建消费面：base 层通道注入件（baseUrl/key/thinking）
        assertThat(resolved.channel())
                .isEqualTo(new ModelChannel(BASE_URL, "sk-ark-test", false));
    }

    @Test
    void given_tier_row_thinking_enabled_when_resolve_then_context_carries_tier_value() {
        // #370：thinking 终值位从编译常量改档位行——跟档位/模型走（换模型行为
        // 自动跟随、doubao 与 DeepSeek 可不同设），装配单点读行值
        seedChannel("t-ark", BASE_URL, "sk-ark-test", true);
        seedTier("t-think", "t-ark", "deepseek-v4-pro-ga-260813", true, true);

        ResolvedModel resolved = modelTiers.resolve("t-think");

        assertThat(resolved.thinkingEnabled()).isTrue();
        assertThat(resolved.channel())
                .isEqualTo(new ModelChannel(BASE_URL, "sk-ark-test", true));
        // 开关腿进通道指纹：改开关＝新指纹＝新实例（改配置即生效、进行中 run 不定格）
        assertThat(resolved.channel().fingerprint())
                .isNotEqualTo(new ModelChannel(BASE_URL, "sk-ark-test", false).fingerprint());
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
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", false, null);

        assertThatThrownBy(() -> modelTiers.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void given_disabled_provider_when_resolve_then_explicit_error_no_silent_rechannel() {
        seedChannel("t-ark", BASE_URL, "sk-ark-test", false);
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", true, null);

        assertThatThrownBy(() -> modelTiers.resolve("t-flash"))
                .isInstanceOf(ApplicationException.class)
                .hasMessageContaining("停用");
    }

    @Test
    void given_blank_provider_api_key_when_resolve_then_explicit_error_no_env_fallback() {
        seedChannel("t-ark", BASE_URL, null, true);
        seedTier("t-flash", "t-ark", "deepseek-v4-flash-ga-260731", true, null);

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

    /** 建档位夹具（thinkingEnabled null＝不写列——走库缺省，缺省腿断言语境）。 */
    private void seedTier(String tierKey, String providerKey, String modelName, boolean enabled,
            Boolean thinkingEnabled) {
        if (thinkingEnabled == null) {
            jdbcTemplate.update(
                    "INSERT INTO prj_model_tiers (tier_key, provider_key, model_name, enabled) "
                            + "VALUES (?, ?, ?, ?)",
                    tierKey, providerKey, modelName, enabled);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO prj_model_tiers (tier_key, provider_key, model_name, enabled, "
                        + "thinking_enabled) VALUES (?, ?, ?, ?, ?)",
                tierKey, providerKey, modelName, enabled, thinkingEnabled);
    }
}
