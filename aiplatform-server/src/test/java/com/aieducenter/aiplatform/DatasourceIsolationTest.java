package com.aieducenter.aiplatform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * 测试库隔离的 tripwire（ADR-0015 / issue #132）：挂在 {@link IntegrationTest} 下，
 * 集成测试的数据源必须解析到独立测试库 {@code aiplatform_test}，而非 dev 的
 * {@code aiplatform}。一旦有人移除 {@code application-test.yml} 或 {@code
 * @ActiveProfiles}，本测试即红——防止测试悄然回到「全表 DELETE 清空 dev 真数据」。
 */
@IntegrationTest
class DatasourceIsolationTest {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Test
    void given_test_profile_when_resolving_datasource_url_then_targets_isolated_test_db() {
        assertThat(datasourceUrl).contains("aiplatform_test");
    }
}
