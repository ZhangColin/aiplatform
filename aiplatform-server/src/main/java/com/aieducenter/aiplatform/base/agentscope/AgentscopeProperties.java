package com.aieducenter.aiplatform.base.agentscope;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AgentScope 内核配置（前缀 app.agentscope）：模型默认值 / 默认人格 / agent 名 /
 * 工作区根 / 单轮超时。workspace 是本地兜底工作区（命令未带 workspaceId 时用，
 * 如取名轻调用），为空时用 AgentScope 默认（.agentscope/workspace）；带 workspaceId
 * 的调用解析为项目 dev 工作区（容器文件面，不经此配置）。
 */
@Component
@ConfigurationProperties(prefix = "app.agentscope")
public class AgentscopeProperties {

    /** agent 名（AgentState 落盘命名空间的一部分，稳定勿动） */
    private String agentName = "platform-agent";

    /** 默认模型串（provider:modelId，白名单见 ModelRef） */
    private String defaultModel = "deepseek:deepseek-v4-flash";

    /** 默认系统提示词（命令未带 systemPrompt 时兜底） */
    private String defaultSystemPrompt = "你是平台智能体。";

    /** 工作区根路径（可空 → AgentScope 默认） */
    private Path workspace;

    /** 单轮对话超时 */
    private Duration timeout = Duration.ofMinutes(2);

    /**
     * 单 turn 迭代上限（ReAct 模型调用次数）。null = 不覆盖框架缺省——HarnessAgent
     * 内核缺省 10：对话轮够用（主智能体每轮以 ask_user 挂起收尾，远到不了 10），编码
     * turn 远不够（#25 活体对照实测：一次中等 PRD 的系统生成单 turn 需约 160 次迭代，
     * 10 次即被掐断成 exceed_max_iters「假完成」）。取值给足余量（实测需求 ×2 弱），
     * 失控保护以墙钟为主界（app.generation.timeout），本上限只作次级护栏。
     */
    private Integer maxIters;

    /**
     * 压缩触发阈值（token 数，显式值）。框架动态缺省 = contextWindow(1M) - reserved(20k)
     * ≈ 980K，形同虚设——压缩在真正溢出前不触发；且框架 token 估算（2.5 字符/token）
     * 对中文系统性低估 1.25–2.5×（中文实际 1–2 字符/token），主智能体（中文对话）
     * 尤甚。取 400K：按最坏低估比 2.5× 折算 est 400K ≈ 真实 1M，正好在
     * context_length_exceeded 硬上限前触发（streamEvents 路径天然受益，「炸后下轮」
     * 因下一轮先压缩再跑而自愈，#108 / ADR-0012）。null = 回框架动态缺省。
     */
    private Integer compactionTriggerTokens = 400_000;

    /**
     * 压缩（摘要）专用模型串（provider:modelId，压缩模型档位）。缺省取 flash 便宜档——
     * 执行体（pro）会话的压缩不再烧 pro；主智能体本就 flash，同档无成本差但显式化
     * （ADR-0012：框架缺省值转平台显式配置）。null/空 = 回框架缺省（用主模型）。
     */
    private String compactionModel = "deepseek:deepseek-v4-flash";

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public String getDefaultSystemPrompt() {
        return defaultSystemPrompt;
    }

    public void setDefaultSystemPrompt(String defaultSystemPrompt) {
        this.defaultSystemPrompt = defaultSystemPrompt;
    }

    public Path getWorkspace() {
        return workspace;
    }

    public void setWorkspace(Path workspace) {
        this.workspace = workspace;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Integer getMaxIters() {
        return maxIters;
    }

    public void setMaxIters(Integer maxIters) {
        this.maxIters = maxIters;
    }

    public Integer getCompactionTriggerTokens() {
        return compactionTriggerTokens;
    }

    public void setCompactionTriggerTokens(Integer compactionTriggerTokens) {
        this.compactionTriggerTokens = compactionTriggerTokens;
    }

    public String getCompactionModel() {
        return compactionModel;
    }

    public void setCompactionModel(String compactionModel) {
        this.compactionModel = compactionModel;
    }
}
