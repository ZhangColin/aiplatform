package com.semgravio.aiplatform.business.project.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 编码 run 配置（前缀 app.generation，#22）：活超时判死阈值（#322）＋设计轨的
 * 有界重试旋钮。
 *
 * <p><b>#604 拆 run 尝试体旧机制后的消费面</b>：生成与更新 run（内核座）<b>单发
 * 无重试</b>——失败语义＝明确展示＋重跑即恢复（ADR-0055 #580-13：不造第三层
 * 重试），重试层只余供给链 failover 与内核 maxAttempts=1，<b>不读本类的重试
 * 旋钮</b>。设计线按总 spec「原样保留」——设计座沿用原有的有界重试（失败／零稿
 * 收口后带续作提示重试，通道级秒败另有免预算重试账），{@link #maxAttempts}、
 * {@link #channelInstantRetryMax}、{@link #channelInstantRetryBackoff} 三键自此
 * <b>只服务设计轨</b>。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.generation")
public class GenerationProperties {

    /**
     * 设计轨单场会话的尝试次数上界（含首试，即 N-1 次带续作提示的重试）：超限转
     * 终态失败，件状态保持、不跳件——用户重提意见即兜底（#289 设计轨失败终态）。
     * #604 起仅设计座消费（生成/更新 run 单发，不读本键）。
     */
    private int maxAttempts = 3;

    /**
     * 单次尝试的活超时阈值（#322）：事件流纯静默超过此值即判死，进入失败处置。
     * 缺省 10 分钟——「还在干活就不掐」：持续产出事件/工具动作的段不受总时长限制，
     * 取代旧一次性 30 分钟总墙钟（活跃输出也被误掐）。模型单条流另有总时长上限
     * （app.agentscope.model-stream-max-duration），两者分工不同：本阈值治整轮
     * 多流多工具的静默，单流上限治永不收口的挂流。
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
     * 防通道长期故障下的无限循环）。0＝关闭免预算。#604 起仅设计座消费。
     */
    private int channelInstantRetryMax = 3;

    /**
     * 通道级秒败免预算重试的起始退避（#477）：2s 起指数翻倍、封顶 30s——通道故障
     * 给上游喘息，秒败不再零间隔连环重试。#604 起仅设计座消费。
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

    public void setToolSilenceTimeout(Duration toolSilenceTimeout) {
        this.toolSilenceTimeout = toolSilenceTimeout;
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
}
