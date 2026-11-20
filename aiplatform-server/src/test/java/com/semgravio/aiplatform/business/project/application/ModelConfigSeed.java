package com.semgravio.aiplatform.business.project.application;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试的配置种子护卫（ADR-0031；V79 收敛后口径，ADR-0053 ④）：走真解析链
 * （{@code ModelConfigAppService} → V44+V79 种子行）的测试用——V79 后种子＝
 * deepseek 单通道（火山下线收尾），deepseek 通道的 apiKey 由 test profile
 * placeholder 在迁移时种入（sk-test-deepseek）；本工具对空值幂等兜底（不覆盖
 * 已写值——live 环境覆写真 key 后他测不扰），让三座命令构建走通全链。
 */
public final class ModelConfigSeed {

    /** 测试 key（假值——与 application-test.yml deepseekApiKey placeholder 同值；集成测试不打真 API，模型解析在命令构建面被 mock/stub 截住）。 */
    public static final String TEST_API_KEY = "sk-test-deepseek";

    /** 配置 'flash' 种子行的解析产物（V79 种子：deepseek 官方 flash 模型）。 */
    public static final String FLASH_MODEL_STRING = "deepseek:deepseek-flash";

    private ModelConfigSeed() {
    }

    /** 幂等补测试 key（空才写——不覆盖他测/live 自设值）。 */
    public static void ensureChannelKey(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "UPDATE prj_model_providers SET api_key = ? "
                        + "WHERE provider_key = 'deepseek' "
                        + "AND (api_key IS NULL OR api_key = '')",
                TEST_API_KEY);
    }
}
