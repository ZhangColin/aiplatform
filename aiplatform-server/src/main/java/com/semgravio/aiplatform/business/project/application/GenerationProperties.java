package com.semgravio.aiplatform.business.project.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 编码 run 配置（前缀 app.generation，#22）：活超时判死阈值（#322）。
 *
 * <p><b>#604 拆 run 尝试体旧机制后的消费面</b>：生成与更新 run（内核座）<b>单发
 * 无重试</b>——失败语义＝明确展示＋重跑即恢复（ADR-0055 #580-13：不造第三层
 * 重试），重试层只余供给链 failover 与内核 maxAttempts=1。<b>#610 设计线整条
 * 退役</b>：设计座的有界重试旋钮（max-attempts／channel-instant-retry-*）随设计
 * 执行体一并拆除，本类只余活超时两项。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.generation")
public class GenerationProperties {

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
