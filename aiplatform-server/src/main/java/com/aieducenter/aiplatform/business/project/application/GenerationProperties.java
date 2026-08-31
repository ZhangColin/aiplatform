package com.aieducenter.aiplatform.business.project.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生成编排配置（前缀 app.generation，#22）：重试次数与超时值实施期定的落点。
 */
@Component
@ConfigurationProperties(prefix = "app.generation")
public class GenerationProperties {

    /**
     * 生成尝试次数上界（含首试，即 N-1 次自动重试）：超限转终态失败，
     * 由用户重新发起兜底（人工兜底入口）。
     */
    private int maxAttempts = 3;

    /** 单次生成尝试的对话超时：编码是长任务，区别于对话轮内核默认（2 分钟）。 */
    private Duration timeout = Duration.ofMinutes(30);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
