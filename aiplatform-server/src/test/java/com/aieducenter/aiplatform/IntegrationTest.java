package com.aieducenter.aiplatform;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;

/**
 * 集成测试基注解：{@link SpringBootTest} + 测试库隔离 profile（ADR-0015）。
 *
 * <p>组合 {@code @ActiveProfiles({"local","test"})}——保留 local 的一切覆盖，test
 * profile 只翻数据源 URL 到独立测试库 {@code aiplatform_test}（见
 * {@code src/test/resources/application-test.yml}）。全表 DELETE teardown 因此在
 * 测试库上就地正确，不再碰 dev 的 {@code aiplatform}（issue #132）。</p>
 *
 * <p>不连数据面的窄上下文测试（SSE 死连接/事件端点回归，显式排除
 * DataSourceAutoConfiguration）不挂本注解，仍用 {@code @SpringBootTest}。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles({"local", "test"})
public @interface IntegrationTest {

    /** 转发到 {@link SpringBootTest#properties()}（如 sso.* 覆盖）。 */
    @AliasFor(annotation = SpringBootTest.class, attribute = "properties")
    String[] properties() default {};
}
