package com.semgravio.aiplatform.config;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * prod 配置面 redis 密码（#383）：密码项注入生效 + 缺失 fail-fast 口径与既有
 * REDIS_HOST 一致（占位符无默认值，启动解析即抛——不静默回落空串/字面量）。
 * 测试直读真身 application-prod.yml（YamlPropertySourceLoader 载入 Environment），
 * 断言走标准占位符解析路径（应用启动同一条 resolveRequiredPlaceholders），
 * 照 #56 配置键实绑口径。
 */
class ProdRedisPropertiesTest {

    /** #383 注入：REDIS_PASSWORD 环境变量原样进入 spring.data.redis.password。 */
    @Test
    void given_redis_password_env_when_resolve_prod_yml_then_injected_value() throws IOException {
        StandardEnvironment environment = prodEnvironment(true);

        assertThat(environment.getRequiredProperty("spring.data.redis.password"))
                .isEqualTo("s3cret-infra-pass");
        // 对照：既有 host 同管线、同注入值
        assertThat(environment.getRequiredProperty("spring.data.redis.host"))
                .isEqualTo("semgravio-redis");
        // 既有默认值键不受影响
        assertThat(environment.getRequiredProperty("spring.data.redis.port")).isEqualTo("6379");
    }

    /** #383 fail-fast：缺失 REDIS_PASSWORD 与缺失 REDIS_HOST 同口径——启动解析即炸。 */
    @Test
    void given_no_redis_password_env_when_resolve_then_fail_fast_like_redis_host() throws IOException {
        StandardEnvironment environment = prodEnvironment(false);

        assertThatThrownBy(() -> environment.getRequiredProperty("spring.data.redis.password"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDIS_PASSWORD");
        // 对照：既有 REDIS_HOST 同口径（先例在，证明「口径一致」而非各管各的）
        assertThatThrownBy(() -> environment.getRequiredProperty("spring.data.redis.host"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDIS_HOST");
    }

    /** 载入 application-prod.yml 真身；withPassword 决定是否叠加宿主 env（模拟部署面 .env 注入）。 */
    private StandardEnvironment prodEnvironment(boolean withPassword) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        List<PropertySource<?>> yamlSources = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml"));
        yamlSources.forEach(environment.getPropertySources()::addFirst);
        if (withPassword) {
            environment.getPropertySources().addFirst(new PropertySource<>("env") {
                @Override
                public Object getProperty(String name) {
                    return switch (name) {
                        case "REDIS_HOST" -> "semgravio-redis";
                        case "REDIS_PASSWORD" -> "s3cret-infra-pass";
                        default -> null;
                    };
                }
            });
        }
        return environment;
    }
}
