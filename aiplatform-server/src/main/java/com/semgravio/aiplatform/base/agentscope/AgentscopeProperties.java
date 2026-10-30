package com.semgravio.aiplatform.base.agentscope;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AgentScope 内核配置（前缀 app.agentscope）：默认人格 / agent 名 / 工作区根 /
 * 单轮超时。模型选用不在此（ADR-0031/#366 起全链走档位解析：三座与边缘四点的
 * 模型串＋通道注入由业务侧 {@code ModelTierAppService} 解析随命令/装配透传——
 * 旧 yml 模型键 default-model/compaction-model 已随边缘点收编退役）。workspace
 * 是本地兜底工作区（命令未带 workspaceId 时用，如取名轻调用），为空时用
 * AgentScope 默认（.agentscope/workspace）；带 workspaceId 的调用解析为项目
 * dev 工作区（容器文件面，不经此配置）。
 */
@Component
@ConfigurationProperties(prefix = "app.agentscope")
public class AgentscopeProperties {

    /** agent 名（AgentState 落盘命名空间的一部分，稳定勿动） */
    private String agentName = "platform-agent";

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
     * 编码轨失控保护以活超时判死为主界（app.generation.silence-timeout，#322），
     * 本上限只作次级护栏。
     */
    private Integer maxIters;

    /**
     * 压缩触发阈值（token 数，显式值）。框架动态缺省 = contextWindow(1M) - reserved(20k)
     * ≈ 980K，形同虚设——压缩在真正溢出前不触发；且框架 token 估算（2.5 字符/token）
     * 对中文系统性低估 1.25–2.5×（中文实际 1–2 字符/token），主智能体（中文对话）
     * 尤甚。取 400K：按最坏低估比 2.5× 折算 est 400K ≈ 真实 1M，正好在
     * context_length_exceeded 硬上限前触发（streamEvents 路径天然受益，「炸后下轮」
     * 因下一轮先压缩再跑而自愈，#108 / ADR-0012）。null = 回框架动态缺省。
     * 压缩模型不在此配（#366 起走边缘点档位链：compaction 档位库值优先缺省 flash，
     * 经 {@code EdgeModelSupplier} 解析注入工厂装配）。
     */
    private Integer compactionTriggerTokens = 400_000;

    /**
     * 模型流断流自动重试次数上界（含首试；#321）。单条模型流遇可重试错误（HTTP
     * 429/5xx，框架 {@code ExecutionConfig.RETRYABLE_ERRORS} 分类）在流层有限次
     * 自动重试接续，耗尽才上抛进外层尝试环（CoderRunAttempts 维持最后防线）；1 =
     * 关闭流层重试。缺省 3 = 首试 + 2 次重试。平台层独占流层重试（框架内建缺省
     * 已在 resolve 时关闭，见工厂 innerRetryDisabledOptions——两层叠加会让次数
     * 语义不可控）。已发块后的失败不重试（重订阅重放已消费块会让下游累积重复
     * 文本——护栏在 {@link RetryingModel}）。流时长上限（堵无界挂流）不在此配
     * ——属 #322 活超时线。
     */
    private Integer modelStreamRetryMaxAttempts = 3;

    /** 模型流重试起始退避（指数退避起点，缺省 2s——429 限流场景给上游喘息）。 */
    private Duration modelStreamRetryInitialBackoff = Duration.ofSeconds(2);

    /** 模型流重试退避上限（指数退避封顶，缺省 30s）。 */
    private Duration modelStreamRetryMaxBackoff = Duration.ofSeconds(30);

    /**
     * 单条模型流总时长上限（#322 堵无界挂流）。缺省 15 分钟；null/零/负 = 不设上限。
     * 取值依据：框架缺省 5 分钟 inter-signal 超时只掐「信号间隔」——滴漏挂流（块间隔
     * 小于阈值、永不收口）不触发，实测现场 15–109 分钟级；合法单流（连续产出的长
     * 生成）远小于此量级（30 tok/s × 15min ≈ 27K token 输出，已是超长单响应），
     * 误掐代价＝一次可恢复的失败重试（外层尝试环原地修），不掐代价＝用户无界等待
     * ——取掐。超限按失败处置进重试面（{@link ModelStreamDurationException}
     * 可重试分类），已发块护栏照常治理。
     */
    private Duration modelStreamMaxDuration = Duration.ofMinutes(15);

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
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

    public Integer getModelStreamRetryMaxAttempts() {
        return modelStreamRetryMaxAttempts;
    }

    public void setModelStreamRetryMaxAttempts(Integer modelStreamRetryMaxAttempts) {
        this.modelStreamRetryMaxAttempts = modelStreamRetryMaxAttempts;
    }

    public Duration getModelStreamRetryInitialBackoff() {
        return modelStreamRetryInitialBackoff;
    }

    public void setModelStreamRetryInitialBackoff(Duration modelStreamRetryInitialBackoff) {
        this.modelStreamRetryInitialBackoff = modelStreamRetryInitialBackoff;
    }

    public Duration getModelStreamRetryMaxBackoff() {
        return modelStreamRetryMaxBackoff;
    }

    public void setModelStreamRetryMaxBackoff(Duration modelStreamRetryMaxBackoff) {
        this.modelStreamRetryMaxBackoff = modelStreamRetryMaxBackoff;
    }

    public Duration getModelStreamMaxDuration() {
        return modelStreamMaxDuration;
    }

    public void setModelStreamMaxDuration(Duration modelStreamMaxDuration) {
        this.modelStreamMaxDuration = modelStreamMaxDuration;
    }
}
