package com.aieducenter.aiplatform.business.project.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 生成编排配置（前缀 app.generation，#22）：重试次数与活超时判死阈值（#322）。
 */
@Component
@ConfigurationProperties(prefix = "app.generation")
public class GenerationProperties {

    /**
     * 生成尝试次数上界（含首试，即 N-1 次自动重试＝原地修，#221）：超限转终态
     * 失败，由用户「继续生成」断点续跑兜底（人工兜底入口）。
     */
    private int maxAttempts = 3;

    /**
     * 单次生成尝试的活超时阈值（#322）：事件流纯静默超过此值即判死，进入失败处置
     * （重试/尝试环）。缺省 10 分钟——「还在干活就不掐」：持续产出事件/工具动作的
     * 段不受总时长限制，取代旧一次性 30 分钟总墙钟（活跃输出也被误掐）。模型单条
     * 流另有总时长上限（app.agentscope.model-stream-max-duration），两者分工不同：
     * 本阈值治整轮多流多工具的静默，单流上限治永不收口的挂流。
     */
    private Duration silenceTimeout = Duration.ofMinutes(10);

    /**
     * 工具执行窗的活超时预算（#355 机制检查）：工具执行中（参数落定→结果返回）
     * 引擎无任何事件，长命令（依赖安装/测试超 10 分钟）会被静默阈值误杀——
     * 「还在干活就不掐」意图落空。执行窗给延长预算（缺省 30 分钟），任何事件
     * 到达即回落常规静默阈值；空值/非正回落同静默阈值（不放大预算）。
     */
    private Duration toolSilenceTimeout = Duration.ofMinutes(30);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getSilenceTimeout() {
        return silenceTimeout;
    }

    public void setSilenceTimeout(Duration silenceTimeout) {
        this.silenceTimeout = silenceTimeout;
    }

    public Duration getToolSilenceTimeout() {
        return toolSilenceTimeout;
    }

    public void setToolSilenceTimeout(Duration toolSilenceTimeout) {
        this.toolSilenceTimeout = toolSilenceTimeout;
    }
}
