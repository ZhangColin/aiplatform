package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.cartisan.core.exception.ApplicationException;

import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentResume;
import com.semgravio.aiplatform.base.agentscope.AgentSessionExecutor;
import com.semgravio.aiplatform.base.agentscope.AgentSuspension;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.UsageContext;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.SegmentSettlementAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.SegmentType;
import com.semgravio.aiplatform.business.order.application.OrderQueryAppService;
import com.semgravio.aiplatform.business.project.application.dto.command.MessageAttachment;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.DesignScope;
import com.semgravio.aiplatform.business.project.domain.model.StyleDirections;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 主智能体单会话编排（#86 并轨，ADR 0006「单一主智能体+委派式执行」）：主智能体
 * 是平台进程内对话智能体（AgentScope HarnessAgent，经 {@link AgentscopeAgentClient}
 * 直调——编排缝极薄，无中间端口层），与用户的全部对话——建项目开场、追问、答询、
 * 受理意见、PRD 撰写修订、需求侧判定——都续同一会话，连续不换会话。
 *
 * <p><b>会话寻址（projectId → 主智能体会话的稳定绑定）</b>：sessionId =
 * {@code main-{projectId}} 无表派生、userId = 项目 owner（状态槽位
 * (userId, sessionId) 跨轮一致，谁触发都不劈叉上下文；cat_agent_state 承载，
 * 平台重启后同标识恢复）。工作区解析为<b>只读面</b>（主智能体永不读写沙箱代码——
 * 内核文件/shell 工具结构性关闭；PRD 写入走 savePrd 业务工具自带通道）。计量
 * （dims 终态口径 {@link UsageDims}，agentKind=main）与智能体资产（ask_user /
 * savePrd + 只读四件，按配置发放）同归本编排。</p>
 *
 * <p><b>车道语义（入口三分类归 {@link DispatchAppService}，本服务只收分岔后的
 * 轮）</b>：咨询零产物短路——{@link #answerInquiry} 同会话直答，不锚意见、不派
 * 更新 run、不受守卫（订单冻结 / 挂起问答只拦意见链）。挂起问答期间对话区输入
 * 即作答（CONTEXT.md「挂起问答」）：问答卡已呈现时前端直发作答通道；<b>渲染
 * 竞态窗口</b>（挂起事件已发、卡片未及呈现）里经派发口到达的咨询经
 * {@link #suspendedQuestions} 会合锚<b>转作答复续跑</b>——同一挂起 run 与工具面、
 * 咨询文本即答复文本，与作答通道完全同路（模型按协议消化非答复文本，可答可
 * 再问）；不炸对话、不出错误气泡。仅平台重启丢锚且挂起仍在的边角同步 409
 * PRJ_024 指路作答（run 无表丢 runId，无法代答）。</p>
 *
 * <p><b>链必达收口（#43；#101 生成无门→#332 生成加门→#462 去确认门→<b>#542
 * 首建派发门（ADR-0048/0049）</b>）</b>：主智能体无派发权——生成与更新 run 的
 * 派发都不在模型手里，平台在意见轮落定后观测收口（无挂起问答）即按项目态派发：
 * 已生成派更新 run（交接物 = 用户意见原文 + 需求侧判定结果——PRD 改没改、改了
 * 什么，见 {@link #dispatchOnTurnClose}，受理即派不动）；未生成但 PRD 已产出按
 * <b>快道事实分岔</b>（#542——自动派发两入口整体退役、全域无无条件自动路径）：
 * 快道（savePrd 的 fastLane 工具调用事实——试用态＝用户明确收敛令〔2026-11-17
 * 起未答满五问唯一合法提前收口形〕；付费态＝零必答短路/「直接开始做」视同确认，
 * {@link FastLaneFacts}）直派首次生成 run（
 * {@link GenerationAppService#dispatchGenerationOnTurnClose}，在途静默跳过＋意见
 * 入队 #360）；自然收口<b>不派发</b>——试用态置岔路口状态位（
 * {@link BuildGateAppService#ensureForkPending}，三选卡持久可回、「立即生成」即
 * 点火）、付费态确认门读面在场（PRD 文档面按钮确认即派发）；未产出 PRD 止于
 * 对话（访谈期常态）。判定结果从工具调用事实观测（{@link PrdRevisionFacts}
 * {@link FastLaneFacts}），不新增模型自报结论的面；守卫沿用（未产出 PRD 止于
 * 对话、归档拒、在途排队合并/静默跳过，归 {@link IterationAppService} /
 * {@link GenerationAppService}）。</p>
 *
 * <p><b>访谈轮收口硬契约与悬空接管（ADR-0032，#373）</b>：需求梳理期（未出 PRD）
 * 意见轮与问答作答复跑轮的合法收口只有两形——再问一问（挂起问答）或产出 PRD
 * （含修订）；轮落定观测点校验，无问无 PRD 即<b>悬空</b>（savePrd 失败只存在于
 * 智能体叙述、或轮被判死中断，皆命中）——平台补一条如实状态消息（{@link
 * #danglingInterviews} 标记＋guide-reply 同族平台自述面，文案按可观测失败分形：
 * 判死「本轮被中断」／savePrd 失败「刚才写 PRD 一步失败了」／泛形，恒带「继续」
 * 指路），其后输入经 {@link DispatchAppService} 绕过入口三分类直达意见轮（主智能
 * 体把关），至下一次合法收口解除；恢复轮再悬空则标记保持、再补一条。咨询轮豁免
 * （答询零产物是常态）、迭代期（PRD 已出）不适用（有派发链与 run 留痕兜底）。</p>
 *
 * <p><b>事件桥与对话史（#89）</b>：过程事件经 {@link EventsAppService}（eventhub
 * 唯一 SSE 管道）发射，关联字段（projectId）逐事件注入——底座不解释、透传。发射
 * 失败护栏：单事件发射异常只记日志不断流（SSE 是「让 UI 活」的面，不承担正确性）；
 * 对话本身的成败以 error 事件 + 异常表达（会话执行器吞掉记日志，REST 快返回）。
 * 对话史落库的主写口在本编排：用户发言提交侧同步落（守卫全过后——失败上抛撤回
 * REST 面，「落库 ⟺ 说过」不漂移），智能体回复段与问答卡在轮落定点按对话序落
 * （{@link ConversationHistoryAppService.TurnRecorder}——挂起段文本先落、问答卡
 * 随后），收尾卡归编码 run 收口（{@code CoderRunAttempts}）。</p>
 */
@Service
@Slf4j
public class MainAgentAppService {

    /** 主智能体会话标识派生前缀（projectId → main-{projectId}，稳定绑定勿动）。 */
    public static final String SESSION_PREFIX = "main-";

    private final ProjectRepository projectRepository;
    private final AgentscopeAgentClient agentClient;
    private final AgentEventBridge eventBridge;
    private final AgentSessionExecutor sessionExecutor;
    private final ProjectKnowledgeAppService knowledgeAppService;
    private final AgentConfigAppService agentConfigs;
    private final ModelConfigAppService modelConfigs;
    private final OrderQueryAppService orderQueryAppService;
    private final IterationAppService iterationAppService;
    private final GenerationAppService generationAppService;
    private final DesignProcessAppService designProcessAppService;
    private final PrdRevisionFacts prdRevisions;
    private final ConversationHistoryAppService conversationHistory;
    private final MainAgentProperties mainAgentProperties;
    private final SedimentProposalFacts sedimentProposals;
    private final SegmentSettlementAppService segmentSettlement;
    private final InterviewQuotaAppService interviewQuota;
    private final BillingAppService billingAppService;

    /** 快道事实（#542）：savePrd 的 fastLane 工具调用事实——收口观测分岔快道直派/自然收口。 */
    private final FastLaneFacts fastLaneFacts;

    /** 岔路口与派发门（#542）：自然收口置岔路口状态位（试用态出卡）。 */
    private final BuildGateAppService buildGateService;

    /**
     * 挂起交换的意见锚（sessionId → 交接物意见腿文本）：意见原文随会话任务落锚
     * （任务首行 put，随执行器串行——意见轮在途的新意见各自成轮，后写不覆盖在途
     * 轮的锚，#54），追问挂起期间的答复逐条并入（{@link #appendOpinionReply}）
     * ——收口派发的任务即锚的终值；收口即消费，轮炸即清（失败不留锚，重提即
     * 兜底）。进程内态，重启丢锚（续跑收口降级为仅末条答复，实质由 PRD 承载）。
     */
    private final Map<String, String> opinionExchanges = new ConcurrentHashMap<>();

    /**
     * 挂起问答的会合锚（sessionId → 本进程内最近一次 ask_user 挂起事实）：轮落定
     * 点维护（{@link #opinionTurn} 的 converse 与 {@link #answerQuestion} 的 resume
     * 返回处——问答挂起即落锚、收口或再挂起即覆盖/清除，随会话执行器串行无并发
     * 写）。用途：答询轮撞挂起（渲染竞态窗口）转作答复续跑——锚携带挂起 run 与
     * engineRef（run 无表，重启即丢——丢锚回落 409 指路口径）。
     */
    private final Map<String, SuspendedQuestion> suspendedQuestions = new ConcurrentHashMap<>();

    /** 一次 ask_user 挂起的会合事实（转答复续跑的入参重建源）。 */
    private record SuspendedQuestion(String runId, AgentSuspension suspension) {
    }

    /**
     * 访谈悬空标记（ADR-0032，#373）：需求梳理期（未出 PRD）用户轮悬空收口（无挂起
     * 问答且无 PRD 产出——合法收口只有两形）即置；合法收口（挂起问答或 PRD 产出）
     * 即清。标记在场时入口派发（{@link DispatchAppService}）绕过三分类直达意见轮。
     * 进程内态，重启丢标（同意见锚取舍——重启后悬空输入回分类口径，泛形兜底文案
     * 仍在）；key = 会话（projectId 派生，项目间天然隔离）。
     */
    private final Set<String> danglingInterviews = ConcurrentHashMap.newKeySet();

    /**
     * 额度收口的纠偏轮推进账（#538，sessionId → 已派轮数）：收口作答续跑/收口微指令
     * 轮经 {@link #dispatchOnTurnClose} 重入推进（再挂起＝再纠偏），封顶
     * {@link #CORRECTIVE_ROUND_CAP} 轮后播报悬空（防烧 token，同 ADR-0036 姿态）。
     * 收口（PRD 产出）/悬空播报即清。进程内态，重启丢账（重试上限重置为已接受边角）。
     */
    private final Map<String, Integer> quotaCorrectiveRounds = new ConcurrentHashMap<>();

    /**
     * 沉淀兜底盘问记账（#460 数据飞轮采集层）：用户轮合法收口（挂起问答在场 / PRD
     * 产出 / 迭代期受理）且本轮主智能体无 propose_sediment 调用事实时置位（
     * 「该提未提」——平台不判该不该提，只记提没提）；下一主智能体对话轮命令构建时
     * 消费（system prompt 尾附轻提醒，{@link #mainCommand}），提醒非硬门——不设
     * 拦、不播报、用户无感知。进程内态，重启丢记账（协议软指引仍在，不影响）。
     */
    private final Set<String> sedimentNudges = ConcurrentHashMap.newKeySet();

    /** 沉淀兜底盘问尾注（#460）：随主智能体 system prompt 尾附——提醒模型回顾
     * 未记录的沉淀物；软提醒不绑时刻，模型可忽略。 */
    static final String SEDIMENT_NUDGE_TAIL =
            "\n\n（平台轻提醒，无需向用户提及：回顾此前的对话，若有值得跨项目复用的"
                    + "需求案例（CASE）/功能模块（MODULE）/业务规则（RULE）而尚未记录，"
                    + "可随时调 propose_sediment 写入采集层草稿；没有就忽略本提醒，"
                    + "继续本轮工作。）";

    /** 访谈协议段·试用轨（#538，ADR-0048 协议分轨；2026-11-17 用户裁决改为五问
     * 承诺——5 不只是上限，也是常态题量，除非用户明确不要）：不数数（额度由平台
     * 程序计数）、不预告、不预设题库。 */
    static final String TRIAL_INTERVIEW_PROTOCOL_TAIL =
            "\n\n【访谈协议·试用轨】（平台按项目付费状态随本轮附带，属工作协议一部分）"
                    + "本项目处于试用状态：访谈固定五问，平台向用户明示「已答 N/5 问」"
                    + "进度——五问是平台对用户的承诺：除用户明确要求提前收口（说「不用"
                    + "再问了/直接出 PRD/直接开始做」＝收敛令，此时 savePrd 的 fastLane "
                    + "传 true）外，必须问满 5 个不同缺口再产出 PRD。即便你判断需求已经"
                    + "明确或没有必答题也不得提前收口——未答满五问且无收敛令的 savePrd "
                    + "调用会被平台拒绝（错误结果可见，照其指引继续 ask_user 提问）。"
                    + "你不要自己计数（已答数以平台拒绝结果与「收口指令」为准）、不预告"
                    + "剩余题数、不预估还有几问。问题按重要性排序：优先问缺了会建错方向"
                    + "的，其次问涉用户独特事实与偏好的（工作协议第 1 条两类口径），必答"
                    + "两类问尽后接着问对方案影响最大的范围/优先级/偏好类实质澄清；不为"
                    + "凑数问无关紧要的问题，平台不预设题库。第五问答复后额度即用尽"
                    + "——平台会注入「收口指令」段，此后 ask_user 调用同样会被平台拒绝"
                    + "（错误结果可见、提问不生效也不会呈现给用户）：无论收到收口指令还是"
                    + "ask_user 拒绝结果，都立即按其指引停止提问、补全缺口并 savePrd。";

    /** 访谈协议段·付费轨（#538）：付费翻转后回原协议——#449 题量软目标原文。 */
    static final String PAID_INTERVIEW_PROTOCOL_TAIL =
            "\n\n【访谈协议·付费轨】（平台按项目付费状态随本轮附带，属工作协议一部分）"
                    + "题量软目标 5 个以内（典型约 5 个）——是软目标不是硬上限，可超过、"
                    + "题量不固定、理想趋零；不预告剩余题数、不设硬顶。";

    /**
     * 风格方向库目录尾注（#543，ADR-0052 切片①——「目录经每轮 systemPrompt 动态装配
     * 注入、正本单源在平台常量」）：每轮主智能体命令尾附目录视图（方向名＋领域
     * 气质清单＋选取协议句），与两轨访谈协议段同缝共存不冲突；访谈期供写 PRD 时
     * 代选、迭代期供「想换风格」意见走对话修订——全程不加轮次、不占五问额度。
     */
    static final String STYLE_DIRECTION_CATALOG_TAIL = StyleDirections.catalogMarkdown();

    /** 收口指令（#538，ADR-0048「第 5 问答完由编排层注入收口指令」）：额度用尽后
     * 随主智能体轮 system prompt 尾附——停止提问、剩余必答缺口按推荐补全、立即
     * 梳理产出 PRD。 */
    static final String QUOTA_CLOSURE_TAIL =
            "\n\n【收口指令】（平台注入：访谈提问额度已用尽）停止提问——不再调用 "
                    + "ask_user。剩余必答缺口全部按推荐补全（口径同工作协议第 3 条：以"
                    + "专业身份直陈决定本身、禁用「假设/如果/我猜」字眼、对应用逻辑与 UI "
                    + "操作的自洽负推理责任，不是机械填推荐）；随后立即 load_skill_through_path "
                    + "加载 prd-writing 技能（path 传 SKILL.md）并调用 savePrd 保存完整 PRD"
                    + "（content 传完整 PRD markdown 全文，summary 必传：说明覆盖了什么、"
                    + "哪些缺口按推荐补全）；PRD「关键约束」章的风格方向行按随附「风格方向"
                    + "库·目录」选与项目领域气质最匹配的方向——缺省套仅当其余方向无一贴近"
                    + "时才兜底，不得因收口赶工省事选缺省（2026-11-18 收口赶工落缺省套实案"
                    + "修复，ADR-0052 补记）。保存成功后向用户输出简短总结（PRD 已产出＋核心要点＋按推荐"
                    + "补全了哪些缺口）。除此之外本轮不要再提问。";

    /** 入口分类调用的计量锚（#537 段收口接线）：projectId → 本次派发的分类 runId——
     * 翻转后判定轮同计费边界，随该轮的主智能体轮结算入账（收口消费即清，同
     * {@code opinionExchanges} 的进程内锚形态）。 */
    private final Map<Long, String> classifyRuns = new ConcurrentHashMap<>();

    /** 悬空补救文案（ADR-0032）：判死/失败收口形——点名「本轮被中断」。 */
    static final String DANGLING_REMEDY_INTERRUPTED =
            "本轮被中断了，需求梳理还没有完成。你可以说「继续」，我们接着梳理。";

    /** 悬空补救文案（ADR-0032）：savePrd 关键步失败形——点名写 PRD 失败。 */
    static final String DANGLING_REMEDY_PRD_FAILED =
            "刚才写 PRD 一步失败了，梳理成果还没有保存。你可以说「继续」，我们接着来。";

    /** 悬空补救文案（ADR-0032）：无可观测失败泛形。 */
    static final String DANGLING_REMEDY_GENERIC =
            "上一轮没有正常完成，需求梳理还没有到位。你可以说「继续」，我们接着梳理。";

    public MainAgentAppService(ProjectRepository projectRepository,
            AgentscopeAgentClient agentClient, AgentEventBridge eventBridge,
            AgentSessionExecutor sessionExecutor, ProjectKnowledgeAppService knowledgeAppService,
            AgentConfigAppService agentConfigs, ModelConfigAppService modelConfigs,
            OrderQueryAppService orderQueryAppService,
            IterationAppService iterationAppService, GenerationAppService generationAppService,
            DesignProcessAppService designProcessAppService,
            PrdRevisionFacts prdRevisions,
            ConversationHistoryAppService conversationHistory,
            MainAgentProperties mainAgentProperties, SedimentProposalFacts sedimentProposals,
            SegmentSettlementAppService segmentSettlement,
            InterviewQuotaAppService interviewQuota, BillingAppService billingAppService,
            FastLaneFacts fastLaneFacts, BuildGateAppService buildGateService) {
        this.projectRepository = projectRepository;
        this.agentClient = agentClient;
        this.eventBridge = eventBridge;
        this.sessionExecutor = sessionExecutor;
        this.knowledgeAppService = knowledgeAppService;
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
        this.orderQueryAppService = orderQueryAppService;
        this.iterationAppService = iterationAppService;
        this.generationAppService = generationAppService;
        this.designProcessAppService = designProcessAppService;
        this.prdRevisions = prdRevisions;
        this.conversationHistory = conversationHistory;
        this.mainAgentProperties = mainAgentProperties;
        this.sedimentProposals = sedimentProposals;
        this.segmentSettlement = segmentSettlement;
        this.interviewQuota = interviewQuota;
        this.billingAppService = billingAppService;
        this.fastLaneFacts = fastLaneFacts;
        this.buildGateService = buildGateService;
    }

    /**
     * 主智能体会话建立轮（建项目自动开场专用）：绑定 {@code main-{projectId}}
     * 之时做知识命中注入——query = 初始需求原文，命中块落会话缓存并接 system prompt
     * 尾部（知识是背景非指令）；检索失败降级为空注入，访谈照常开始（#5 决议①）。
     * 一次切入一次注入：后续轮（{@link #runOpinionTurn} / {@link #answerInquiry}）
     * 与问答续跑（{@link #answerQuestion}）复用同一注入块，不重检索不重追加。
     *
     * @throws ApplicationException PRJ_001 项目不存在；守卫组同 {@link #runOpinionTurn}
     *                              （自动开场仅 PRJ_001 可实际发生）
     */
    public MainAgentRun startConversation(Long projectId, String requirement) {
        knowledgeAppService.establishSessionInjection(projectId, requirement);
        return opinionTurn(projectId, requirement, MessageAttachment.NONE);
    }

    /**
     * 派发入口全局守卫（#51 守卫后移）：对话区开着的最低门槛——项目存在 / 未归档
     * （归档 = 对话区物理关闭，咨询与兜底也停）。订单冻结（ORD_006）与挂起问答
     * （PRJ_024）只拦意见链：意见分岔自带同款守卫（{@link #opinionTurn}，防御
     * 纵深），咨询与兜底随时可答（CONTEXT.md「派发」）。已知代价：被拒意见先烧
     * 一次 flash 分类调用（秒级轻调用，接受）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（对话区关闭）
     */
    public Project requireDispatchableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        return project;
    }

    /**
     * 悬空标记读口（入口派发接管判定，ADR-0032，#373）：在即对话区输入绕过入口
     * 三分类直达意见轮——悬空期短确认（「OK」「可以」）不再被无状态分类吃进兜底、
     * 回与项目状态无关的定型文案（「装忙复读」的症状面）；宁进意见链有主智能体
     * 把关。标记合法收口即清（生命周期见 {@link #danglingInterviews}）。
     */
    public boolean interviewDangling(Long projectId) {
        return danglingInterviews.contains(sessionIdOf(projectId));
    }

    /**
     * 跑一轮意见受理（意见链分岔，prompt 即用户侧输入）：会话执行器异步提交即
     * 返回（runId 随响应回，过程事件经 SSE；失败经 error 事件表达不炸调用方）。
     * system prompt = 主智能体配置 + 会话注入块（未建立/空注入/重启后 = 裸配置）。
     * 轮收口（无挂起问答且项目已生成）由平台观测自动派更新 run。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（对话区关闭）；
     *                              ORD_006 订单处理中（下单即冻结迭代，取消即解冻）；
     *                              PRJ_024 挂起问答待答（同步 409 指路作答，#40 / ADR-0005）
     */
    public MainAgentRun runOpinionTurn(Long projectId, String prompt,
            List<MessageAttachment> attachments) {
        return opinionTurn(projectId, prompt, attachments);
    }

    /** 无附件的意见轮（纯文字发言——#97 之前与测试既有口径）。 */
    public MainAgentRun runOpinionTurn(Long projectId, String prompt) {
        return runOpinionTurn(projectId, prompt, MessageAttachment.NONE);
    }

    /**
     * 应答一条咨询（咨询链分岔，零产物短路，#47 语义随 #86 并轨单会话）：同一
     * {@code main-{projectId}} 会话直答——不锚意见、不派更新 run、不受订单冻结与
     * 挂起问答守卫（只拦意见链）。异步提交（runId 随响应回，回答经 SSE 到达）。
     * 项目事实由派发入口守卫后的聚合携带（owner / 工作区寻址不入前端信）。
     *
     * <p><b>渲染竞态收敛</b>：挂起问答期间对话区输入即作答——问答卡已呈现时前端
     * 直发作答通道；卡片未及呈现的窗口里经派发口到达的咨询经 {@link
     * #suspendedQuestions} 转作答复续跑（同挂起 run 与工具面，咨询文本即答复文本）
     * ——同一输入无论卡片渲染快慢行为一致，不出错误气泡。丢锚重启边角（挂起仍在
     * 但 runId 已失）同步 409 PRJ_024 指路作答。</p>
     */
    public MainAgentRun answerInquiry(Project project, String question,
            List<MessageAttachment> attachments) {
        Long projectId = project.getId();
        String sessionId = sessionIdOf(projectId);
        // 圈注随咨询同句发送：渲染进答复续跑/答询 prompt（结构化定位喂主智能体）
        String annotated = question + AttachmentPrompt.renderSuffix(attachments);
        SuspendedQuestion pending = suspendedQuestions.get(sessionId);
        if (pending != null) {
            MainAgentAppService.log.info("[main] 项目 {} 答询撞挂起问答（渲染竞态窗口），转作答复续跑（runId={}）",
                    projectId, pending.runId());
            answerQuestion(projectId, pending.runId(), pending.suspension().engineRef(),
                    pending.suspension().toolCalls(), annotated);
            return new MainAgentRun(pending.runId());
        }
        if (agentClient.hasAskingToolCall(project.ownerUserId(), sessionId)) {
            // 丢锚重启边角：挂起仍在但本进程没有挂起事实（runId 已失，无法代答）
            // ——同步 409 指路作答，好过异步错误气泡
            throw new ApplicationException(ProjectMessage.QUESTION_PENDING);
        }
        String runId = EventsAppService.newRunId();
        conversationHistory.recordUserUtterance(projectId, runId, question, attachments);
        AgentCommand command = mainCommand(project, runId, annotated);
        // 零产物：仅对话（答询协议在主智能体配置内——查证只读工具 + 据实作答）
        sessionExecutor.submit(sessionId, () -> {
            try {
                ConversationHistoryAppService.TurnRecorder recorder =
                        conversationHistory.recorder(projectId, eventBridge.sink(project));
                AgentReply reply = agentClient.converse(command, recorder);
                recorder.settle(runId, reply);
            }
            finally {
                settleTurn(projectId, runId); // #537 段收口结算：咨询轮翻转后计费——成败/停止都结算
            }
        });
        return new MainAgentRun(runId);
    }

    /** 无附件的答询轮（纯文字咨询——#97 之前与测试既有口径）。 */
    public MainAgentRun answerInquiry(Project project, String question) {
        return answerInquiry(project, question, MessageAttachment.NONE);
    }

    /**
     * 问答答复续跑（ask_user 挂起的恢复）：挂起轮的 runId/engineRef 与待确认工具
     * 清单（question-raised 事件 data.toolCalls 形状，前端问答卡回传）+ 用户答复 →
     * ConfirmResult 批复续跑（续跑续在同一 run 上收口，事件序含答复后的下一问或
     * 收口）。恢复私货（配置/owner/工作区/计量）从项目侧事实重建，不信前端。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（对话区关闭）；
     *                              ORD_006 订单处理中（下单即冻结迭代，取消即解冻）
     */
    public void answerQuestion(Long projectId, String runId, String replyId,
            List<Map<String, Object>> pendingToolCalls, String answerText) {
        Project project = requireUpdatableProject(projectId);
        String sessionId = sessionIdOf(projectId);
        conversationHistory.recordAnswer(projectId, runId, answerText);
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        ModelCandidateQueue model = modelConfigs.resolveQueue(main.modelConfigKey());

        AgentResume resume = new AgentResume(
                runId,
                sessionId,
                project.ownerUserId(),
                Long.toString(project.getWorkspaceId()),
                model.modelString(),
                model.channel(),
                model, // #557 续跑与首轮同队列（failover 装配面）
                main.systemPrompt() + knowledgeAppService.sessionTailOf(projectId)
                        + interviewTailsOf(project) + STYLE_DIRECTION_CATALOG_TAIL,
                replyId,
                pendingToolCalls.stream()
                        .map(toolCall -> AgentscopeAgentClient.answeredToolCall(toolCall, answerText))
                        .toList(),
                answerText,
                usageContextOf(projectId, sessionId),
                AgentProfile.MAIN.key(),
                /* workspaceReadOnly= */ true,
                /* workspaceNoShell= */ false,
                main.toolSpec(),
                // #371 续跑与 converse 同口径：与首轮命令同源阈值（活超时接管续跑流）
                mainAgentProperties.getSilenceTimeout(),
                mainAgentProperties.getToolSilenceTimeout());
        appendOpinionReply(sessionId, answerText);
        ConversationHistoryAppService.TurnRecorder recorder =
                conversationHistory.recorder(projectId, eventBridge.sink(project));
        sessionExecutor.submit(sessionId, () -> {
            try {
                AgentReply reply = agentClient.resume(resume, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                // 续跑失败同轮失败口径（#83 起 resume 失败上抛）：清锚不派发——error
                // 事件已由 resume 内发出，用户重提即兜底（不自动重试）
                settleTurn(projectId, runId); // 失败也扣（#537 段收口结算——用量已实耗）
                failUserTurn(projectId, sessionId, runId);
                throw e;
            }
            dispatchOnTurnClose(projectId, sessionId, runId, /* platformRound= */ false);
        });
    }

    /** 一轮主智能体对话的运行标识（前端挂智能体事件 ?runId= 的锚）。 */
    public record MainAgentRun(String runId) {
    }

    /**
     * 终点切换前置守卫（#285）：切换重产轮与意见轮同会话同引擎约束——挂起问答
     * 在即同步 409 指路作答。归位在切换编排落库<b>之前</b>调用（拒绝即零副作用
     * ——终点属性不动）；{@link #requestEndpointShift} 提交前同守卫兜竞态。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              ORD_006 订单处理中；PRJ_024 挂起问答待答
     */
    public void requireEndpointShiftable(Long projectId) {
        Project project = requireUpdatableProject(projectId);
        requireNoPendingQuestion(project, sessionIdOf(projectId));
    }

    /**
     * 终点切换重产轮（#285，设置 tab 终点控件的后置轮）：平台发起的主智能体轮
     * ——不记用户发言（同补产轮口径：对话面只见主智能体的说明，透明面可见），
     * prompt 由 {@link #endpointShiftPrompt} 按切换事实拼装（PRD 已产出＝重产指令
     * ——清单章随终点类型走形/设计硬约束入关键约束；访谈期＝转向通告）。轮收口
     * 走同一条 {@link #dispatchOnTurnClose}（生成轨道对设计类终点收口，设计过程
     * 编排归后续票；回到系统形的重产轮收口＝用户触发链的延续——
     * {@link GenerationAppService#noteFirstBuildIntent} 记意图、收口按意图再派
     * 首个构建〔#291 验收⑤，#542 非无条件自动路径口径保持〕）。守卫同意见轮（归档/订单
     * 冻结在切换编排前置判定，挂起问答在此同步 409 指路作答）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              ORD_006 订单处理中；PRJ_024 挂起问答待答
     */
    public MainAgentRun requestEndpointShift(Long projectId, String prompt) {
        Project project = requireUpdatableProject(projectId);
        String sessionId = sessionIdOf(projectId);
        requireNoPendingQuestion(project, sessionId);
        String runId = EventsAppService.newRunId();
        AgentCommand command = mainCommand(project, runId, prompt);
        // 首建派发意图（#542 用户触发面，#602 去计划形重塑）：切换重产轮收口
        // （系统终点＋PRD 在场）按意图延续派发首个构建（如定稿设计稿入起跑上下文），
        // 非无条件自动路径——收口消费即清，重启丢账用户再点一次即兜底
        generationAppService.noteFirstBuildIntent(projectId);
        sessionExecutor.submit(sessionId, () -> {
            // 同补产轮口径（#602 补产链退役后仅切换轮走本路）：本轮需求侧事实从零
            // 起算（清残留——上一轮滞留的修订事实不冒充本轮产出）
            String workspaceId = Long.toString(project.getWorkspaceId());
            prdRevisions.clear(workspaceId);
            fastLaneFacts.clear(workspaceId);
            sedimentProposals.clear(workspaceId);
            try {
                ConversationHistoryAppService.TurnRecorder recorder =
                        conversationHistory.recorder(projectId, eventBridge.sink(project));
                AgentReply reply = agentClient.converse(command, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                // 失败即清锚（同意见轮口径）：切换已落库（属性是事实）、重产轮失败
                // 经 error 事件如实呈现——后续任意 PRD 修订按技能形态规则自然收敛
                opinionExchanges.remove(sessionId);
                suspendedQuestions.remove(sessionId);
                throw e;
            }
            dispatchOnTurnClose(projectId, sessionId, runId, /* platformRound= */ true);
        });
        return new MainAgentRun(runId);
    }

    /**
     * 切换重产轮 prompt（#285）：按「前终点 → 新终点 × PRD 已否产出」拼装——PRD
     * 在即重产（清单章走形随终点：设计＝设计物清单、系统/系统＋设计＝功能清单
     * 〔后者设计硬约束入关键约束〕）；访谈期即
     * 转向通告（后续梳理按新终点收口，PRD 产出时形态自对齐）。
     */
    static String endpointShiftPrompt(ProjectEndpointType previous, ProjectEndpointType target,
            DesignScope scope, boolean prdProduced) {
        String targetName = target.getName();
        if (!prdProduced) {
            return "平台通知：项目终点类型已定为「" + targetName + "」。请向用户简短说明这一变化（"
                    + steerSentenceOf(target) + "），随后继续需求梳理；产出 PRD 时清单章按该终点类型走形"
                    + chapterHintOf(target) + "。";
        }
        StringBuilder prompt = new StringBuilder("平台请求：项目终点类型已");
        if (previous != target) {
            prompt.append("由「").append(previous.getName()).append("」");
        }
        prompt.append("切换为「").append(targetName).append("」（")
                .append(shiftSentenceOf(previous, target)).append("）。");
        if (target == ProjectEndpointType.DESIGN) {
            prompt.append("请把工作区 docs/PRD.md 修订为设计形态：清单章重写为「设计物清单」")
                    .append("（编号列出各设计物，每件附用途、尺寸、风格要点与验收要点");
            if (scope != null) {
                prompt.append("；设计范围从原功能清单的").append(scope.describe()).append("锚定");
            }
            prompt.append("），其余章节按「交付设计而非实现系统」的语境相应梳理（系统实现细节退场）。");
        }
        else if (previous == ProjectEndpointType.DESIGN) {
            prompt.append("请把工作区 docs/PRD.md 修订为系统形态：清单章重写为「功能清单」")
                    .append("（编号列出系统的页面与功能点，每点附验收要点——原设计物清单的设计内容")
                    .append("转为对应的页面与功能梳理）");
            if (target == ProjectEndpointType.SYSTEM_DESIGN) {
                prompt.append("，「关键约束」章写入设计硬约束（系统构建遵循本项目的")
                        .append("设计方向与已定稿设计）");
            }
            prompt.append("。");
        }
        else if (target == ProjectEndpointType.SYSTEM_DESIGN) {
            prompt.append("PRD 保持系统形：功能清单章不动（不另立设计物清单），请把设计硬约束补入")
                    .append("「关键约束」章——写明设计范围为功能清单的")
                    .append(scope != null ? scope.describe() : "全部页面")
                    .append("、设计经平台设计过程产出、系统构建遵循定稿设计。");
        }
        else {
            prompt.append("请把工作区 docs/PRD.md 的「关键约束」章中的设计硬约束移除（功能清单等其余章节不动）。");
        }
        prompt.append("先 load_skill_through_path 加载 prd-writing 技能（清单章按项目终点类型走形），")
                .append("再调用 savePrd 保存修订后的完整 PRD 全文（summary 必传：说明终点切换与本次修订内容）。");
        prompt.append("保存成功后向用户简短说明本次变化。");
        return prompt.toString();
    }

    /** 访谈期转向通告句（按新终点的推进语义）。 */
    private static String steerSentenceOf(ProjectEndpointType target) {
        return switch (target) {
            case DESIGN -> "本次定制交付的是设计资产包，平台不生成系统";
            case SYSTEM_DESIGN -> "先做设计、设计定稿后系统从设计稿长出";
            case SYSTEM -> "本次定制交付的是可操作系统";
        };
    }

    /** 访谈期 prompt 的清单章形态提示。 */
    private static String chapterHintOf(ProjectEndpointType target) {
        return switch (target) {
            case DESIGN -> "（设计物清单——编号列出各设计物及用途、尺寸、风格要点与验收要点）";
            case SYSTEM_DESIGN -> "（功能清单，设计硬约束写入关键约束章）";
            case SYSTEM -> "（功能清单）";
        };
    }

    /** 重产指令的切换语义句（PRD 已产出形）。 */
    private static String shiftSentenceOf(ProjectEndpointType previous, ProjectEndpointType target) {
        if (previous == ProjectEndpointType.DESIGN && target == ProjectEndpointType.SYSTEM_DESIGN) {
            return "转系统开发";
        }
        return steerSentenceOf(target);
    }

    // ---------- 内部 ----------

    private MainAgentRun opinionTurn(Long projectId, String prompt,
            List<MessageAttachment> attachments) {
        Project project = requireUpdatableProject(projectId);
        String sessionId = sessionIdOf(projectId);
        requireNoPendingQuestion(project, sessionId);

        String runId = EventsAppService.newRunId();
        // 圈注随意见同句发送：渲染进主智能体 prompt 与交接物（意见原文）——结构化
        // 定位随链下到更新 run，执行体知道改哪里
        String annotated = prompt + AttachmentPrompt.renderSuffix(attachments);
        // 对话史落库（#89）：提交守卫全过后同步落用户发言（失败上抛撤回 REST 面——
        // 「落库 ⟺ 说过」不漂移）；智能体回复段在轮落定点写（见 recordTurnReply）。
        // attachments 原始 JSON 落库供刷新回显重建圈注 chip
        conversationHistory.recordUserUtterance(projectId, runId, prompt, attachments);
        // 受理动作卡（#87）：迭代期意见轮（受理轮）开场即发受理事件——意见已接住、
        // 主智能体正在受理（追问或改 PRD 的过程呈现位，衔接轮收口自动派的更新 run
        // 工作消息）。守卫全过才发（拒绝即零事件）；访谈期意见轮是纯追问轮、咨询
        // 轮走 {@link #answerInquiry}，场景矩阵均无卡
        if (project.isGenerated()) {
            eventBridge.emitAcceptanceStarted(project, runId);
        }
        AgentCommand command = mainCommand(project, runId, annotated);
        sessionExecutor.submit(sessionId, () -> {
            // 排队成轮（#54）：锚随任务落（同会话 FIFO——后发意见的 put 排在本轮
            // 收口之后，不覆盖在途轮的锚）：意见轮在途连发的意见各自成轮、各自
            // 收口派发，撞在途更新 run 走排队合并
            opinionExchanges.put(sessionId, annotated);
            // 本轮需求侧判定从零起算（随会话执行器串行——上一轮收口消费在前，
            // 不会被本轮起跑插队 wipe）：炸轮滞留/访谈期的 savePrd 事实残留不进
            // 本轮交接物（意见锚无此滞留——失败即清，见下）
            prdRevisions.clear(Long.toString(project.getWorkspaceId()));
            fastLaneFacts.clear(Long.toString(project.getWorkspaceId()));
            sedimentProposals.clear(Long.toString(project.getWorkspaceId()));
            try {
                ConversationHistoryAppService.TurnRecorder recorder =
                        conversationHistory.recorder(projectId, eventBridge.sink(project));
                AgentReply reply = agentClient.converse(command, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                // 失败即清锚（#54，对齐「收口即消费」）：炸轮不留锚——重提即兜底，
                // 不自动重试；error 事件已由 converse 内发出（异常上抛由会话执行器吞）
                settleTurn(projectId, runId); // 失败也扣（#537 段收口结算——用量已实耗）
                failUserTurn(projectId, sessionId, runId);
                throw e;
            }
            dispatchOnTurnClose(projectId, sessionId, runId, /* platformRound= */ false);
        });
        return new MainAgentRun(runId);
    }

    /**
     * 轮落定点的挂起问答会合锚维护：问答挂起（软终点，ask_user 唯一挂起源）即落锚
     * （渲染竞态窗口里答询轮转作答复续跑的入参源）；正常收口即清锚（无可答之问）。
     */
    private void settleSuspendedQuestion(String sessionId, String runId, AgentReply reply) {
        if (reply.suspension() != null) {
            suspendedQuestions.put(sessionId, new SuspendedQuestion(runId, reply.suspension()));
        }
        else {
            suspendedQuestions.remove(sessionId);
        }
    }

    /** 主智能体对话命令（意见轮与咨询轮同构：同会话、同配置、同只读面）。 */
    private AgentCommand mainCommand(Project project, String runId, String prompt) {
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        // #460 兜底盘问投递：收口记账的会话下一轮 system prompt 尾附轻提醒（消费即清）
        return mainCommandWith(project, runId, prompt, main, modelConfigs.resolveQueue(main.modelConfigKey()),
                sedimentNudgeTailOf(project.getId()));
    }

    /** 兜底盘问尾注取口（消费即清）：记账在场附尾注，不在场附空串。 */
    private String sedimentNudgeTailOf(Long projectId) {
        return sedimentNudges.remove(sessionIdOf(projectId)) ? SEDIMENT_NUDGE_TAIL : "";
    }

    /**
     * 纠偏轮命令（#445；<b>#462 统一微指令修订</b>——ADR-0036 补记）：同主对话命令的
     * 会话/配置/计量/只读面（system prompt 与工具规格同取 MAIN 生效值——纠偏轮也是
     * 主智能体的轮），prompt 换统一纠偏微指令、模型配置按轮次解析（{@link
     * #correctiveModelOf}）。
     */
    private AgentCommand correctiveCommand(Project project, String runId,
            AgentConfigAppService.EffectiveConfig main, ModelCandidateQueue model) {
        return correctiveCommandWith(project, runId, main, model, CORRECTIVE_REDIRECT_PROMPT);
    }

    /** 纠偏命令构建（微指令参数化——#462 统一微指令与 #538 收口微指令两形同缝）。 */
    private AgentCommand correctiveCommandWith(Project project, String runId,
            AgentConfigAppService.EffectiveConfig main, ModelCandidateQueue model,
            String correctivePrompt) {
        // 纠偏轮不带沉淀兜底盘问尾注（mainCommandWith 尾注腿传空串）：纠偏是格式失格的
        // 确定性纠正，不混入沉淀提醒（记账不被消费，下轮正常投递）
        return mainCommandWith(project, runId, correctivePrompt,
                main, model, /* sedimentNudgeTail= */ "");
    }

    /**
     * 统一纠偏轮微指令（#462，ADR-0036 补记——原提问形/savePrd 失败形两条分形指令
     * 合并为一条）：悬空收口的两种格式失格（正文提问未走 ask_user／说可出 PRD 但
     * 未调 savePrd／savePrd 未落成）平台侧无可观测信号可靠区分——统一指令一次覆盖
     * 两形：教合法收口两形＋令按上轮意图立即执行其一。确定性、不解析自由文本。
     */
    static final String CORRECTIVE_REDIRECT_PROMPT =
            "平台纠正：你上一轮的收口不合法——既没有挂起问答（未调用 ask_user），"
                    + "也没有产出 PRD（未调用 savePrd）。需求梳理推进只有两个合法出口："
                    + "①要提问——必须经 ask_user 工具（question 填问题文本、header 填主题"
                    + "短标签、options 给 2-4 个候选，禁止把问题写在正文里）；②判定需求"
                    + "明确、要产出 PRD——先 load_skill_through_path 加载 prd-writing 技能，"
                    + "随即调用 savePrd（content 传完整 PRD markdown 全文，summary 必传）。"
                    + "请按你上一轮的意图立即执行其中之一：上轮想提问的，现在用 ask_user "
                    + "重新提出（可精简原文）；上轮想产出 PRD 的，现在立即保存 PRD。"
                    + "除此之外本轮不要再做别的。";

    /** 纠偏收口轮微指令（#538，ADR-0048「纠偏只导向收口不导向新问」）：第 5 问后
     * 悬空收口（无问无 PRD）的纠偏轮唯一出口＝产出 PRD——不再提供提问选项。 */
    static final String CORRECTIVE_CLOSURE_PROMPT =
            "平台纠正：访谈提问额度已用尽——你上一轮的收口不合法：既没有产出 PRD"
                    + "（未调用 savePrd），提问额度也已用尽（不再允许新的 ask_user）。"
                    + "现在唯一合法出口：剩余必答缺口全部按推荐补全（口径同工作协议第 3 条："
                    + "专业身份直陈决定本身、禁用「假设/如果」字眼、对应用逻辑与 UI 自洽负"
                    + "推理责任，不是机械填推荐），随即 load_skill_through_path 加载 "
                    + "prd-writing 技能（path 传 SKILL.md）并调用 savePrd 保存完整 PRD"
                    + "（content 传完整 PRD markdown 全文，summary 必传：说明覆盖了什么、"
                    + "哪些缺口按推荐补全）。不要再调用 ask_user 提问，除此之外不要做别的。";

    /** 收口作答文本（#538 纠偏收口）：额度用尽后模型漂移再提问——平台以收口指令
     * 代答该问题并续跑同一 run（答复经 resume 工具结果通道给模型；不落成用户作答行）。 */
    static final String CLOSURE_PLATFORM_ANSWER =
            "收口作答：访谈提问额度已用尽，本问题不再展开——请严格执行平台收口指令："
                    + "剩余必答缺口按推荐补全，立即梳理并调用 savePrd 保存 PRD（PRD「关键约束」"
                    + "章风格方向行按随附目录选与领域气质最匹配的方向，缺省套仅兜底、"
                    + "不得因收口赶工省事选缺省）。";

    /** 悬空补救文案（#538）：额度收口双失格形——恒带「继续」指路。 */
    static final String DANGLING_REMEDY_QUOTA_EXHAUSTED =
            "访谈提问的额度已经用完了，需求梳理还没有收口。你可以说「继续」，"
                    + "平台按推荐补全缺口、立即为你整理需求文档。";

    /** 主对话命令构建（配置束与解析后模型参数化——对话轮与纠偏轮共用，#445）。
     * systemPrompt/模型配置/增强工具开关经运营配置读面取生效值（#251 库值优先、
     * 缺省回落枚举默认——每轮命令构建时查，配置变更下一轮自然生效；#252 开关编入
     * toolSpec 随命令透传装配）；配置键→模型串与通道注入经 {@link ModelConfigAppService}
     * 解析（ADR-0031——供应商/凭证配置变更同轮生效）；对话轮活超时阈值取
     * {@link MainAgentProperties}（#371：携增强工具的慢轮不被总墙钟掐死）。
     */
    private AgentCommand mainCommandWith(Project project, String runId, String prompt,
            AgentConfigAppService.EffectiveConfig main, ModelCandidateQueue model,
            String sedimentNudgeTail) {
        Long projectId = project.getId();
        String sessionId = sessionIdOf(projectId);
        return new AgentCommand(
                runId,
                prompt,
                main.systemPrompt() + knowledgeAppService.sessionTailOf(projectId)
                        + sedimentNudgeTail + interviewTailsOf(project)
                        + STYLE_DIRECTION_CATALOG_TAIL,
                model.modelString(),
                model.channel(),
                sessionId,
                project.ownerUserId(),
                usageContextOf(projectId, sessionId),
                Long.toString(project.getWorkspaceId()),
                correlationOf(projectId),
                /* timeout= */ null, // #371 活超时替换一次性总墙钟（同生成轨 #322）
                mainAgentProperties.getSilenceTimeout(),
                mainAgentProperties.getToolSilenceTimeout(), // #355 工具执行窗延长预算
                AgentProfile.MAIN.key(),
                /* workspaceReadOnly= */ true,
                /* workspaceNoShell= */ false,
                /* heading= */ null,
                main.toolSpec(),
                // #557 候选队列（续跑与首轮同队列——多候选＝failover 装配面）
                model);
    }

    /** 会话标识派生（projectId → main-{projectId} 稳定绑定）。 */
    private static String sessionIdOf(Long projectId) {
        return SESSION_PREFIX + projectId;
    }

    /**
     * 访谈协议段＋收口指令尾注（#538，ADR-0048 协议分轨＋收口指令注入；2026-11-17
     * 用户裁决：试用态五问改承诺语义——5 不只是上限也是常态题量，用户明确收敛令
     * 除外）：访谈期（未产出 PRD ∧ 未生成）每轮主智能体命令按项目付费状态附协议段
     * ——试用态＝五问承诺＋重要性排序纪律、付费态＝原协议（题量软目标，#449 原文）；
     * 试用态额度用尽（{@link InterviewQuotaAppService#closureRequired}）再附收口
     * 指令（停止提问、缺口按推荐补全、立即产出 PRD）。非访谈期附空串——访谈纪律
     * 不混入迭代轮；问答续跑（{@link #answerQuestion}）与对话/纠偏轮同缝同口径。
     */
    private String interviewTailsOf(Project project) {
        if (project.getPrdProducedAt() != null || project.getGeneratedAt() != null) {
            return ""; // 非访谈期：PRD 产出即访谈收口、已生成＝迭代期
        }
        boolean trial = billingAppService.trackOf(project.getId()).trial();
        if (!trial) {
            return PAID_INTERVIEW_PROTOCOL_TAIL; // 付费翻转后回原协议（无上限）
        }
        return interviewQuota.closureRequired(project.getId())
                ? TRIAL_INTERVIEW_PROTOCOL_TAIL + QUOTA_CLOSURE_TAIL
                : TRIAL_INTERVIEW_PROTOCOL_TAIL;
    }

    /**
     * 链必达收口观测（#43，#101 生成无门自动发起→#332 生成加门→<b>#462 去确认门
     * 整段退役</b>，ADR-0039）：轮落定（对话轮收口或问答续跑收口）后观测链的走向
     * ——会话有挂起问答 = 本轮未收口（答复后续跑再判，意见锚保留）；收口前归档 =
     * 竞态守卫，静默不派。落定即按项目态自动派发：<b>已生成派更新 run</b>（交接物 =
     * {@link #opinionExchanges} 中的意见原文及追问答复 + {@link #prdRevisions} 中的
     * PRD 修订事实——本轮 savePrd 调用的 summary 终值，无调用事实即 null「未修订」）
     * ——大改小改分流的机械停派已随确认门退役（#462：范围级新草稿照派，判定权归
     * 主智能体工作协议）；<b>未生成但已产出 PRD</b>——系统终点自动派首个构建 run
     * （首次 savePrd 落盘回调已先行触发，{@link GenerationAppService#dispatchFirstDemoOnPrdProduced}；
     * 此处收口观测再兜一轮，撞在途静默跳过）。设计类终点保持原触发律（未定稿起
     * 设计过程、全部定稿起构建，入口已收起）。意见锚与修订事实同「收口即消费」
     * 清掉——生成读的是工作区 PRD 正本，不进交接物；未产出 PRD 止于对话（访谈期
     * 常态）。主智能体无派发权：模型存没存 PRD、调没调任何工具都不影响派发——链
     * 的收口在平台代码。派发失败不炸对话轨道、不恢复意见锚（收口即消费语义保持）、
     * 不自动重试——用户重提即兜底。
     *
     * <p><b>平台轮（终点切换重产）</b>：{@code platformRound} 区分平台发起的
     * 需求侧轮（终点切换重产 {@link #requestEndpointShift}）
     * 与用户对话轮。去门化后用户轮与平台轮同判据（PRD 已产出即派），平台轮标记仍
     * 保留——悬空契约与沉淀记账的平台轮豁免沿用它（ADR-0032/ADR-0038 口径不变）。</p>
     *
     * <p><b>访谈轮收口硬契约（ADR-0032，#373；纠偏链 ADR-0036＋#462 统一微指令
     * 修订）</b>：本观测点同位校验不变量——需求梳理期（未出 PRD）用户轮的合法收口
     * 只有「再问一问」（挂起问答）或「产出 PRD」两形；无问无 PRD 即悬空。命中不
     * 立即播报——先走收口纠偏链（主档＋统一纠偏微指令重派〔一条指令覆盖正文提问
     * 与「说可出 PRD 但未调 savePrd」两形——平台无可观测信号可靠区分，确定性纠正
     * 不解析自由文本〕，失格升强档兜底，封顶两轮，{@link #dispatchCorrectiveChain}），
     * 双失格才置 {@link #danglingInterviews}＋平台补救（文案按 savePrd 失败事实分形）。
     * 两合法形与迭代期、归档路径随观测清除标记（接管解除单点归一）；平台轮不入
     * 契约。轮失败（判死等异常上抛）不经本观测与纠偏链——悬空补救在轮任务的
     * catch 侧（{@link #remedyFailedInterviewTurn}，即时点名播报）。</p>
     *
     * <p><b>沉淀兜底盘问记账（#460，ADR-0038）</b>：用户轮合法收口（挂起问答在场／
     * PRD 产出／迭代期受理三形）且本轮主智能体无 propose_sediment 调用事实（
     * {@link SedimentProposalFacts}）即置 {@link #sedimentNudges} 记账——下一对话轮
     * system prompt 尾附软提醒（{@link #mainCommand} 消费即清）。不设硬门：不拦
     * 收口、不播报、用户无感知；悬空收口与平台轮不记账。</p>
     */
    private void dispatchOnTurnClose(Long projectId, String sessionId, String runId,
            boolean platformRound) {
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                return;
            }
            String workspaceId = Long.toString(project.getWorkspaceId());
            boolean quotaClosure = interviewQuota.closureRequired(projectId);
            boolean suspensionPending = agentClient.hasAskingToolCall(project.ownerUserId(), sessionId);
            if (suspensionPending && !quotaClosure) {
                // 合法收口形一（ADR-0032）：挂起问答在场＝本轮未收口——悬空标记随
                // 解除（访谈重获推进抓手，入口恢复分类）
                danglingInterviews.remove(sessionId);
                sedimentNudgeOnClose(sessionId, workspaceId, platformRound);
                return; // 挂起即段未收口：不结算（答复续跑同 runId 用量随真收口入账）
            }
            if (suspensionPending) {
                // #538 合法形收窄（ADR-0032 两形契约的条件收窄，非新增第三形）：试用态
                // 第 5 答后挂起问答不再放行——漂移的第 6 问不视作合法收口形一，落入
                // 下方纠偏收口链（收口作答收口，只导向收口不导向新问）。提问侧额度闸
                //（AskUserTool 工具点 PASSTHROUGH 回拒因、不挂起）是主防线，漂移提问
                // 正常到不了这里；本分支是其防御纵深（闸查询 fail-open / 置备竞态等
                // 边角挂起漏网时仍由收窄链兜住，实测旧形态＝第 6 问先出卡再自问自答）
                log.info("[main-close] 项目 {} 额度收口收窄：第 5 答后挂起问答不再放行"
                        + "（提问侧闸漏网，纠偏收口代答兜底）", projectId);
            }
            // #537 段收口结算：主智能体轮真收口即结算（失败/停止/平台轮同扣——失败也扣）；
            // 在挂起判定之后——问答续跑的用量同段同锚，幂等不重扣
            settleTurn(projectId, runId);
            if (project.getArchivedAt() != null) {
                clearTurnAnchors(sessionId, workspaceId); // 归档竞态守卫：静默不派
                danglingInterviews.remove(sessionId); // 归档即对话区关闭：标记随清（终态）
                return;
            }
            if (project.getGeneratedAt() == null) {
                // 未生成：PRD 已产出即平台自动派首次产出轨（轨内无门——终点类型定轨：
                // 系统终点派生成 #101、设计类终点起设计过程 #289 同律）；未产出
                // PRD 静默止于对话（访谈期常态：生成前意见链终点）。#602 起切片
                // 计划机器整体退役——生成无计划交接物，PRD 正本即全部输入
                // 意见锚取出（#291）：设计类终点的设计派发消费意见原文（在途排队/
                // 改稿受理）；生成路径不需要（生成读工作区 PRD 正本）——其余锚照
                // 「收口即消费」清掉
                String opinion = opinionExchanges.remove(sessionId);
                String prdRevisionSummary = prdRevisions.consume(workspaceId);
                if (project.getPrdProducedAt() == null) {
                    // 访谈轮收口硬契约（ADR-0032，#373）：合法收口只有「再问一问」
                    // （上方挂起检查已排除）或「产出 PRD」（本判定已排除）两形——
                    // 此处即悬空。用户轮置标记＋平台补救；平台轮（补产/切换通告）不
                    // 入契约（补产必在 PRD 后；通告轮的悬空不扩大接管面）。补救文案
                    // 按 savePrd 失败事实分形（点名/泛形），恢复轮再悬空标记保持、
                    // 再补一条
                    boolean prdSaveFailed = prdRevisions.consumeFailure(workspaceId);
                    // #538 额度收口（quotaClosure）不受 platformRound 限：收口作答续跑轮
                    // （platformRound=true）再挂起同属额度失格，须再纠偏（推进归
                    // quotaCorrectiveRounds 账）；非额度平台轮不入契约（原口径）
                    if (!platformRound || quotaClosure) {
                        // ADR-0036（#445）收口纠偏链：用户轮悬空命中不立即播报——先
                        // 自动纠偏（主档＋确定性微指令重派），失格再升强档兜底，双失
                        // 格才置标记＋平台补救（markDanglingInterview 单点收口，文案
                        // 按 savePrd 失败事实分形——点名/泛形）。纠偏轮封顶两轮防烧
                        // token；均为平台轮不入本契约（不递归）；routingEnabled=关的
                        // 纯人工定档姿态跳过升档、第 1 轮失格即播报。轮失败（异常收
                        // 口）不经本链——catch 侧 remedyFailedInterviewTurn 即时点名
                        // 播报（环境性失败重试照样失败，ADR-0032 原 rationale 保持）
                        dispatchCorrectiveChain(project, sessionId, runId, prdSaveFailed,
                                quotaClosure, suspensionPending);
                    }
                    return;
                }
                // 合法收口形二（ADR-0032）：PRD 产出（含修订）——悬空标记随解除
                danglingInterviews.remove(sessionId);
                quotaCorrectiveRounds.remove(sessionId); // #538：收口即清纠偏推进账
                sedimentNudgeOnClose(sessionId, workspaceId, platformRound);
                if (project.getEndpointType().designInvolved()
                        && !designProcessAppService.designFinalized(project)) {
                    // 设计类终点（#289 接管 #285 临时静默口径）：PRD 收口自动起
                    // 设计过程（轨内无门，设计先行——设计主线与系统＋设计同律，
                    // ADR-0025）；意见原文随派发（#291：在途即排队、清单全收口
                    // 即改稿——改稿同会话继续）
                    DesignProcessAppService.DesignRun run =
                            designProcessAppService.dispatchDesignOnTurnClose(projectId,
                                    opinion);
                    if (run != null) {
                        log.info("[main-close] 项目 {} 意见轮收口，平台自动起设计过程（{}）",
                                projectId, run.runId());
                    }
                    return;
                }
                // 首建派发门（#542 访谈②，ADR-0048/0049——自动派发两入口整体退役）：
                // 系统终点 PRD 已产出的收口按<b>快道事实</b>分岔——快道（savePrd 的
                // fastLane 工具调用事实：用户明确收敛令〔试用态未问满五问唯一合法的
                // 提前收口形〕/付费态零必答首轮直产与「直接开始做」视同确认）
                // 直派首个构建 run（急着要的给快的——不出卡不确认，在途撞车静默跳过
                // ＋意见载荷按 #360 入队不静默丢）；自然收口（判定明确含提前明确）
                // <b>不派发</b>——试用态置岔路口状态位（三选卡持久可回，「立即生成」
                // 即点火）、付费态确认门读面在场（PRD 文档面按钮确认即派发）——点火
                // 归用户，全域无无条件自动路径。设计类终点（含全部定稿）保持原触发律
                // （上方设计轨分岔已收口，设计定稿构建归 DesignProcessAppService）。
                if (fastLaneFacts.consume(workspaceId)) {
                    GenerationAppService.GenerationRun run =
                            generationAppService.dispatchGenerationOnTurnClose(projectId,
                                    new GenerationAppService.TurnOpinion(
                                            opinion, prdRevisionSummary, runId));
                    if (run != null) {
                        log.info("[main-close] 项目 {} 快道收口（用户收敛令/付费零必答/直接开始做），"
                                + "直接派发首次生成 run（{}）", projectId, run.runId());
                    }
                    return;
                }
                // 切换重产轮收口的意图延续（#542 用户触发面，#602 去计划形重塑）：
                // 意图由 {@link #requestEndpointShift} 记账——仅平台轮（切换轮本身）
                // 收口消费（同一条用户触发链的延续、条件性路径）；自然收口/无意图
                // 不派（点火归用户，岔路口卡/确认门读面在场）
                if (platformRound) {
                    GenerationAppService.GenerationRun resumed =
                            generationAppService.dispatchAfterEndpointShift(projectId,
                                    new GenerationAppService.TurnOpinion(
                                            opinion, prdRevisionSummary, runId));
                    if (resumed != null) {
                        log.info("[main-close] 项目 {} 切换重产轮收口，按用户触发链延续派发"
                                + "首次生成 run（{}）", projectId, resumed.runId());
                        return;
                    }
                }
                // 自然收口：在途意见照旧排队（#360——首 Demo 在途的反馈不静默丢），
                // 试用态置岔路口位（幂等——意见修订轮再收口卡照旧在场）
                generationAppService.queueFirstDemoTurnOpinion(projectId,
                        new GenerationAppService.TurnOpinion(opinion, prdRevisionSummary, runId));
                if (buildGateService.ensureForkPending(projectId)) {
                    log.info("[main-close] 项目 {} 试用态自然收口：岔路口三选卡在场"
                            + "（持久可回，「立即生成」即点火）", projectId);
                }
                return;
            }
            // 已生成（迭代期）：收口不变量不适用（ADR-0032——受理即派、有派发链与
            // run 留痕兜底），悬空标记随清（迭代期无访谈悬空态）
            danglingInterviews.remove(sessionId);
            sedimentNudgeOnClose(sessionId, workspaceId, platformRound);
            if (project.getEndpointType().designInvolved()
                    && !designProcessAppService.designFinalized(project)) {
                // 已生成＋设计类终点＋设计未定稿完（#291 系统中途切换的设计轨承接）：
                // 意见进设计轨（在途排队/改稿/续跑）——系统迭代让位（设计先行；
                // 全部定稿后回更新 lane，按稿对齐经定稿触发）
                String designOpinion = opinionExchanges.remove(sessionId);
                DesignProcessAppService.DesignRun run =
                        designProcessAppService.dispatchDesignOnTurnClose(projectId, designOpinion);
                if (run != null) {
                    log.info("[main-close] 项目 {} 意见轮收口，平台起设计过程（{}，系统中途切换承接）",
                            projectId, run.runId());
                }
                return;
            }
            String task = opinionExchanges.remove(sessionId);
            if (task == null || task.isBlank()) {
                return; // 锚缺失（任务内落锚先于收口，同任务序——进程内理论不可达）——防御不派
            }
            String prdRevisionSummary = prdRevisions.consume(workspaceId);
            // 大改分流（#462 去确认门——机械停派整段退役，ADR-0039/#450 裁决 A）：
            // 不再以「修订事实＋草稿漂移」停派待确认——意见轮收口一律受理即派，
            // 范围级/小反馈的判定权归主智能体（工作协议第 11 条：意见明确→修订
            // PRD 直接派；不明确→当场 ask_user 问清）。修订说明照旧进交接物。
            IterationAppService.FixDispatch dispatch =
                    iterationAppService.startFixRun(projectId, task, prdRevisionSummary);
            log.info("[main-close] 项目 {} 意见轮收口，平台自动派更新 run（{}，{}）",
                    projectId, dispatch.queued() ? "排队下一轮" : "起跑",
                    prdRevisionSummary != null ? "PRD 已修订" : "本轮无修订");
        }
        catch (RuntimeException e) {
            // 派发失败（#51 → #82 失败家族归位；#101 起同 catch 接生成与更新两路）：
            // 意见锚已消费（不恢复）、不自动重试，用户重提即兜底——失败信号归 error
            // 事件（dispatch-failed 阶段族已退役），对话面如实呈现不静默
            try {
                // 派发失败路径（非热）：归属重查一次携聚合——正常路径在 try 内已携
                projectRepository.findById(projectId).ifPresentOrElse(
                        project -> eventBridge.emitError(project, runId, "意见派发失败，请重新发送"),
                        () -> log.warn("[main-close] 项目 {} 派发失败且事件锚项目缺档，error 事件免发", projectId));
            }
            catch (RuntimeException emitFailure) {
                log.warn("[main-close] 项目 {} 派发失败事件发射失败：{}", projectId,
                        emitFailure.toString());
            }
            log.warn("[main-close] 项目 {} 收口自动派发失败（用户重提即兜底）：{}",
                    projectId, e.toString());
        }
    }

    /** 收口纠偏链封顶轮数（#445）：①主档 ②强档——防烧 token 的结构性上限。 */
    private static final int CORRECTIVE_ROUND_CAP = 2;

    /**
     * 悬空收口的自动纠偏链（ADR-0036，#445；<b>#462 统一微指令修订</b>——原提问形/
     * savePrd 失败形两条分形指令合并为一条覆盖两形的确定性微指令，ADR-0036 补记）：
     * 平台发起的纠正轮序列——把「模型纯文本提问／说可出 PRD 但未调 savePrd／
     * savePrd 未落成」的格式失格在会话锁死（首轮坏示范被后续轮自模仿延续）前扳回
     * 工具轨道。第 1 轮用主智能体生效档（flash 为主）＋统一微指令；仍失格第 2 轮
     * 升强档（路由面生效值，缺省 pro）——「flash 做不到再升 pro」与执行体路由
     * （ADR-0030）同一总姿态。成功判据＝合法收口形两形（挂起问答在场或 PRD 已产出，
     * {@link #correctiveSettled}）；每轮新 runId、会话执行器串行、回复照常落对话史。
     * 链不占悬空标记——标记双失格才置（接管面不扩大）；播报文案仍按 savePrd 失败
     * 事实分形（点名/泛形——{@code prdSaveFailed} 只进文案，不再选指令）。
     */
    private void dispatchCorrectiveChain(Project project, String sessionId, String originRunId,
            boolean prdSaveFailed, boolean quotaClosure, boolean suspensionPending) {
        if (quotaClosure) {
            // #538 额度收口分岔：只导向收口不导向新问（非额度链的形一/形二微指令不适用）
            dispatchQuotaCorrective(project, sessionId, originRunId, suspensionPending);
            return;
        }
        submitCorrectiveRound(project, sessionId, originRunId, prdSaveFailed, /* attempt= */ 1);
    }

    /**
     * 纠偏收口链（#538，ADR-0048「第 5 问后纠偏只导向收口不导向新问」）：额度用尽
     * 的失格收口唯一出口＝产出 PRD。两形——挂起在场（模型漂移又提问）：平台以收口
     * 指令代答该问题并续跑同一 run（引擎 ASKING 态拒新 converse，代答续跑是唯一
     * 通路；同缺口重派不另计——计数读口按缺口标识去重）；无挂起（死收口）：收口
     * 微指令新轮（主档起、失格升强档，同 ADR-0036 姿态）。推进经重入收口观测（代答/
     * 微指令轮的 {@link #dispatchOnTurnClose} 再走收窄链）累积
     * {@link #quotaCorrectiveRounds} 账，封顶两轮播报悬空（防烧 token）。
     */
    private void dispatchQuotaCorrective(Project project, String sessionId, String originRunId,
            boolean suspensionPending) {
        int attempt = quotaCorrectiveRounds.merge(sessionId, 1, Integer::sum);
        if (attempt > CORRECTIVE_ROUND_CAP) {
            quotaCorrectiveRounds.remove(sessionId);
            markDanglingInterview(project, originRunId, DANGLING_REMEDY_QUOTA_EXHAUSTED);
            return;
        }
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        ModelCandidateQueue model = correctiveModelOf(main, attempt);
        if (model == null) {
            // 路由关的纯人工定档姿态：不自动升档（第 1 轮失格即播报，同非额度链语义）
            quotaCorrectiveRounds.remove(sessionId);
            markDanglingInterview(project, originRunId, DANGLING_REMEDY_QUOTA_EXHAUSTED);
            return;
        }
        if (suspensionPending) {
            resumeWithPlatformClosureAnswer(project, sessionId, originRunId);
            return;
        }
        converseClosureCorrective(project, sessionId, main, model);
    }

    /**
     * 收口作答收口（#538）：以 {@link #CLOSURE_PLATFORM_ANSWER} 代答挂起问答并续跑
     * 同一 run——收口指令随 system prompt 尾附（额度用尽态，{@link #interviewTailsOf}
     * 同缝）；代答只置位问答卡（不落用户作答行——平台话语不进对话面用户侧）。续跑
     * 收口走常规 {@link #dispatchOnTurnClose}（platformRound=true）：产出 PRD 即收口
     * 形二；再挂起即收窄重入（推进账封顶两轮后播报悬空）。挂起锚缺失（重启丢 runId
     * 边角）无法代答——直接播报悬空（挂起卡仍可由用户作答，作答续跑随附收口指令）。
     */
    private void resumeWithPlatformClosureAnswer(Project project, String sessionId,
            String originRunId) {
        Long projectId = project.getId();
        SuspendedQuestion pending = suspendedQuestions.get(sessionId);
        if (pending == null) {
            quotaCorrectiveRounds.remove(sessionId);
            markDanglingInterview(project, originRunId, DANGLING_REMEDY_QUOTA_EXHAUSTED);
            return;
        }
        conversationHistory.markAnswered(pending.runId());
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        ModelCandidateQueue model = modelConfigs.resolveQueue(main.modelConfigKey());
        AgentResume resume = new AgentResume(
                pending.runId(),
                sessionId,
                project.ownerUserId(),
                Long.toString(project.getWorkspaceId()),
                model.modelString(),
                model.channel(),
                model, // #557 续跑与首轮同队列（failover 装配面）
                main.systemPrompt() + knowledgeAppService.sessionTailOf(projectId)
                        + interviewTailsOf(project) + STYLE_DIRECTION_CATALOG_TAIL, // 收口指令在场（额度用尽态）＋风格目录尾注
                pending.suspension().engineRef(),
                pending.suspension().toolCalls().stream()
                        .map(toolCall -> AgentscopeAgentClient.answeredToolCall(
                                toolCall, CLOSURE_PLATFORM_ANSWER))
                        .toList(),
                CLOSURE_PLATFORM_ANSWER,
                usageContextOf(projectId, sessionId),
                AgentProfile.MAIN.key(),
                /* workspaceReadOnly= */ true,
                /* workspaceNoShell= */ false,
                main.toolSpec(),
                // 续跑与 converse 同口径：同源静默判死阈值（#371 活超时接管续跑流）
                mainAgentProperties.getSilenceTimeout(),
                mainAgentProperties.getToolSilenceTimeout());
        String closureRunId = pending.runId();
        ConversationHistoryAppService.TurnRecorder recorder =
                conversationHistory.recorder(projectId, eventBridge.sink(project));
        sessionExecutor.submit(sessionId, () -> {
            try {
                AgentReply reply = agentClient.resume(resume, recorder);
                settleSuspendedQuestion(sessionId, closureRunId, reply);
                recorder.settle(closureRunId, reply);
            }
            catch (RuntimeException e) {
                // 代答续跑炸：不播报不置标——收口观测见挂起仍在即重入纠偏（推进账
                // 已记一轮，封顶两轮后播报；error 事件已由 resume 内发出）
                log.warn("[main-close] 项目 {} 收口作答续跑失败（收窄链重入推进）：{}",
                        projectId, e.toString());
            }
            dispatchOnTurnClose(projectId, sessionId, closureRunId, /* platformRound= */ true);
        });
    }

    /**
     * 纠偏收口轮（#538，无挂起的死收口形）：收口微指令新轮——只导向产出 PRD；
     * 档位姿态同 ADR-0036（推进账已在 {@link #dispatchQuotaCorrective} 记录，封顶
     * {@link #CORRECTIVE_ROUND_CAP}）。收口走常规重入观测。
     */
    private void converseClosureCorrective(Project project, String sessionId,
            AgentConfigAppService.EffectiveConfig main, ModelCandidateQueue model) {
        Long projectId = project.getId();
        String runId = EventsAppService.newRunId();
        AgentCommand command = correctiveCommandWith(project, runId, main, model,
                CORRECTIVE_CLOSURE_PROMPT);
        sessionExecutor.submit(sessionId, () -> {
            try {
                ConversationHistoryAppService.TurnRecorder recorder =
                        conversationHistory.recorder(projectId, eventBridge.sink(project));
                AgentReply reply = agentClient.converse(command, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                // 纠偏收口轮炸不炸链：收口观测重入推进（同配额代答口径）
                log.warn("[main-close] 项目 {} 纠偏收口轮失败（收窄链重入推进）：{}",
                        projectId, e.toString());
            }
            dispatchOnTurnClose(projectId, sessionId, runId, /* platformRound= */ true);
        });
    }

    /** 纠偏轮单轮派发：配置解析（null＝不升档的直接播报收口）→ 命令构建 → 串行执行 → 成功判据 → 推进/收口。 */
    private void submitCorrectiveRound(Project project, String sessionId, String originRunId,
            boolean prdSaveFailed, int attempt) {
        AgentConfigAppService.EffectiveConfig main = agentConfigs.effectiveOf(AgentProfile.MAIN);
        ModelCandidateQueue model = correctiveModelOf(main, attempt);
        if (model == null) {
            markDanglingInterview(project, originRunId,
                    prdSaveFailed ? DANGLING_REMEDY_PRD_FAILED : DANGLING_REMEDY_GENERIC);
            return;
        }
        Long projectId = project.getId();
        String runId = EventsAppService.newRunId();
        AgentCommand command = correctiveCommand(project, runId, main, model);
        sessionExecutor.submit(sessionId, () -> {
            try {
                ConversationHistoryAppService.TurnRecorder recorder =
                        conversationHistory.recorder(projectId, eventBridge.sink(project));
                AgentReply reply = agentClient.converse(command, recorder);
                settleSuspendedQuestion(sessionId, runId, reply);
                recorder.settle(runId, reply);
            }
            catch (RuntimeException e) {
                // 纠偏轮炸不炸链：按失格推进下一环（error 事件已由 converse 内发出）——
                // 链只为格式失格而设，不放宽「环境性失败重试照样失败」的口径
                log.warn("[main-close] 项目 {} 纠偏轮 {}/{} 失败（按失格推进）：{}", projectId,
                        attempt, CORRECTIVE_ROUND_CAP, e.toString());
            }
            if (correctiveSettled(projectId, sessionId)) {
                log.info("[main-close] 项目 {} 纠偏轮 {}/{} 成功（合法收口形达成——不播报不置标记）",
                        projectId, attempt, CORRECTIVE_ROUND_CAP);
                return;
            }
            if (attempt < CORRECTIVE_ROUND_CAP) {
                submitCorrectiveRound(project, sessionId, originRunId, prdSaveFailed,
                        attempt + 1);
                return;
            }
            markDanglingInterview(project, originRunId,
                    prdSaveFailed ? DANGLING_REMEDY_PRD_FAILED : DANGLING_REMEDY_GENERIC);
        });
    }

    /**
     * 纠偏轮成功判据（ADR-0032 合法收口形两形）：挂起问答在场（再问一问——提问形
     * 纠偏的目标态）或 PRD 已产出（savePrd 形的目标态）。项目事实重查一次（纠偏轮
     * 内可能已产出 PRD 或项目已归档——归档/缺档即停链不播报：对话区已关闭）。
     */
    private boolean correctiveSettled(Long projectId, String sessionId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null || project.getArchivedAt() != null) {
            return true;
        }
        if (project.getPrdProducedAt() != null) {
            return true;
        }
        return agentClient.hasAskingToolCall(project.ownerUserId(), sessionId);
    }

    /**
     * 纠偏轮配置解析：第 1 轮＝主智能体生效档（库值优先、缺省回落枚举——与对话轮
     * 同腿）；第 2 轮＝升强档，路由开关关（纯人工定档运营姿态）返回 null——调用方
     * 直接播报、不自动升档。强档取路由面生效值（运营可换、不发版，ADR-0031），
     * 缺省 {@link ModelRoutingAppService#DEFAULT_STRONG_MODEL_CONFIG_KEY}。
     */
    private ModelCandidateQueue correctiveModelOf(
            AgentConfigAppService.EffectiveConfig main, int attempt) {
        if (attempt == 1) {
            return modelConfigs.resolveQueue(main.modelConfigKey());
        }
        if (!main.routingEnabled()) {
            return null;
        }
        return modelConfigs.resolveQueue(main.routingStrongModelConfigKey() != null
                ? main.routingStrongModelConfigKey()
                : ModelRoutingAppService.DEFAULT_STRONG_MODEL_CONFIG_KEY);
    }

    /** 收口即消费：意见锚与修订事实同口径清掉（不留过轮）——归档 / 未产出 PRD /
     * 未生成派生成前的三条静默路径共用（生成读的是工作区 PRD 正本，不进交接物）。 */
    private void clearTurnAnchors(String sessionId, String workspaceId) {
        opinionExchanges.remove(sessionId);
        prdRevisions.clear(workspaceId);
        fastLaneFacts.clear(workspaceId);
    }

    /**
     * 沉淀兜底盘问记账（#460）：先消费本轮自荐调用事实——主智能体槽本轮提过
     * （受理与拒因同口径）即撤掉此前可能存在的记账（问答续跑里补提的情形）；
     * 未提且为用户轮合法收口则置记账（次轮 system prompt 尾附轻提醒）。平台轮
     * 消费事实不置记账（补产/通告轮不入「该提未提」口径）。悬空收口不记账——
     * 纠偏轮正在救格式失格，不混入沉淀提醒。
     */
    private void sedimentNudgeOnClose(String sessionId, String workspaceId,
            boolean platformRound) {
        boolean mainProposed = sedimentProposals.consumeMainProposed(workspaceId);
        if (mainProposed) {
            sedimentNudges.remove(sessionId);
            return;
        }
        if (!platformRound) {
            sedimentNudges.add(sessionId);
        }
    }

    /**
     * 用户轮失败收口（意见轮与问答续跑共用，ADR-0032）：清两会话锚（失败不留锚
     * ——重提即兜底，#54/#83 既有口径）＋悬空观测（判死同契约，见 {@link
     * #remedyFailedInterviewTurn}）。平台轮（补产/切换通告）不入悬空契约，catch
     * 不走本口。
     */
    private void failUserTurn(Long projectId, String sessionId, String runId) {
        opinionExchanges.remove(sessionId);
        suspendedQuestions.remove(sessionId);
        remedyFailedInterviewTurn(projectId, runId);
    }

    /**
     * 记录本次派发的分类 runId（#537 段收口接线）：入口分类判定翻转后同计费边界
     * ——runId 随该轮的主智能体轮结算入账（收口消费即清；派发分岔后调用，被拦/
     * 兜底路径不记——兜底走 {@link #settleStandaloneClassify} 单独结算）。
     */
    public void noteClassifyRun(Long projectId, String classifyRunId) {
        classifyRuns.put(projectId, classifyRunId);
    }

    /**
     * 兜底路径的判定轮单独结算（#537 段收口接线）：兜底不起轮——分类调用按自身
     * 锚单独结算（不落会漏；试用项目结算服务空过）。
     */
    public void settleStandaloneClassify(Long projectId, String classifyRunId) {
        settleSegment(projectId, SegmentType.MAIN_TURN,
                classifyRunId, List.of(classifyRunId));
    }

    /**
     * 主智能体轮收口结算（#537 段收口接线，ADR-0047「段收口即结算」）：段＝一轮，
     * 收口即结算一次——判定轮（在场时）与本轮用量合落一条扣减行；试用项目零扣减
     * （计费边界＝翻转时点）、零用量/未配价空过（结算服务口径）、同段址重试幂等。
     */
    private void settleTurn(Long projectId, String runId) {
        String classifyRunId = classifyRuns.remove(projectId);
        List<String> usageRunIds = new ArrayList<>(2);
        if (classifyRunId != null) {
            usageRunIds.add(classifyRunId);
        }
        usageRunIds.add(runId);
        settleSegment(projectId,
                SegmentType.MAIN_TURN,
                runId, usageRunIds);
    }

    /** 段结算尽力而为口径（失败也扣——收口/失败/停止都结算；结算异常只记日志不反噬收口轨道）。 */
    private void settleSegment(Long projectId,
            SegmentType segmentType,
            String segmentRef, List<String> usageRunIds) {
        try {
            segmentSettlement.settleSegment(projectId, segmentType, segmentRef, usageRunIds);
        }
        catch (RuntimeException e) {
            log.warn("[main-settle] 项目 {} 段结算失败（不反噬收口轨道）：{}", projectId, e.toString());
        }
    }

    /**
     * 访谈期悬空收口的平台补救（ADR-0032，#373）：置悬空标记（其后输入绕过入口
     * 分类直达意见轮）＋补一条如实状态消息——复用平台自述面（guide-reply 同族，
     * SSE 事件封闭集零新增；prompt 不携带——无新用户话语，不重复重建用户气泡），
     * 对话史 guide 行挂本轮 runId（#360 在途意见回执同口径）。发射失败只记日志：
     * 标记已置（接管仍生效），呈现面失败不牵连收口轨道。
     */
    private void markDanglingInterview(Project project, String runId, String remedyText) {
        danglingInterviews.add(sessionIdOf(project.getId()));
        quotaCorrectiveRounds.remove(sessionIdOf(project.getId())); // #538：播报即清纠偏推进账（用户「继续」重起）
        try {
            eventBridge.emitGuideReply(project, runId, null,
                    DispatchAppService.GUIDE_LABEL, remedyText);
            conversationHistory.recordGuideNotice(project.getId(), runId, remedyText);
        }
        catch (RuntimeException e) {
            log.warn("[main-close] 项目 {} 悬空补救发射失败（标记已置，不牵连收口轨道）：{}",
                    project.getId(), e.toString());
        }
        log.info("[main-close] 项目 {} 访谈期悬空收口（ADR-0032）：置悬空标记＋平台补救「{}」",
                project.getId(), remedyText);
    }

    /**
     * 失败收口的悬空观测（ADR-0032，#373）：访谈期（未出 PRD）用户轮判死/失败即
     * 悬空——同为无问无 PRD 收口（#371 判死同被不变量命中），置标记＋点名形补救
     * （「本轮被中断」）。合法收口形（挂起问答在场——引擎 ASKING 态有推进抓手；
     * PRD 已产出——判死前 savePrd 已成功的复合边角）与归档终态同观测清除标记
     * （生命周期与 {@link #dispatchOnTurnClose} 同口径闭合，不留滞留标记）。项目
     * 事实重查一次（提交时快照后 PRD 可能已出——失败轮自身不产 PRD，但守卫口径
     * 取观测时点）；补救发射失败只记日志——不遮原始轮失败（error 事件已由
     * converse/resume 内发出，异常仍上抛）。
     */
    private void remedyFailedInterviewTurn(Long projectId, String runId) {
        try {
            Project project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                return;
            }
            if (project.getArchivedAt() != null || project.getPrdProducedAt() != null
                    || agentClient.hasAskingToolCall(project.ownerUserId(),
                            sessionIdOf(projectId))) {
                danglingInterviews.remove(sessionIdOf(projectId));
                return;
            }
            markDanglingInterview(project, runId, DANGLING_REMEDY_INTERRUPTED);
        }
        catch (RuntimeException e) {
            log.warn("[main-close] 项目 {} 失败收口悬空补救发射失败（不遮原始轮失败）：{}",
                    projectId, e.toString());
        }
    }

    /** 追问答复并入意见锚（挂起交换期间累积——多轮追问的答复都进交接物）；
     * 无锚（重启丢锚的续跑）时以答复自立。 */
    private void appendOpinionReply(String sessionId, String reply) {
        opinionExchanges.compute(sessionId, (key, opinion) -> opinion == null
                ? reply : opinion + "；用户对追问的答复：" + reply);
    }

    private static Map<String, Object> correlationOf(Long projectId) {
        return Map.of(EventsAppService.PROJECT_FIELD, projectId.toString());
    }

    private static UsageContext usageContextOf(Long projectId, String sessionId) {
        return new UsageContext(Long.toString(projectId),
                UsageDims.of(projectId, UsageDims.kindOf(AgentProfile.MAIN), sessionId));
    }

    private Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
    }

    /** 意见轮守卫：归档即对话区关闭（只读终态）；未终结订单在即冻结迭代
     * （下单后的意见不再受理，取消订单即解冻回迭代态）——新发言与作答一并拒绝。 */
    private Project requireUpdatableProject(Long projectId) {
        Project project = requireProject(projectId);
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        orderQueryAppService.requireNoActiveOrder(projectId);
        return project;
    }

    /** 挂起问答守卫（#40 / ADR-0005）：会话存在挂起问答（ASKING 态工具块）时
     * 新输入不盲提交——引擎必拒且 REST 已返 200 只见异步 error 事件；同步 409
     * 指路作答。作答（resume）在途、ASKING 尚未清库的偶发拦截为已接受竞态边角。
     * 守卫先于命令提交，拒绝即零事件。 */
    private void requireNoPendingQuestion(Project project, String sessionId) {
        if (agentClient.hasAskingToolCall(project.ownerUserId(), sessionId)) {
            throw new ApplicationException(ProjectMessage.QUESTION_PENDING);
        }
    }

    /**
     * 意见受理守卫束（#533 拦截卡确认前置）：归档（对话区关闭）/ 订单冻结
     * （ORD_006）/ 挂起问答（PRJ_024）——确认扣减翻转前先验受理面：拒则拦截卡
     * 留待决、不空翻（翻转不可逆，翻转后受理被拒＝钱的承诺事实落空）。
     */
    public void requireOpinionAcceptable(Long projectId) {
        Project project = requireUpdatableProject(projectId);
        requireNoPendingQuestion(project, sessionIdOf(projectId));
    }
}
