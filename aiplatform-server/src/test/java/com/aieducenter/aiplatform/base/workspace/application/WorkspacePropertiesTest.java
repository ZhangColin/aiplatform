package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作区配置（#63）：置备最大尝试次数默认值 + 配置键真绑定（前缀/字段名拼写错时字段
 * 静默吃默认值，POJO setter 测不出来——照 #56 配置键实绑口径）。
 */
class WorkspacePropertiesTest {

    /** 规格值断言：默认最大尝试次数 3（含首次，即 2 次自动重试）。 */
    @Test
    void given_default_properties_when_get_provision_max_attempts_then_3() {
        assertThat(new WorkspaceProperties().getProvisionMaxAttempts()).isEqualTo(3);
    }

    /** #171 闲置休眠阈值默认 60 分钟、休眠器默认开启。 */
    @Test
    void given_default_properties_when_hibernation_then_60m_and_enabled() {
        WorkspaceProperties properties = new WorkspaceProperties();
        assertThat(properties.getIdleThreshold()).isEqualTo(Duration.ofMinutes(60));
        assertThat(properties.isHibernationEnabled()).isTrue();
    }

    /** 配置键真绑定（app.workspace.idle-threshold / hibernation-enabled）。 */
    @Test
    void given_config_keys_when_bind_then_hibernation_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.workspace.idle-threshold", "30m",
                "app.workspace.hibernation-enabled", "false"));

        WorkspaceProperties bound = new Binder(source)
                .bind("app.workspace", Bindable.ofInstance(new WorkspaceProperties()))
                .get();

        assertThat(bound.getIdleThreshold()).isEqualTo(Duration.ofMinutes(30));
        assertThat(bound.isHibernationEnabled()).isFalse();
    }

    /** 配置键真绑定（app.workspace.provision-max-attempts）。 */
    @Test
    void given_config_key_when_bind_then_provision_max_attempts_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("app.workspace.provision-max-attempts", "5"));

        WorkspaceProperties bound = new Binder(source)
                .bind("app.workspace", Bindable.ofInstance(new WorkspaceProperties()))
                .get();

        assertThat(bound.getProvisionMaxAttempts()).isEqualTo(5);
    }

    /** #128 预览基域名默认 localhost（开发子域 {id}.localhost）。 */
    @Test
    void given_default_properties_when_get_preview_base_then_localhost() {
        assertThat(new WorkspaceProperties().getPreviewBase()).isEqualTo("localhost");
    }

    /** 配置键真绑定（app.workspace.preview-base）。 */
    @Test
    void given_config_key_when_bind_then_preview_base_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("app.workspace.preview-base", "preview.example.com"));

        WorkspaceProperties bound = new Binder(source)
                .bind("app.workspace", Bindable.ofInstance(new WorkspaceProperties()))
                .get();

        assertThat(bound.getPreviewBase()).isEqualTo("preview.example.com");
    }

    /** #129 预览 scheme 默认 http（开发关 TLS）。 */
    @Test
    void given_default_properties_when_get_preview_scheme_then_http() {
        assertThat(new WorkspaceProperties().getPreviewScheme()).isEqualTo("http");
    }

    /** 配置键真绑定（app.workspace.preview-scheme）。 */
    @Test
    void given_config_key_when_bind_then_preview_scheme_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("app.workspace.preview-scheme", "https"));

        WorkspaceProperties bound = new Binder(source)
                .bind("app.workspace", Bindable.ofInstance(new WorkspaceProperties()))
                .get();

        assertThat(bound.getPreviewScheme()).isEqualTo("https");
    }

    /** #172 封存默认值：阈值 30 天、目录 seal-archives、就绪等待 10 分钟。 */
    @Test
    void given_default_properties_when_seal_then_30d_default_dir_and_10m_readiness() {
        WorkspaceProperties properties = new WorkspaceProperties();
        assertThat(properties.getSealThreshold()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.getSealArchiveDir()).isEqualTo("seal-archives");
        assertThat(properties.getReadinessTimeout()).isEqualTo(Duration.ofMinutes(10));
    }

    /** 配置键真绑定（app.workspace.seal-threshold / seal-archive-dir / readiness-timeout）。 */
    @Test
    void given_config_keys_when_bind_then_seal_wired() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.workspace.seal-threshold", "7d",
                "app.workspace.seal-archive-dir", "/data/seal",
                "app.workspace.readiness-timeout", "20m"));

        WorkspaceProperties bound = new Binder(source)
                .bind("app.workspace", Bindable.ofInstance(new WorkspaceProperties()))
                .get();

        assertThat(bound.getSealThreshold()).isEqualTo(Duration.ofDays(7));
        assertThat(bound.getSealArchiveDir()).isEqualTo("/data/seal");
        assertThat(bound.getReadinessTimeout()).isEqualTo(Duration.ofMinutes(20));
    }
}
