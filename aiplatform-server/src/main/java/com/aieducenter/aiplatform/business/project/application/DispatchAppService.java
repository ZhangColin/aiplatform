package com.aieducenter.aiplatform.business.project.application;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.agentscope.AgentCommand;
import com.aieducenter.aiplatform.base.agentscope.AgentReply;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.UsageContext;
import com.aieducenter.aiplatform.base.eventhub.application.AgentStreamAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.model.UsageDims;

import lombok.extern.slf4j.Slf4j;

/**
 * 入口派发编排（#47 三分类）：指令区发言先过公共守卫，再经智能体边界上的轻量
 * 分类调用分岔——意见走 BA（既有意见链）、咨询走助理职能体（
 * {@link AssistantAppService}，零产物短路）、兜底走平台定型引导（零产物，不起
 * 任何智能体 run）。对用户全程隐式（CONTEXT.md「派发」）。
 *
 * <p><b>分类是轻量调用而非新模型端口</b>：复用 {@link AgentscopeAgentClient}
 * 一次性会话（{@code classify-{runId}}，flash 缺省档、空 sink 无帧、不触项目
 * 工作区），同步跑在派发请求路径上（秒级；分类结果决定响应携带的 runId 归属，
 * 异步化会拿不到真锚）。</p>
 *
 * <p><b>失败 / 超时兜底 = 按意见</b>（设计 v1 §3.1 的定向取舍）：误进意见链有
 * BA 把关（追问或改 PRD，代价小）；误判为咨询会丢变更（不可接受）。分类调用
 * 异常、超时或输出不可解析一律回落意见链。</p>
 *
 * <p><b>兜底类零产物</b>：轻量引导回复为平台定型文案（代码承载，非 LLM 产），
 * 经 {@code guide-reply} 帧直达指令区；下单意图归兜底的引导分岔——指引
 * 「确认下单」入口（未生成时如实说明入口出现时机）。全程不起 run、不动任何
 * 产物。</p>
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
            "你是平台项目指令区的入口分类器，把用户发来的消息分类后只输出分类标签本身。"
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

    /** 下单意图引导文案（已生成——「确认下单」入口在位）。 */
    static final String GUIDE_ORDER_TEXT_GENERATED =
            "好的，请点输入框上方的「确认下单」按钮：确认后平台会为您安排报价。";

    /** 下单意图引导文案（未生成——如实说明入口出现时机，不指不存在的东西）。 */
    static final String GUIDE_ORDER_TEXT_NOT_GENERATED =
            "系统首次生成完成后，输入框上方会出现「确认下单」按钮，届时点击即可开始下单。";

    /** 引导回复呈现标签（非智能体角色——平台自己说话）。 */
    static final String GUIDE_LABEL = "平台";

    private final BaInterviewAppService baInterviewAppService;
    private final AssistantAppService assistantAppService;
    private final AgentStreamBridge streamBridge;
    private final AgentscopeAgentClient agentClient;

    public DispatchAppService(BaInterviewAppService baInterviewAppService,
            AssistantAppService assistantAppService, AgentStreamBridge streamBridge,
            AgentscopeAgentClient agentClient) {
        this.baInterviewAppService = baInterviewAppService;
        this.assistantAppService = assistantAppService;
        this.streamBridge = streamBridge;
        this.agentClient = agentClient;
    }

    /**
     * 派发一条指令区输入（REST 路径同步入口）：公共守卫 → 轻量分类 → 按类分岔。
     * 响应携带所派 run 的标识（意见 = BA 轮 / 咨询 = 助理轮 / 兜底 = 引导帧锚）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档（指令区
     *                              关闭）；ORD_006 订单处理中；PRJ_024 挂起问答
     *                              待答（守卫先于分类——拒绝即零调用零帧）
     */
    public DispatchRun dispatch(Long projectId, String prompt) {
        Project project = baInterviewAppService.requireDispatchableProject(projectId);
        Classification classified = classify(projectId, prompt);
        return switch (classified.type()) {
            case OPINION -> new DispatchRun(
                    baInterviewAppService.runInterviewTurn(projectId, prompt).runId());
            case INQUIRY -> new DispatchRun(
                    assistantAppService.answer(project, prompt).runId());
            case FALLBACK -> guideReply(project, prompt, classified.orderIntent());
        };
    }

    /** 一次派发的运行标识（前端挂智能体流 ?runId= 的锚；兜底路径锚 guide-reply 帧）。 */
    public record DispatchRun(String runId) {
    }

    /** 指令区消息三分类（CONTEXT.md「派发」；下单意图是兜底的引导分岔信号）。 */
    enum MessageClass {
        OPINION, INQUIRY, FALLBACK
    }

    /** 分类结果：类型 + 兜底分支的下单意图信号。 */
    record Classification(MessageClass type, boolean orderIntent) {

        static final Classification OPINION_FALLBACK = new Classification(MessageClass.OPINION, false);
    }

    // ---------- 内部 ----------

    /**
     * 兜底轻引导：发 {@code guide-reply} 帧（零产物路径的全部帧）即收口——不起
     * run、不提交会话、不动任何产物。runId 为派发锚。
     */
    private DispatchRun guideReply(Project project, String prompt, boolean orderIntent) {
        String runId = AgentStreamAppService.newRunId();
        String text = orderIntent
                ? (project.getGeneratedAt() != null
                        ? GUIDE_ORDER_TEXT_GENERATED : GUIDE_ORDER_TEXT_NOT_GENERATED)
                : GUIDE_GENERIC_TEXT;
        streamBridge.emitGuideReply(project.getId(), runId, prompt, GUIDE_LABEL, text);
        log.info("[dispatch] 项目 {} 兜底引导（{}）", project.getId(),
                orderIntent ? "下单意图" : "泛引导");
        return new DispatchRun(runId);
    }

    /**
     * 轻量分类调用（智能体边界上，非新端口）：一次性会话、flash 缺省档、空 sink
     * （无 SSE 帧）、不触项目工作区、计量 agentKind=classify。失败 / 超时 /
     * 输出不可解析一律回落意见链（见类注释的定向取舍）。
     */
    private Classification classify(Long projectId, String prompt) {
        String runId = AgentStreamAppService.newRunId();
        String sessionId = CLASSIFY_SESSION_PREFIX + runId;
        AgentCommand command = new AgentCommand(
                runId,
                prompt,
                CLASSIFY_SYSTEM_PROMPT,
                null, // 模型取适配器缺省（flash 档——单标签输出，快且省）
                sessionId,
                null, // 一次性会话，无 (userId, sessionId) 槽位复用语义
                new UsageContext(Long.toString(projectId),
                        UsageDims.of(projectId, UsageDims.AGENT_KIND_CLASSIFY, sessionId)),
                null, // 本地兜底工作区：分类不读写项目工作区
                Map.of(),
                CLASSIFY_TIMEOUT,
                /* live= */ false,
                null,
                /* workspaceReadOnly= */ false);
        try {
            AgentReply reply = agentClient.converse(command, event -> {
            });
            Classification parsed = parse(reply.text());
            if (parsed != null) {
                return parsed;
            }
            log.warn("[dispatch] 项目 {} 分类输出不可解析（回落意见链）：{}", projectId, reply.text());
        }
        catch (RuntimeException e) {
            log.warn("[dispatch] 项目 {} 分类调用失败（回落意见链）：{}", projectId, e.toString());
        }
        return Classification.OPINION_FALLBACK;
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
            return Classification.OPINION_FALLBACK; // 歧义兜底：宁进意见链（有 BA 把关）
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
