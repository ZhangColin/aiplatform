package com.semgravio.aiplatform.business.project.application;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.UsageContext;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceGateAppService;
import com.semgravio.aiplatform.business.billing.application.BalanceReminderAppService;
import com.semgravio.aiplatform.business.billing.application.BillingAppService;
import com.semgravio.aiplatform.business.billing.application.TrialInterceptAppService;
import com.semgravio.aiplatform.business.billing.domain.enums.BalanceHoldLane;
import com.semgravio.aiplatform.business.billing.domain.enums.DeductionSource;
import com.semgravio.aiplatform.business.billing.domain.enums.InterceptLane;
import com.semgravio.aiplatform.business.project.application.dto.command.MessageAttachment;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;

import lombok.extern.slf4j.Slf4j;

/**
 * 入口派发编排（#47 三分类，#86 并轨后归一主智能体单会话）：对话区发言先过全局
 * 守卫（存在 / 未归档），再经智能体边界上的轻量分类调用分岔——意见走主智能体
 * 意见轮（{@link MainAgentAppService#runOpinionTurn}，订单冻结 / 挂起问答守卫在
 * 此分岔内拦）、咨询走主智能体答询轮（{@link MainAgentAppService#answerInquiry}，
 * 零产物短路，随时可答）、兜底走平台定型引导（零产物，不起任何智能体 run）。
 * 意见与咨询同一 {@code main-{projectId}} 会话连续（#86）——分岔只是车道语义
 * （守卫与交接物有无），不是会话派生。对用户全程隐式（CONTEXT.md「派发」）。
 *
 * <p><b>生成后对话面（#622，ADR-0056 两平面）</b>：项目已生成或编码 run 在途时，
 * 发言在分类前直接分岔到对话面（{@link DialogAppService}）——独立轻会话
 * （{@code dialog-{projectId}}）、只读答疑与澄清、零派发；生成前的首建访谈链
 * （分类/答询/兜底）原样不动。旧迭代受理链与其上的试用拦截/余额留存不接本面
 * （商业面代码保留，归 #625 清场）。</p>
 *
 * <p><b>分类是轻量调用而非新模型端口</b>：复用 {@link AgentscopeAgentClient}
 * 一次性会话（{@code classify-{runId}}，模型档走边缘点配置链——classification
 * 配置库值优先缺省 flash（#366 收编，旧 yml 专用键退役；原意承 #51：轻调用不
 * 烧重档）、空 sink 无事件、不触项目工作区），
 * 同步跑在派发请求路径上（秒级；分类结果决定响应携带的 runId 归属，异步化会
 * 拿不到真锚）。</p>
 *
 * <p><b>失败 / 超时兜底 = 按意见</b>（设计 v1 §3.1 的定向取舍）：误进意见链有
 * 主智能体把关（追问或改 PRD，代价小）；误判为咨询会丢变更（不可接受）。分类
 * 调用异常、超时或输出不可解析一律回落意见链。</p>
 *
 * <p><b>悬空接管（ADR-0032，#373）——「有时不分类」的解释落点</b>：分类器是一次性
 * 会话、无项目上下文，访谈悬空态（轮异常收口后无问无 PRD）里的短确认（「OK」「可以」）
 * 会被 FALLBACK 定义吃掉、回定型文案——用户感知「装忙复读」。悬空标记在场时
 * （{@link MainAgentAppService#interviewDangling}——平台事实级判定，非模型自报）
 * 输入绕过分类直达意见轮，标记合法收口即清、恢复分类。</p>
 *
 * <p><b>兜底类零产物</b>：轻量引导回复为平台定型文案（代码承载，非 LLM 产），
 * 经 {@code guide-reply} 事件直达对话区；下单意图归兜底的引导分岔——指引
 * Demo 确认门（「确认此版本，准备签约」；未生成时如实说明入口出现时机）。全程不起 run、不动任何
 * 产物。</p>
 *
 * <p><b>试用拦截闸（#533 计费②门②，ADR-0047「试用拦截面＝平台结构闸」）</b>：
 * 分类之后、受理之前——试用项目（两轨判定单读点，迭代期已生成）的意见与兜底
 * 输入被拦下留存（{@code bil_intercepts} 待决行，原文＋附件不丢失），拦截卡事件
 * 直达对话面（显源池；源未定随卡首次选源——「本项目余额／我的账户」）。确认扣减
 * ＝翻转门②（付费状态不可逆，落定同事务）并立即受理原被拦输入（不重输）；或走
 * 充值引导/中途关闭——卡可回（读面 {@code GET /projects/{id}/intercept}）。咨询
 * 零产物短路天然放行、下单意图维持旧轨。车道误判偏差面（票内声明）：咨询误判
 * 意见＝多弹一次卡（可改口），意见误判咨询＝漏一次费（demo 阶段容忍）。</p>
 *
 * <p><b>段边界余额门（#537 计费③，ADR-0047「段间硬门段中自由」）</b>：余额检查
 * 只发生在段边界——分类之后、受理之前，付费项目（两轨判定单读点）源池余额&le;0
 * 拦下可计费车道（意见/咨询；兜底零调用天然免费不拦），被拦输入留存
 * （{@code bil_balance_holds} 待决行，原文＋附件不丢失），余额提醒卡事件直达
 * 对话面（显当前负额＋充值入口〔先登录＋准入码〕）；充值回正自动放行（恢复受理
 * 不重输——入账事件编排监听触发认领）；悬空接管路径同守本门。试用项目门恒放行
 * （计费边界＝翻转时点）。段内绝不检查绝不中断（扣成负照跑——结算段收口才结算）。</p>
 *
 * <p><b>判定轮结算（#537 段收口接线）</b>：分类调用的 runId 随可计费车道的轮
 * 结算入账（翻转后判定轮同计费边界）；兜底路径不起轮——判定调用单独结算。</p>
 */
@Service
@Slf4j
public class DispatchAppService {

    /** 分类一次性会话标识派生前缀（每次派发独立会话——分类不承上下文）。 */
    static final String CLASSIFY_SESSION_PREFIX = "classify-";

    /**
     * 分类超时（REST 路径上的硬界）：flash 档单标签输出秒级；超时即回落意见链，
     * 不让派发请求无限等待。
     */
    private static final Duration CLASSIFY_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 分类协议：只输出一个标签，不解释。三分类 + 下单意图分岔（ORDER_INTENT 是
     * 兜底的引导分岔——同一终止形态，仅文案不同）；拿不准意见/咨询时判意见。
     */
    private static final String CLASSIFY_SYSTEM_PROMPT =
            "你是平台项目对话区的入口分类器，把用户发来的消息分类后只输出分类标签本身。"
                    + "分类定义：\n"
                    + "OPINION（意见）：想让平台改点什么——改系统、加功能、调范围、改需求，"
                    + "或推进需求梳理的答复与补充；拿不准是不是要改时也算 OPINION。\n"
                    + "INQUIRY（咨询）：想了解现状的提问——问系统怎么用、访问地址、账号、"
                    + "项目进展、PRD 写了什么等；不要求任何变更。\n"
                    + "FALLBACK（兜底）：与项目无关的寒暄、感谢、闲聊、模糊输入。\n"
                    + "ORDER_INTENT（下单意图）：想买/下单/询价/问怎么购买的明确意图。\n"
                    + "判定规则：只输出一个标签（OPINION、INQUIRY、FALLBACK 或 ORDER_INTENT），"
                    + "不解释、不换行、不加标点；拿不准 OPINION 还是 INQUIRY 时判 OPINION。";

    /** 兜底轻引导文案（平台侧定型文案，用户面正本）。 */
    static final String GUIDE_GENERIC_TEXT =
            "我在这里帮您把系统做出来：想改哪里、想加什么功能，直接告诉我；"
                    + "想了解项目的情况（比如系统的访问地址、账号），也可以直接问。";

    /** 下单意图引导文案（已生成——Demo 确认门入口在位）。 */
    static final String GUIDE_ORDER_TEXT_GENERATED =
            "好的，请到 Demo 体验页点「确认此版本，准备签约」：确认后进入签约付款，平台会为您安排报价。";

    /** 下单意图引导文案（未生成——如实说明入口出现时机，不指不存在的东西）。 */
    static final String GUIDE_ORDER_TEXT_NOT_GENERATED =
            "Demo 生成完成后，Demo 体验页会出现「确认此版本，准备签约」按钮，届时点击即可进入签约。";

    /** 引导回复呈现标签（非智能体角色——平台自己说话）。 */
    static final String GUIDE_LABEL = "平台";

    /**
     * 随拦附件序列化器（#533 拦截留存；同对话史落库口径——NON_NULL、无效形态
     * 上游已滤，留存载荷原样存取）。
     */
    private static final ObjectMapper INTERCEPT_JSON = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final MainAgentAppService mainAgentAppService;
    private final DialogAppService dialogAppService;
    private final CodingRunTrack codingRunTrack;
    private final AgentEventBridge eventBridge;
    private final AgentscopeAgentClient agentClient;
    private final AgentConfigAppService agentConfigs;
    private final ModelConfigAppService modelConfigs;
    private final ConversationHistoryAppService conversationHistory;
    private final BillingAppService billingAppService;
    private final TrialInterceptAppService trialInterceptAppService;
    private final BalanceGateAppService balanceGate;
    private final BalanceReminderAppService balanceReminderService;

    public DispatchAppService(MainAgentAppService mainAgentAppService,
            DialogAppService dialogAppService, CodingRunTrack codingRunTrack,
            AgentEventBridge eventBridge,
            AgentscopeAgentClient agentClient, AgentConfigAppService agentConfigs,
            ModelConfigAppService modelConfigs,
            ConversationHistoryAppService conversationHistory,
            BillingAppService billingAppService,
            TrialInterceptAppService trialInterceptAppService,
            BalanceGateAppService balanceGate,
            BalanceReminderAppService balanceReminderService) {
        this.mainAgentAppService = mainAgentAppService;
        this.dialogAppService = dialogAppService;
        this.codingRunTrack = codingRunTrack;
        this.eventBridge = eventBridge;
        this.agentClient = agentClient;
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
        this.conversationHistory = conversationHistory;
        this.billingAppService = billingAppService;
        this.trialInterceptAppService = trialInterceptAppService;
        this.balanceGate = balanceGate;
        this.balanceReminderService = balanceReminderService;
    }

    /**
     * 派发一条对话区输入（REST 路径同步入口）：全局守卫（存在 / 未归档——归档即
     * 对话区关闭，咨询与兜底也停）→ 悬空接管判定（ADR-0032：标记在场即跳过分类
     * 直达意见轮）→ 轻量分类 → 按类分岔。订单冻结（ORD_006）与
     * 挂起问答（PRJ_024）只拦意见链：意见分岔（主智能体意见轮）自带守卫在分类后
     * 拦——咨询与兜底随时可答（CONTEXT.md「派发」；被拒意见先烧一次 flash 分类
     * 调用，秒级轻调用，接受）。响应携带所派 run 的标识（意见 = 意见轮 / 咨询 =
     * 答询轮 / 兜底 = 引导事件锚）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（对话区
     *                              关闭，先于分类——拒绝即零调用零事件）；ORD_006
     *                              订单处理中 / PRJ_024 挂起问答待答（仅意见类，
     *                              分类后拦）
     */
    public DispatchRun dispatch(Long projectId, String prompt, List<MessageAttachment> attachments) {
        Project project = mainAgentAppService.requireDispatchableProject(projectId);
        // 对话面（#622，ADR-0056 两平面）：项目已生成或首建/迭代在途——对话区发言
        // 走生成后的独立轻会话（答疑与澄清两职能），零派发、零写面、零生成触发；
        // 生成前的首建访谈链、答询与兜底分类原样不动（下方既有派发面）。旧迭代
        // 受理链与其上的试用拦截/余额留存不接本面（商业面代码保留，归 #625 清场）。
        if (dialogPlaneApplies(project, codingRunTrack)) {
            return new DispatchRun(
                    dialogAppService.turn(project, prompt, attachments));
        }
        // 悬空接管（ADR-0032，#373）：访谈悬空标记在场即绕过入口三分类直达意见轮
        // ——悬空期短确认（「OK」「可以」）不再被无状态分类吃进兜底、回与项目状态
        // 无关的定型文案（「装忙复读」症状面）；宁进意见链有主智能体把关（既有定向
        // 取舍的延伸）。守卫照拦：归档已在上拦、订单冻结与挂起问答由意见轮自带守卫
        // 拦（挂起在场即 409 指路作答——与答询转作答复不冲突，标记在合法收口即清）
        if (mainAgentAppService.interviewDangling(projectId)) {
            log.info("[dispatch] 项目 {} 访谈悬空接管：跳过入口分类，直达意见轮（ADR-0032）",
                    projectId);
            // 段边界余额门（#537）：悬空接管同守段间硬门——欠费照样拦下留存
            BalanceGateAppService.Verdict gate = balanceGate.assess(projectId);
            if (gate.blocked()) {
                return blockBalanceReminder(project, prompt, attachments,
                        BalanceHoldLane.OPINION);
            }
            return new DispatchRun(
                    mainAgentAppService.runOpinionTurn(projectId, prompt, attachments).runId());
        }
        ClassifyOutcome classified = classify(projectId, prompt);
        if (interceptable(classified.classification()) && interceptGateOn(project)) {
            // 判定轮单独结算（#537 段收口接线——拦下＝判定调用已发生、无轮跟随，
            // 同兜底口径「不落会漏」；试用空过）
            mainAgentAppService.settleStandaloneClassify(projectId, classified.runId());
            return blockIntercept(project, prompt, attachments, laneOf(classified.classification()));
        }
        // 段边界余额门（#537 计费③，ADR-0047 段间硬门）：余额检查只发生在段边界——
        // 付费项目源池≤0 拦下可计费车道（意见/咨询；兜底零调用天然免费不拦），被拦
        // 输入留存 bil_balance_holds＋余额提醒卡直达对话面（显负额＋充值入口），充值
        // 回正自动放行（恢复受理不重输）；试用项目门恒放行（计费边界＝翻转时点）。
        // 段内绝不检查绝不中断——本门只在派发入口（段起跑前）被消费。
        BalanceGateAppService.Verdict gate = balanceGate.assess(project.getId());
        if (gate.blocked() && billable(classified.classification())) {
            // 判定轮单独结算（同拦截拦下口径——判定调用已发生无轮跟随）
            mainAgentAppService.settleStandaloneClassify(projectId, classified.runId());
            return blockBalanceReminder(project, prompt, attachments,
                    classified.classification().type() == MessageClass.OPINION
                            ? BalanceHoldLane.OPINION : BalanceHoldLane.INQUIRY);
        }
        return switch (classified.classification().type()) {
            case OPINION -> {
                // 判定轮随本轮结算（#537 段收口接线——翻转后判定调用同计费边界）
                mainAgentAppService.noteClassifyRun(projectId, classified.runId());
                yield new DispatchRun(
                        mainAgentAppService.runOpinionTurn(projectId, prompt, attachments).runId());
            }
            case INQUIRY -> {
                mainAgentAppService.noteClassifyRun(projectId, classified.runId());
                yield new DispatchRun(
                        mainAgentAppService.answerInquiry(project, prompt, attachments).runId());
            }
            case FALLBACK -> {
                // 兜底不起轮：判定调用单独结算（不落会漏——翻转后判定轮同计费边界）
                mainAgentAppService.settleStandaloneClassify(projectId, classified.runId());
                yield guideReply(project, prompt, classified.classification().orderIntent(), attachments);
            }
        };
    }

    /**
     * 对话面适用范围（#622）：项目已生成，或首建/迭代编码 run 在途——两者都是
     * 「系统已存在/正在成形」的生成后时段，对话区发言走对话面。未生成且无在途
     * （首建访谈期）走既有入口分类链（原样不动）。
     */
    static boolean dialogPlaneApplies(Project project, CodingRunTrack codingRunTrack) {
        return project.isGenerated() || codingRunTrack.isInFlight(project.getId());
    }

    /**
     * 待决拦截卡读面（#533 计费②门②，卡可回）：无待决/已付费返回空（卡随翻转
     * 熄灭）。重载/回访经 GET 重建——前端无需事件在场。
     */
    public Optional<TrialInterceptAppService.InterceptCard> pendingIntercept(Long projectId) {
        return trialInterceptAppService.pendingCard(projectId);
    }

    /**
     * 待决余额提醒卡读面（#537 计费③，卡可回）：无待决返回空（卡随放行熄灭）。
     * 重载/回访经 GET 重建——前端无需事件在场。
     */
    public Optional<BalanceReminderAppService.BalanceReminderCard> pendingBalanceReminder(
            Long projectId) {
        return balanceReminderService.pendingCard(projectId);
    }

    /**
     * 充值回正放行（#537——「充值到账后下一段自动放行〔不手动重试〕」）：认领全部
     * 过门的待决留存并恢复受理（意见回意见轮／咨询回答询轮，恢复受理的天然守卫
     * 〔归档/订单冻结/挂起问答〕照拦不误）；恢复受理失败的留存退场（discard——
     * 用户重发即新段，段边界门判定自然接管）。入账事件（AFTER_COMMIT 监听）触发。
     */
    public void resumeBalanceHolds() {
        for (BalanceReminderAppService.ResumableHold hold : balanceReminderService.claimPassable()) {
            try {
                Project project = mainAgentAppService.requireDispatchableProject(hold.projectId());
                switch (hold.lane()) {
                    case OPINION -> mainAgentAppService.runOpinionTurn(hold.projectId(),
                            hold.prompt(), attachmentsOf(hold.attachments()));
                    case INQUIRY -> mainAgentAppService.answerInquiry(project,
                            hold.prompt(), attachmentsOf(hold.attachments()));
                }
                log.info("[dispatch] 项目 {} 余额提醒放行：被拦输入已恢复受理（{}，holdId={}）",
                        hold.projectId(), hold.lane(), hold.holdId());
            }
            catch (RuntimeException e) {
                // 恢复受理失败（项目已归档/订单冻结/挂起问答/件状态失位）：留存退场，
                // 输入不自动恢复——用户重发即新段（门判定自然接管，不滞留卡）
                balanceReminderService.discard(hold.holdId());
                log.warn("[dispatch] 项目 {} 余额提醒放行恢复受理失败（留存退场，用户重发即新段）：{}",
                        hold.projectId(), e.toString());
            }
        }
    }

    /** 被拦输入落留存＋余额提醒卡事件（本事件即该次派发的全部：零 run、零对话史——
     *  受理前的拦下不进对话面，原文随卡呈现与恢复；充值回正自动放行〔不手动重试〕）。 */
    private DispatchRun blockBalanceReminder(Project project, String prompt,
            List<MessageAttachment> attachments, BalanceHoldLane lane) {
        String runId = EventsAppService.newRunId();
        BalanceReminderAppService.BalanceReminderCard card = balanceReminderService.hold(
                project.getId(), lane, prompt,
                ConversationHistoryAppService.toJsonMaps(attachments), runId);
        eventBridge.emitBalanceReminder(project, card);
        log.info("[dispatch] 项目 {} 余额提醒（{}）：输入已留存待决，充值回正自动放行",
                project.getId(), lane);
        return new DispatchRun(runId);
    }

    /** 可计费车道判定（#537 余额门消费面）：意见与咨询翻转后计费（对话轮）；兜底
     *  （含下单意图引导）零调用天然免费，永不因余额被拦。 */
    private static boolean billable(Classification classified) {
        return classified.type() == MessageClass.OPINION
                || classified.type() == MessageClass.INQUIRY;
    }

    /**
     * 确认扣减（拦截卡·翻转门②）：意见车道先验受理守卫束（{@code requireOpinionAcceptable}
     * ——归档/订单冻结/挂起问答拒则卡留待决、不空翻：付费状态不可逆，确定性拒绝
     * 必须在翻转前）。源未定随卡首次选定（账户源取当前登录账户，访客选「我的账户」
     * 吃 BILL_009 指路登录）；翻转落定同事务，随后立即受理原被拦输入（不重输——
     * 意见回意见轮、兜底回定型轻引导），响应 runId＝受理运行的标识（挂 /api/events?runId=）。
     *
     * <p><b>残余竞态窗口（有意识取舍，票内声明）</b>：翻转事务提交与恢复受理之间
     * 的毫秒窗内项目被并发归档/下单/挂起新问答时，恢复被拒＝翻转已不可逆、留存
     * 已落定——被拦输入失去自动恢复面。处置＝用户重发该意见（已付费项目闸关直通
     * 受理），资损为零；不引入确认回退/重试卡机制（毫秒窗概率 vs 新机制复杂度
     * 不成比例）。</p>
     */
    public DispatchRun confirmIntercept(Long projectId, DeductionSource source) {
        Project project = mainAgentAppService.requireDispatchableProject(projectId);
        TrialInterceptAppService.PendingHold hold = trialInterceptAppService.requirePending(projectId);
        if (hold.lane() == InterceptLane.OPINION) {
            mainAgentAppService.requireOpinionAcceptable(projectId);
        }
        trialInterceptAppService.confirm(projectId, source, RequestContext.getUserId());
        return resumeIntercept(project, hold);
    }

    /** 恢复受理原被拦输入（按留存车道分岔——确认扣减的「立即受理」语义）。 */
    private DispatchRun resumeIntercept(Project project, TrialInterceptAppService.PendingHold hold) {
        List<MessageAttachment> attachments = attachmentsOf(hold.attachments());
        return switch (hold.lane()) {
            case OPINION -> new DispatchRun(
                    mainAgentAppService.runOpinionTurn(project.getId(), hold.prompt(), attachments)
                            .runId());
            case FALLBACK -> guideReply(project, hold.prompt(), false, attachments);
        };
    }

    /** 被拦输入落留存＋拦截卡事件（本事件即该次派发的全部：零 run、零对话史——
     *  受理前的拦下不进对话面，原文随卡呈现与恢复）。 */
    private DispatchRun blockIntercept(Project project, String prompt,
            List<MessageAttachment> attachments, InterceptLane lane) {
        String runId = EventsAppService.newRunId();
        TrialInterceptAppService.InterceptCard card = trialInterceptAppService.block(
                project.getId(), lane, prompt,
                ConversationHistoryAppService.toJsonMaps(attachments), runId);
        eventBridge.emitInterceptCard(project, card);
        log.info("[dispatch] 项目 {} 试用拦截（{}）：输入已留存待决，拦截卡已发（门②）",
                project.getId(), lane);
        return new DispatchRun(runId);
    }

    /** 两道拦判定（宁严勿漏，ADR-0047）：意见恒拦；兜底拦（下单意图是兜底的
     *  引导分岔——维持旧轨不拦）；咨询零产物短路天然放行。 */
    private static boolean interceptable(Classification classified) {
        return classified.type() == MessageClass.OPINION
                || (classified.type() == MessageClass.FALLBACK && !classified.orderIntent());
    }

    /**
     * 闸的开启条件：试用项目（两轨判定单读点，行缺席＝试用）且已过获客旅程
     * （已生成＝迭代期——五问/PRD/首个构建整场生成零扣费，意见与兜底从首次生成
     * 起进入付费续用）。
     */
    private boolean interceptGateOn(Project project) {
        return project.isGenerated() && billingAppService.trackOf(project.getId()).trial();
    }

    private static InterceptLane laneOf(Classification classified) {
        return classified.type() == MessageClass.OPINION
                ? InterceptLane.OPINION : InterceptLane.FALLBACK;
    }

    /** 留存附件载荷还原（确认受理的回放面；空/缺省归无附件）。 */
    private static List<MessageAttachment> attachmentsOf(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return MessageAttachment.NONE;
        }
        return INTERCEPT_JSON.convertValue(raw, new TypeReference<>() {
        });
    }

    /** 无附件的派发（纯文字发言——#97 之前与测试既有口径）。 */
    public DispatchRun dispatch(Long projectId, String prompt) {
        return dispatch(projectId, prompt, MessageAttachment.NONE);
    }

    /** 一次派发的运行标识（前端挂智能体事件 ?runId= 的锚；兜底路径锚 guide-reply 事件）。 */
    public record DispatchRun(String runId) {
    }

    /** 对话区消息三分类（CONTEXT.md「派发」；下单意图是兜底的引导分岔信号）。 */
    enum MessageClass {
        OPINION, INQUIRY, FALLBACK
    }

    /** 分类结果：类型 + 兜底分支的下单意图信号。 */
    record Classification(MessageClass type, boolean orderIntent) {

        static final Classification OPINION_FALLBACK = new Classification(MessageClass.OPINION, false);
    }

    /** 分类调用结果：分类判定＋本次判定的 runId（#537 段收口接线——判定轮随段结算）。 */
    private record ClassifyOutcome(Classification classification, String runId) {
    }

    // ---------- 内部 ----------

    /**
     * 兜底轻引导：发 {@code guide-reply} 事件（零产物路径的全部事件）即收口——不起
     * run、不提交会话、不动任何产物。runId 为派发锚。
     */
    private DispatchRun guideReply(Project project, String prompt, boolean orderIntent,
            List<MessageAttachment> attachments) {
        String runId = EventsAppService.newRunId();
        String text = orderIntent
                ? (project.getGeneratedAt() != null
                        ? GUIDE_ORDER_TEXT_GENERATED : GUIDE_ORDER_TEXT_NOT_GENERATED)
                : GUIDE_GENERIC_TEXT;
        eventBridge.emitGuideReply(project, runId, prompt,
                GUIDE_LABEL, text);
        // 对话史落库（#89）：轻引导也是对话面——用户发言 + 定型文案两行（事件已发，
        // 补写失败只记日志）；消息附件随发言同落
        conversationHistory.recordGuide(project.getId(), runId, prompt, text, attachments);
        log.info("[dispatch] 项目 {} 兜底引导（{}）", project.getId(),
                orderIntent ? "下单意图" : "泛引导");
        return new DispatchRun(runId);
    }

    /**
     * 轻量分类调用（智能体边界上，非新端口）：一次性会话、模型档走边缘点配置链
     * （#366 收编：classification 配置库值优先缺省 flash——配置读面
     * {@link AgentConfigAppService#effectiveEdgeModelConfigKey} → 解析单点
     * {@link ModelConfigAppService#resolve} 产模型串＋通道注入，后台改配置下一轮派发
     * 即生效）、空 sink（无 SSE 事件）、不触项目工作区、计量 agentKind=classify。
     * 返回分类结果＋本次判定的 runId（#537 段收口接线：判定轮翻转后计费，随可计费
     * 车道的轮结算／兜底路径单独结算）。失败 / 超时 / 输出不可解析一律回落意见链
     * （见类注释的定向取舍）。
     */
    private ClassifyOutcome classify(Long projectId, String prompt) {
        String runId = EventsAppService.newRunId();
        String sessionId = CLASSIFY_SESSION_PREFIX + runId;
        // #557 整候选队列（边缘点同获链式切换——resolve 面之一）
        ModelCandidateQueue model = modelConfigs.resolveQueue(
                agentConfigs.effectiveEdgeModelConfigKey(AgentEdgePoint.CLASSIFICATION));
        AgentCommand command = new AgentCommand(
                runId,
                prompt,
                CLASSIFY_SYSTEM_PROMPT,
                model.modelString(), // 边缘点配置解析产物（缺省 flash 档）
                model.channel(), // 通道注入：key/baseUrl 入 SDK，不吃环境变量
                sessionId,
                null, // 一次性会话，无 (userId, sessionId) 槽位复用语义
                new UsageContext(Long.toString(projectId),
                        UsageDims.of(projectId, UsageDims.AGENT_KIND_CLASSIFY, sessionId)),
                null, // 本地兜底工作区：分类不读写项目工作区
                Map.of(),
                CLASSIFY_TIMEOUT,
                /* silenceTimeout= */ null,
                /* toolSilenceTimeout= */ null,
                null,
                /* workspaceReadOnly= */ false,
                /* heading= */ null,
                /* toolSpec= */ null, // 一次性判定无配置语境：空工具面、无规格
                // #557 候选队列（failover 装配面）
                model);
        try {
            AgentReply reply = agentClient.converse(command, event -> {
            });
            Classification parsed = parse(reply.text());
            if (parsed != null) {
                return new ClassifyOutcome(parsed, runId);
            }
            log.warn("[dispatch] 项目 {} 分类输出不可解析（回落意见链）：{}", projectId, reply.text());
        }
        catch (RuntimeException e) {
            log.warn("[dispatch] 项目 {} 分类调用失败（回落意见链）：{}", projectId, e.toString());
        }
        return new ClassifyOutcome(Classification.OPINION_FALLBACK, runId);
    }

    /**
     * 分类输出解析（容错；大小写不敏感）：OPINION / INQUIRY / FALLBACK /
     * ORDER_INTENT（裸 ORDER 亦认）。单标签命中即归类；<b>出现多个不同标签
     * （模型违规解释）视为歧义，按意见处理</b>——「误判为咨询会丢变更」的定向
     * 兜底（先出现者获胜会放大该风险）。无可识别标签返回 null（调用方回落
     * 意见链）。
     */
    static Classification parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.toUpperCase(Locale.ROOT);
        boolean orderIntent = text.indexOf("ORDER") >= 0;
        boolean opinion = text.indexOf("OPINION") >= 0;
        boolean inquiry = text.indexOf("INQUIRY") >= 0;
        boolean fallback = text.indexOf("FALLBACK") >= 0;
        int distinct = (orderIntent ? 1 : 0) + (opinion ? 1 : 0)
                + (inquiry ? 1 : 0) + (fallback ? 1 : 0);
        if (distinct == 0) {
            return null;
        }
        if (distinct > 1) {
            return Classification.OPINION_FALLBACK; // 歧义兜底：宁进意见链（有主智能体把关）
        }
        if (orderIntent) {
            return new Classification(MessageClass.FALLBACK, true);
        }
        if (opinion) {
            return new Classification(MessageClass.OPINION, false);
        }
        if (inquiry) {
            return new Classification(MessageClass.INQUIRY, false);
        }
        return new Classification(MessageClass.FALLBACK, false);
    }
}
