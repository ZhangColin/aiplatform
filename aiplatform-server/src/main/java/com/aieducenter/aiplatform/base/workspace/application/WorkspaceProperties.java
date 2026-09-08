package com.aieducenter.aiplatform.base.workspace.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作区配置（#63/#128，前缀 {@code app.workspace}）：置备最大尝试次数——后台置备失败的
 * 自动重试上界（含首次，即 {@code N-1} 次自动重试）；达上限转 failed 待手动重试
 * （{@link WorkspaceLifecycleAppService#retry}）。预览基域名（#128 网关化）——预览 URL
 * 子域 {@code {id}.{previewBase}} 的基域名：开发 {@code localhost}，生产换
 * {@code preview.{domain}}。
 */
@Component
@ConfigurationProperties(prefix = "app.workspace")
public class WorkspaceProperties {

    /** 置备最大尝试次数（含首次，默认 3——即 2 次自动重试）。 */
    private int provisionMaxAttempts = 3;

    /** 预览基域名（#128，默认 localhost——开发子域 {id}.localhost）。 */
    private String previewBase = "localhost";

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
}
