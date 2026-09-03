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

    /** 运行开始（runId 随 run 响应同值返回）。 */
    public static final String RUN_START = "run-start";

    /** 运行创建（cat_agent_state 会话槽位首见发——复用 sessionId 续跑不发，跨重启不重发）。 */
    public static final String RUN_CREATED = "run-created";

    /** 运行失败（异步路径的失败表达，不抛异常）。 */
    public static final String ERROR = "error";

    /** 运行结束（finish = 引擎结煞语，如 end / error）。 */
    public static final String RUN_FINISH = "run-finish";

    /**
     * 智能体挂起出现（ask_user 提问等）：payload 带 runId/sessionId/kind/summary/
     * engineRef/data（引擎载荷原样，含前端问答卡投影与续跑上下文）。答复续跑归
     * 业务编排（问答作答通道，需求环），eventhub 只承载帧。
     */
    public static final String QUESTION_RAISED = "question-raised";

    /**
     * 编码 run 自动重试（生成编排层发射）：一次尝试失败后、下一尝试下发前发射
     * ——runId 锚定<b>失败的那次尝试</b>（帧序 error → run-retrying → 下一尝试
     * run-start），用户侧话术「遇到问题，正在重试」由本帧承载。
     */
    public static final String RUN_RETRYING = "run-retrying";

    /** 重试话术键（用户侧文案，发射方给定）。 */
    public static final String RETRY_MESSAGE_FIELD = "message";

    /** 即将下发的尝试序号（1 起，首试为 1）。 */
    public static final String RETRY_ATTEMPT_FIELD = "attempt";

    /**
     * 编码 run 重试超限·终态收口（#56）：轨道层在真终态落定点发射——修正轨道与
     * 终态账（恢复出口的重派依据）同事实点，排队合并续派的中途超限不是终态、不发；
     * 生成轨道超限即终态。runId 锚定<b>末次失败的尝试</b>（帧序 error(末次) →
     * run-failed）。前端恢复出口（重新发起 / 重新修改）只认本帧——重试进行中的
     * error 帧是过程事实，不判终态（「重新修改」零闪现）。
     */
    public static final String RUN_FAILED = "run-failed";

    // ---------- payload 关联键（全通道唯一真值） ----------

    /** 运行关联字段（事件 id 的 streamId 同值；payload 必带）。 */
    public static final String RUN_FIELD = "runId";

    /** 会话标识（会话建立后各帧携带）。 */
    public static final String SESSION_FIELD = "sessionId";

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

    /** run-finish 的结煞语键（end / exceed_max_iters 等）。 */
    public static final String FINISH_FIELD = "finish";

    /**
     * 修正 run 收口·系统未动（#46）：编码智能体以 finish_fix(changed=false) 判定
     * 无需改动时的如实呈现帧——修正轨道收口后发射（正常收口帧 run-finish 之后），
     * 让用户能区分「不需要改」与「链路断了」。changed=true 不发（现有收口行为
     * 不回归）。
     */
    public static final String FIX_UNCHANGED = "fix-unchanged";

    /** 未动系统的原因键（finish_fix 的 text 原文——用户侧呈现正本）。 */
    public static final String FIX_UNCHANGED_REASON_FIELD = "reason";

    /**
     * 兜底轻引导回复（#47 入口三分类）：非意见非咨询输入（寒暄/闲聊/下单意图等）
     * 的平台侧轻量引导——代码承载的定型文案，零产物路径（不起任何智能体 run），
     * 无智能体帧序（本帧即全部）；runId 为派发锚（随派发响应同值返回）。
     */
    public static final String GUIDE_REPLY = "guide-reply";

    /** 引导回复正文键（平台侧定型文案）。 */
    public static final String GUIDE_TEXT_FIELD = "text";

    /** 引导回复锚定的用户输入键（重放重建对话面用，同 run-start 的 prompt 语义）。 */
    public static final String GUIDE_PROMPT_FIELD = "prompt";

    /** 引导回复的呈现标签键（「平台」——非智能体角色，随帧呈现）。 */
    public static final String GUIDE_LABEL_FIELD = "label";

    // ---------- 派发阶段（#50 阶段状态条；意见/咨询全过程） ----------

    /**
     * 派发阶段帧（#50）：意见 / 咨询链的阶段推进信号——前端状态条的唯一数据源
     * （不署智能体名，阶段进度可见）。帧由业务编排层（派发 / BA / 助理 / 修正轨道）
     * 在阶段边界发射，值集见发射方 {@code DispatchStage}（意见链 analyzing →
     * clarifying? / updating-prd? → dispatching | queued → fixing → done；咨询链
     * analyzing → answered）。
     */
    public static final String DISPATCH_STAGE = "dispatch-stage";

    /** 阶段值键（发射方 DispatchStage 枚举的 wire 值）。 */
    public static final String DISPATCH_STAGE_FIELD = "stage";

    /** 完成态是否改动了系统（仅 stage=done 携带：true 已修改 / false 未动系统）。 */
    public static final String DISPATCH_CHANGED_FIELD = "changed";

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
