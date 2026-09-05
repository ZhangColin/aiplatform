package com.aieducenter.aiplatform.base.eventhub.domain.model;

/**
 * 智能体事件名册常量（ADR-0001：代码侧每 BC 一个 EventTypes 常量类，禁止字符串
 * 字面量散落；正本见 docs/spec/SSE事件清单.md·智能体事件族）。eventhub 是唯一
 * SSE 管道，智能体事件词汇表在此定义、agentscope 基础设施 mapper 翻译填充。
 *
 * <p>只收录平台封闭集合——平台事件；引擎透传事件（text / reasoning /
 * patch / tool / step-start / step-finish 等）的 type 是引擎 part 类型原样
 * （开放集合，不设常量），payload 的 {@code data} 键内为 part 原样。</p>
 */
public final class AgentEventTypes {

    /**
     * 运行开始（runId 随 run 响应同值返回）。engine/model 之外携带智能体配置键
     * {@code agent}（业务侧 AgentProfile 的稳定键，如 executor；无配置语境的
     * 一次性调用不携带）——前端工作消息/对话面的锚定判据。一场 run 恰一次：
     * 编码 run 静默重试（#84）不新发——用户面 run 身份 = 首试 runId 全程不变。
     */
    public static final String RUN_START = "run-start";

    /**
     * 运行失败（非重试族的失败表达：对话轮失败、挂起续跑失败、run 起跑前段
     * 失败）。编码 run 重试族静默（#84）——run 层唯一失败终态是 {@link #RUN_FAILED}。
     */
    public static final String ERROR = "error";

    /** 失败事件的用户侧消息键。 */
    public static final String ERROR_MESSAGE_FIELD = "message";

    /**
     * 运行结束（finish = 引擎结煞语，如 end / error）。编码 run 在收口判据落定后
     * 扩载 {@link #CLOSING_FIELD}（#88 收尾卡——服务端权威的摘要/判定行/变更清单/
     * 轮末统计，schema 见 SSE事件清单·收口扩载节）；主智能体对话轮（咨询/纯追问）
     * 不携带——无收尾卡。
     */
    public static final String RUN_FINISH = "run-finish";

    /**
     * run-finish 的收口扩载键（#88）：值为对象 { summary, prdChanged, prdNote?,
     * systemChanged, systemNote?, files[{path,added,removed}], durationMs }——
     * 对话史落库（#89）与版本锚定（#91）复用同一载荷。判定与清单以平台可观测
     * 事实为准（工具调用/探活），不由模型自报。
     */
    public static final String CLOSING_FIELD = "closing";

    /**
     * 智能体挂起提问（ask_user 触发，#83 起纯 QUESTION——权限确认已拆独立事件
     * {@link #PERMISSION_REQUIRED}）：payload 带 runId/sessionId/summary/engineRef/
     * data（引擎载荷原样，含前端问答卡投影与续跑上下文）。答复续跑归业务编排
     * （问答作答通道，需求环），eventhub 只承载事件。
     */
    public static final String QUESTION_RAISED = "question-raised";

    /**
     * 权限确认挂起出现（#83 事件拆分：词根 = 引擎权限确认原语
     * RequireUserConfirmEvent 的非提问面——危险命令等待用户批准）：payload 带
     * runId/sessionId/summary/engineRef/data（data.toolCalls 为待确认工具最小面，
     * 确认卡呈现源）。批准/拒绝续跑归业务编排（权限作答通道，与问答作答分家——
     * 互不串扰），eventhub 只承载事件。
     */
    public static final String PERMISSION_REQUIRED = "permission-required";

    /**
     * 权限确认落定（#83）：作答被受理（批准或拒绝）即发射——确认卡转已批/已拒
     * 终态的呈现源（事件族重放面：重连/刷新后确认卡不回退成待答）。续跑结果另行
     * 经 run 过程事件到达。
     */
    public static final String PERMISSION_RESOLVED = "permission-resolved";

    /** permission-resolved 的批准位键（true = 已批准 / false = 已拒绝）。 */
    public static final String PERMISSION_APPROVED_FIELD = "approved";

    /**
     * 编码 run 重试超限·终态收口（#56）：轨道层在真终态落定点发射——修正轨道与
     * 终态账（恢复出口的重派依据）同事实点，排队合并续派的中途超限不是终态、不发；
     * 生成轨道超限即终态。runId = 该场 run 的<b>用户面标识</b>（首试 runId——#84
     * 静默重试：重试不换新锚、不新发 {@link #RUN_START}，中间尝试的内部 runId
     * 不出用户面，事件序上 run-failed 前无任何失败/重试信号）。前端恢复出口
     * （重新发起 / 重新修改）只认本事件——run 失败为唯一失败终态。
     */
    public static final String RUN_FAILED = "run-failed";

    // ---------- payload 关联键（全通道唯一真值） ----------

    /** 运行关联字段（事件 id 的 streamId 同值；payload 必带）。 */
    public static final String RUN_FIELD = "runId";

    /** 会话标识（会话建立后各事件携带）。 */
    public static final String SESSION_FIELD = "sessionId";

    /** mapper 提取的中性短文本。 */
    public static final String WAIT_SUMMARY_FIELD = "summary";

    /** 引擎侧请求/权限 id（续跑批复的锚）。 */
    public static final String WAIT_ENGINE_REF_FIELD = "engineRef";

    /** 引擎载荷原样（eventhub 不解释）。 */
    public static final String WAIT_DATA_FIELD = "data";

    /** 智能体配置键（AgentProfile 稳定键，如 main/executor；run-start 携带）。 */
    public static final String AGENT_FIELD = "agent";

    /** 承接运行的智能体栈名（单栈 agentscope；各事件 payload 同键携带）。 */
    public static final String ENGINE_FIELD = "engine";

    /**
     * 事件来源归属（#95 委派位）：run 执行体自身产出的过程事件不携带（缺省 =
     * 执行体——用户面仍无角色标签）；子智能体（经 run 执行体委派，如自测）转发进
     * 父流的事件携带子智能体名（值 = 引擎 source 路径的末段）。source 只用于过程
     * 呈现归属（分角色播），不是用户面角色标签。
     */
    public static final String SOURCE_FIELD = "source";

    /** run-finish 的结煞语键（end / exceed_max_iters 等）。 */
    public static final String FINISH_FIELD = "finish";

    /**
     * 兜底轻引导回复（#47 入口三分类）：非意见非咨询输入（寒暄/闲聊/下单意图等）
     * 的平台侧轻量引导——代码承载的定型文案，零产物路径（不起任何智能体 run），
     * 无智能体事件序（本事件即全部）；runId 为派发锚（随派发响应同值返回）。
     */
    public static final String GUIDE_REPLY = "guide-reply";

    /**
     * 受理开始（#87 受理动作卡）：受理轮（迭代期意见轮——项目已生成后的意见链
     * 轮）开场的受理事实——意见已接住、主智能体正在受理（需求不清则追问；需求
     * 变更则改 PRD）。对话区受理动作卡的呈现源（意见到更新 run 工作消息之间的
     * 呈现位，原「更新 PRD 中」阶段的归位）；受理落定不出新事件——由该轮
     * run-finish / error 收口事件推导（重放面随事件族重建）。场景矩阵收口：咨询
     * 轮与纯追问轮（访谈期意见轮）不发。
     */
    public static final String ACCEPTANCE_START = "acceptance-start";

    /** 引导回复正文键（平台侧定型文案）。 */
    public static final String GUIDE_TEXT_FIELD = "text";

    /** 引导回复锚定的用户输入键（重放重建对话面用，同 run-start 的 prompt 语义）。 */
    public static final String GUIDE_PROMPT_FIELD = "prompt";

    /** 引导回复的呈现标签键（「平台」——非智能体角色，随事件呈现）。 */
    public static final String GUIDE_LABEL_FIELD = "label";

    // ---------- 消息部件（parts 契约，词根 = agentscope 原生 part 族） ----------
    // 消息 = 有序部件集合：run 进行中 = 一条生长中的工作消息（解说文本部件 + 工具
    // 动作部件 + 步骤分组），收口定格。由 base.agentscope 的部件映射表产出。

    /** 解说文本部件：text 为完整段非增量（服务端逐段成型，段切分内核共用）。 */
    public static final String PART_TEXT = "part-text";

    /** part-text 的段文本键（完整段）。 */
    public static final String PART_TEXT_FIELD = "text";

    /**
     * 工具动作部件（动作卡）：开始/进行中/完成/失败全生命周期——动作一开始即出
     * 事件，同一动作以 toolCallId 锚定跨状态更新。
     */
    public static final String PART_ACTION = "part-action";

    /** part-action 的动作锚键（引擎工具调用 id，跨状态同值）。 */
    public static final String PART_ACTION_TOOL_CALL_FIELD = "toolCallId";

    /** part-action 的工具名键（引擎原生名，前端图标映射用）。 */
    public static final String PART_ACTION_TOOL_NAME_FIELD = "toolName";

    /** part-action 的生命周期状态键（值 = PART_ACTION_STATE_* 常量）。 */
    public static final String PART_ACTION_STATE_FIELD = "state";

    /** 动作开始（模型发起工具调用，参数在途）——动作卡出现即「进行中」。 */
    public static final String PART_ACTION_STATE_STARTED = "started";

    /** 动作进行中（参数落定、工具执行中——label 至此为具体对象）。 */
    public static final String PART_ACTION_STATE_RUNNING = "running";

    /** 动作完成（工具结果成功返回）。 */
    public static final String PART_ACTION_STATE_COMPLETED = "completed";

    /** 动作失败（工具结果出错/被拒/中断——动作层状态；run 层唯一失败终态仍是 run-failed）。 */
    public static final String PART_ACTION_STATE_FAILED = "failed";

    /** part-action 的动作对象短语键（人话行，无时态——时态由 state 表达）。 */
    public static final String PART_ACTION_LABEL_FIELD = "label";

    /** 步骤分组部件：run 内步骤序号（模型调用边界，1 起；问答续跑为新流段重新起算）。 */
    public static final String PART_STEP = "part-step";

    /** part-step 的步骤序号键（1 起）。 */
    public static final String PART_STEP_FIELD = "step";

    /**
     * 自检播报部件（#85：「正在检查系统 → ✅/❌」）：run 收口判据核验（自检）的
     * 呈现——<b>平台侧产出</b>（不经引擎部件映射表，收口判据是平台事实：生成 = 8081
     * 探活、更新 = finish_edit 收口事实），核验开始发 {@link #PART_CHECK_STATE_CHECKING}、
     * 落定发 {@link #PART_CHECK_STATE_PASSED}/{@link #PART_CHECK_STATE_FAILED}。静默重试
     * 同构口径（#84）：尝试间核验未过不发 failed——部件停在 checking（重试信号不外泄，
     * 重复 checking 幂等）；failed 仅在末次尝试未过（超限转终态）时发，与
     * {@link #RUN_FAILED} 同窗口到达。状态终值 = 探活结果，可被收尾统计消费（#88
     * 轮末统计行）。
     */
    public static final String PART_CHECK = "part-check";

    /** part-check 的核验状态键（值 = PART_CHECK_STATE_* 常量）。 */
    public static final String PART_CHECK_STATE_FIELD = "state";

    /** 核验进行中（收口判据核验开始——「正在检查系统」）。 */
    public static final String PART_CHECK_STATE_CHECKING = "checking";

    /** 核验通过（收口判据落定，run-finish 随之放行）。 */
    public static final String PART_CHECK_STATE_PASSED = "passed";

    /** 核验未过（仅末次尝试——超限转终态，与 run-failed 同窗口）。 */
    public static final String PART_CHECK_STATE_FAILED = "failed";

    /**
     * 消息附件部件（契约预留，#97 圈注）：圈注锚随消息发送的载荷位——结构化定位
     * + 标注类型 + 可选评语，锚载荷 schema 随 SSE事件清单定形。本票只占契约位，
     * 无生产方（圈注管道随 #97 落地）。
     */
    public static final String PART_ATTACHMENT = "part-attachment";

    private AgentEventTypes() {
    }
}
