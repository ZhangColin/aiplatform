package com.aieducenter.aiplatform.business.project.application;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试的档位种子护卫（ADR-0031）：走真解析链（{@code ModelTierAppService} →
 * V44 种子行）的测试用——种子行随 Flyway 迁移进测试库，但 volcengine 通道的
 * apiKey 在测试环境无环境变量注入（空＝解析按 PRJ_063 报错，这正是生产行为）。
 * 本工具幂等补上测试 key（不覆盖已写值），让三座命令构建走通全链。
 */
public final class ModelTierSeed {

    /** 测试 key（假值——集成测试不打真 API，模型解析在命令构建面被 mock/stub 截住）。 */
    public static final String TEST_API_KEY = "sk-test-ark";

    /** 档位 'flash' 种子行的解析产物（V44 种子：volcengine:deepseek-v4-flash-ga-260731）。 */
    public static final String FLASH_MODEL_STRING = "volcengine:deepseek-v4-flash-ga-260731";

    private ModelTierSeed() {
    }

    /** 幂等补测试 key（空才写——不覆盖他测自设值）。 */
    public static void ensureChannelKey(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "UPDATE prj_model_providers SET api_key = ? "
                        + "WHERE provider_key = 'volcengine' "
                        + "AND (api_key IS NULL OR api_key = '')",
                TEST_API_KEY);
    }
}
