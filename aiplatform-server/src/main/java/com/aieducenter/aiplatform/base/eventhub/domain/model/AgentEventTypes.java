package com.aieducenter.aiplatform.base.eventhub.domain.model;

/**
 * 智能体流事件名册常量（ADR-0001：代码侧每 BC 一个 EventTypes 常量类，禁止字符串
 * 字面量散落；正本见 docs/spec/SSE事件清单.md·通道二）。eventhub 是唯一 SSE 管道，
 * 智能体流词汇表在此定义、agentscope 基础设施 mapper 翻译填充。
 *
 * <p>只收录平台封闭集合——平台事件；引擎透传事件（text / reasoning /
 * patch / tool / step-start / step-finish 等）的 type 是引擎 part 类型原样
 * （开放集合，不设常量），payload 的 {@code data} 键内为 part 原样。</p>
 */
public final class AgentEventTypes {

    /** 运行开始（runId 随任务响应同值返回）。 */
    public static final String TASK_START = "task-start";

    /** 会话建立（复用 sessionId 续跑不发——会话早已建立）。 */
    public static final String SESSION_CREATED = "session-created";

    /** 运行失败（异步路径的失败表达，不抛异常）。 */
    public static final String ERROR = "error";

    /** 运行结束（finish = 引擎结煞语，如 end / error）。 */
    public static final String TASK_FINISH = "task-finish";

    /**
     * 智能体挂起出现（ask_user 提问等）：payload 带 runId/sessionId/kind/summary/
     * engineRef/data（引擎载荷原样，含前端问答卡投影与续跑上下文）。答复续跑归
     * 业务编排（问答作答通道，需求环），eventhub 只承载帧。
     */
    public static final String WAIT_RAISED = "wait-raised";

    /**
     * 编码 run 自动重试（生成编排层发射）：一次尝试失败后、下一尝试下发前发射
     * ——runId 锚定<b>失败的那次尝试</b>（帧序 error → task-retrying → 下一尝试
     * task-start），用户侧话术「遇到问题，正在重试」由本帧承载。
     */
    public static final String TASK_RETRYING = "task-retrying";

    /** 重试话术键（用户侧文案，发射方给定）。 */
    public static final String RETRY_MESSAGE_FIELD = "message";

    /** 即将下发的尝试序号（1 起，首试为 1）。 */
    public static final String RETRY_ATTEMPT_FIELD = "attempt";

    // ---------- payload 关联键（全通道唯一真值；wait-raised 契约键为同值别名） ----------

    /** 运行关联字段（事件 id 的 streamId 同值；payload 必带）。 */
    public static final String RUN_FIELD = "runId";

    /** 会话标识（会话建立后各帧携带）。 */
    public static final String SESSION_FIELD = "sessionId";

    /** wait-raised 的 runId 契约键（别名，同 {@link #RUN_FIELD}）。 */
    public static final String WAIT_RUN_FIELD = RUN_FIELD;

    /** wait-raised 的 sessionId 契约键（别名，同 {@link #SESSION_FIELD}）。 */
    public static final String WAIT_SESSION_FIELD = SESSION_FIELD;

    /** 挂起种类（QUESTION = 向用户提问 / PERMISSION = 工具确认）。 */
    public static final String WAIT_KIND_FIELD = "kind";

    /** mapper 提取的中性短文本。 */
    public static final String WAIT_SUMMARY_FIELD = "summary";

    /** 引擎侧请求/权限 id（续跑批复的锚）。 */
    public static final String WAIT_ENGINE_REF_FIELD = "engineRef";

    /** 引擎载荷原样（eventhub 不解释）。 */
    public static final String WAIT_DATA_FIELD = "data";

    /** 角色卡分配（业务编排层发射）：选定角色卡后、run 下发前发射——底座无角色
     *  概念（systemPrompt/modelId 是入参），常量入册供编排层引用。 */
    public static final String ROLE_ASSIGNED = "role-assigned";

    /** 角色卡标识（RolePreset 枚举名）。 */
    public static final String ROLE_FIELD = "role";

    /** 角色卡展示标签。 */
    public static final String ROLE_LABEL_FIELD = "roleLabel";

    /** 承接运行的智能体栈名（单栈 agentscope；各帧 payload 同键携带）。 */
    public static final String ROLE_ENGINE_FIELD = "engine";

    /** 帧自述引擎键（别名，同 {@link #ROLE_ENGINE_FIELD}）。 */
    public static final String ENGINE_FIELD = ROLE_ENGINE_FIELD;

    /** task-finish 的结煞语键（end / exceed_max_iters 等）。 */
    public static final String FINISH_FIELD = "finish";

    // ---------- 直播词汇（#23 生成环②；编码 run 的客户面解说广播） ----------
    // 前端直播侧栏只消费本组帧（+ run 生命周期平台事件），不耦合引擎透传事件格式；
    // 帧由 base.agentscope 的直播 mapper 逐段生产（SSE事件清单·通道二直播行）。

    /** 直播·智能体自述解说段（服务端逐段成型——句读/块变/边界切分，一段一帧完整文本）。 */
    public static final String LIVE_TEXT = "live-text";

    /** 直播·动作摘要行（工具动作 → 人话模板，如「正在编写【订单管理】」）。 */
    public static final String LIVE_ACTION = "live-action";

    /** 直播·步骤段（run 内步骤序号，1 起）。 */
    public static final String LIVE_STEP = "live-step";

    /** live-text 的段文本键（完整段，非增量）。 */
    public static final String LIVE_TEXT_FIELD = "text";

    /** live-action 的人话动作键。 */
    public static final String LIVE_ACTION_FIELD = "action";

    /** live-step 的步骤序号键（1 起）。 */
    public static final String LIVE_STEP_FIELD = "step";

    private AgentEventTypes() {
    }
}
