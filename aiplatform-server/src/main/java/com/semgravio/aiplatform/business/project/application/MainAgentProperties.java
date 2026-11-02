package com.semgravio.aiplatform.business.project.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 主智能体对话编排配置（前缀 app.main-agent）：对话轮活超时判死阈值（#371）。
 */
@Component
@ConfigurationProperties(prefix = "app.main-agent")
public class MainAgentProperties {

    /**
     * 对话轮的活超时阈值（#371）：事件流纯静默超过此值即判死（error 事件如实呈现）。
     * 旧口径是一次性 2 分钟总墙钟（app.agentscope.timeout）——#213/#215 增强工具
     * （fetch_url/web_search）加入后，单个慢工具（跨境抓取）即可吃穿总墙钟：轮被
     * 掐死、对话无产出，用户只见「没反应」。改用生成轨 #322 活超时口径：还在干活
     * 就不掐（持续产出事件/工具动作的段无总时长死限），纯静默超限才判死。缺省与旧
     * 总墙钟同量级（2 分钟）——纯死寂轮的兜底时感不变。
     */
    private Duration silenceTimeout = Duration.ofMinutes(2);

    /**
     * 对话轮工具执行窗的活超时预算（#355 同机制）：工具执行中（参数落定→结果返回）
     * 引擎无任何事件，增强工具慢源（跨境 fetch/搜索）会被静默阈值误杀——执行窗给
     * 延长预算，任何事件到达即回落常规静默阈值。缺省 5 分钟：对话工具是网络 I/O
     * 型（fetch_url 请求超时另配 app.external-content.request-timeout-seconds，
     * 缺省 45s），远轻于生成轨的构建命令（30 分钟），给足单工具余量即可；空值/非正
     * 回落同静默阈值（不放大预算）。
     */
    private Duration toolSilenceTimeout = Duration.ofMinutes(5);

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
