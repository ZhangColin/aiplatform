package com.semgravio.aiplatform.business.project.application;

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

    /**
     * 通道级秒败的免预算重试上限（#477，按场计数跨尝试累计）：AgentTurnFailure 且
     * {@code llmMs=0}（未发生真实模型调用——连接拒绝/凭证失效类通道错误）不消耗
     * 正常尝试预算，走独立的免预算重试账并带退避（{@link #channelInstantRetryBackoff}
     * 指数翻倍封顶 30s）；额度耗尽后秒败回落正常预算（烧尝试、终态兜底不变——
     * 防通道长期故障下的无限循环）。0＝关闭免预算（回到旧口径）。
     */
    private int channelInstantRetryMax = 3;

    /**
     * 通道级秒败免预算重试的起始退避（#477）：2s 起指数翻倍、封顶 30s——通道故障
     * 给上游喘息，秒败不再零间隔连环重试。
     */
    private Duration channelInstantRetryBackoff = Duration.ofSeconds(2);

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

    public int getChannelInstantRetryMax() {
        return channelInstantRetryMax;
    }

    public void setChannelInstantRetryMax(int channelInstantRetryMax) {
        this.channelInstantRetryMax = channelInstantRetryMax;
    }

    public Duration getChannelInstantRetryBackoff() {
        return channelInstantRetryBackoff;
    }

    public void setChannelInstantRetryBackoff(Duration channelInstantRetryBackoff) {
        this.channelInstantRetryBackoff = channelInstantRetryBackoff;
    }

    public void setToolSilenceTimeout(Duration toolSilenceTimeout) {
        this.toolSilenceTimeout = toolSilenceTimeout;
    }
}
