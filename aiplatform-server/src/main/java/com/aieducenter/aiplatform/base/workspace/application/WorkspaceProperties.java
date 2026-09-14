package com.aieducenter.aiplatform.base.workspace.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作区配置（#63/#128/#129/#171，前缀 {@code app.workspace}）：置备最大尝试次数——后台置备失败的
 * 自动重试上界（含首次，即 {@code N-1} 次自动重试）；达上限转 failed 待手动重试
 * （{@link WorkspaceLifecycleAppService#retry}）。预览基域名与 scheme（#128 网关化、
 * #129 生产化）——预览 URL 子域 {@code {scheme}://{id}.{previewBase}} 的两个正交维度：
 * 开发 {@code http} + {@code localhost}，生产 {@code https} + {@code preview.{domain}}。
 * 闲置休眠（#171，ADR-0016）：闲置阈值（last-touch 逾此即休眠，工作区与快照查看
 * 会话同一阈值）与休眠器开关（测试 profile 关闭——集成测试直调 scanOnce，不让
 * 定时器与启动对账扰库）。
 */
@Component
@ConfigurationProperties(prefix = "app.workspace")
public class WorkspaceProperties {

    /** 置备最大尝试次数（含首次，默认 3——即 2 次自动重试）。 */
    private int provisionMaxAttempts = 3;

    /** 预览基域名（#128，默认 localhost——开发子域 {id}.localhost）。 */
    private String previewBase = "localhost";

    /** 预览 URL scheme（#129，默认 http——开发关 TLS；生产 https）。 */
    private String previewScheme = "http";

    /** 闲置休眠阈值（#171，默认 60 分钟）：last-touch 逾此即休眠。 */
    private Duration idleThreshold = Duration.ofMinutes(60);

    /** 休眠器开关（#171，默认开；test profile 关——扫描直调，定时器不扰测试库）。 */
    private boolean hibernationEnabled = true;

    public int getProvisionMaxAttempts() {
        return provisionMaxAttempts;
    }

    public void setProvisionMaxAttempts(int provisionMaxAttempts) {
        this.provisionMaxAttempts = provisionMaxAttempts;
    }

    public String getPreviewBase() {
        return previewBase;
    }

    public void setPreviewBase(String previewBase) {
        this.previewBase = previewBase;
    }

    public String getPreviewScheme() {
        return previewScheme;
    }

    public void setPreviewScheme(String previewScheme) {
        this.previewScheme = previewScheme;
    }

    public Duration getIdleThreshold() {
        return idleThreshold;
    }

    public void setIdleThreshold(Duration idleThreshold) {
        this.idleThreshold = idleThreshold;
    }

    public boolean isHibernationEnabled() {
        return hibernationEnabled;
    }

    public void setHibernationEnabled(boolean hibernationEnabled) {
        this.hibernationEnabled = hibernationEnabled;
    }
}
