package com.aieducenter.aiplatform.business.project.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.FileChange;
import com.aieducenter.aiplatform.base.agentscope.RunHeading;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.order.application.OrderQueryAppService;
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignItem;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectFiles;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DesignItemStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignItemRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 设计过程编排（#289 设计执行体槽位＋首产编排；#291 改稿分代与在途排队＋定稿
 * 机制，ADR-0024/0025——与生成轨道 {@link GenerationAppService} 并列的两套产出
 * 逻辑，不挂 run 第三型、不套编码五段循环）：设计类终点项目 PRD 收口后自动起
 * 设计（轨内无门——触发律同生成无门，#101 对偶；入口＝终点属性本身，对话区提
 * 设计不自动转轨），按<b>首产推进序逐件串行</b>——序源自 PRD 清单章（设计主线
 * ＝设计物清单、系统＋设计＝功能清单按设计范围圈定，见 {@link DesignChecklist}；
 * 改序＝改 PRD：PRD 演进即清单重产、推进序随新清单，已收口件按标题对照保留不
 * 重做），<b>每设计物一个设计会话</b>（{@code designer-{projectId}-item-{ord}}
 * ——审美上下文靠会话连续性，<b>改稿同会话继续</b>：改稿产新代候选不覆盖前代
 * ——旧代全保留、横向分代，ADR-0025 候选集语义）、内部切分对用户透明（过程呈现
 * 复用事件封闭集：run-start 带 agent=designer 与设计物标题、写稿动作照常
 * part-action 播报——零新增 part 型）。
 *
 * <p><b>改稿与在途插话（#291）</b>：意见轮收口的设计派发携意见原文——设计轨
 * 在途即<b>排队</b>（同构迭代排队：当前稿代收口后排空受理、不丢失；多条意见
 * 按目标件合并成一场改稿 run——对偶修正轨排队合并）；轨道空闲且清单全部收口
 * /定稿即意见直接成<b>改稿</b>（目标＝最近活跃件，跨件回溯经画布点选的作用域
 * 消息直达任意件——{@link #reviseDesignItem} 即会话路由能力）。改稿收口判据
 * ＝对话收口（relaxed）：意见不涉改稿的回应（确认、提问）也是合法收口——
 * drafts 如实反映本场落稿（可能 0 稿），对偶修正 finish_edit(changed=false)
 * 「判定无需改动也合法收口」；首产判据照旧严格（本场 design/ 有新稿）。</p>
 *
 * <p><b>定稿机制（#291）</b>：定稿＝用户显式动作收口（{@link #finalizeDesignItem}
 * ——稿卡动作的 REST 面，画布接线归 #294）——件状态转已定稿、git commit 成版
 * （Run-Id 对偶锚定定稿收尾卡——收尾卡经 run-finish 载 closing 直达对话流，回访
 * 经对话史水合；「查看当时」即见定稿稿），并触发后续分岔：系统在途（已生成＋
 * 设计类终点＝中途切换）自动起更新 run 按稿对齐（交接物携定稿稿引用）；系统＋
 * 设计全部定稿自动起首个构建（稿入起跑上下文，经 {@code GenerationAppService}
 * 计划补产链自然恢复）；设计主线开放下单（确认下单可见性随全部定稿，下单面归
 * 交易票）。转系统开发走既有终点切换链（PRD 重写系统形后同序自动起构建，
 * {@code MainAgentAppService#requestEndpointShift}）。</p>
 *
 * <p><b>执行体座席</b>：设计执行体（{@link com.aieducenter.aiplatform.business.project.domain.model.AgentProfile#DESIGNER}）
 * 无 shell（ProjectDesign 工作区面：写文件件在、命令执行结构性关闭——界面类稿
 * 写可交互 HTML 落 design/，平面类出图经 generate_image 归 #292 真跑）；模型档位
 * 与 systemPrompt 经智能体运营配置覆盖（三座齐，ADR-0021）。首产收口判据＝本场
 * design/ 有新稿（平台可观察的文件变更事实，对偶生成轨 8081 探活——converse
 * 无异常不构成成功）；设计候选不自动成版（ADR-0025 候选与版本两套语义不打通，
 * 只有定稿成版）。收尾卡走收口扩载（closing.drafts 带本轮稿清单与去向），
 * 对话史落库同载荷（写口唯一口径不破）。</p>
 *
 * <p><b>失败与续跑</b>：某件超限转终态即发 {@code run-failed}、不自动跳下一件
 * （完整性优先，同生成轨道）；用户重提意见即经收口链再触发本编排——轨道表件行
 * 事实源（对偶断点续跑：已收口件跳过、失败/待跑件重做）。排队意见是进程内态
 * （同修正轨队列口径——重启即清，用户重提即兜底）；失败收场不排空队列（残队
 * 随下次派发合并受理，不丢失）。</p>
 */
@Service
@Slf4j
public class DesignProcessAppService {

    /** 设计轨会话前缀（对偶 coder-）：轨道执行键 designer-{projectId}、逐件会话 designer-{projectId}-item-{ord}。 */
    public static final String SESSION_PREFIX = "designer-";

    /** 设计物收口叙事前缀（收尾卡 summary；对偶切片收口叙事前缀）。 */
    static final String ITEM_CLOSING_PREFIX = "完成设计物：";

    /** 设计物收口叙事（收尾卡 summary 的拼装单点）。 */
    static String itemClosingSummary(String item) {
        return ITEM_CLOSING_PREFIX + item;
    }

    /** 改稿收口叙事前缀（#291 收尾卡 summary；对偶首产收口叙事前缀）。 */
    static final String REVISION_CLOSING_PREFIX = "改稿设计物：";

    /** 改稿收口叙事（收尾卡 summary 的拼装单点）。 */
    static String revisionClosingSummary(String item) {
        return REVISION_CLOSING_PREFIX + item;
    }

    /** 定稿收口叙事前缀（#291 定稿收尾卡 summary；成版提交主题同源）。 */
    static final String FINALIZE_CLOSING_PREFIX = "定稿设计物：";

    /** 定稿收口叙事（定稿收尾卡 summary 与成版提交主题的拼装单点）。 */
    static String finalizeClosingSummary(String item) {
        return FINALIZE_CLOSING_PREFIX + item;
    }

    /**
     * 定稿后续触发的用户面文案（#291 定稿收尾卡 triggers——后续分岔各触发的事实，
     * 呈现归收尾卡）。
     */
    static final String TRIGGER_ALIGN_STARTED = "系统已开始按定稿设计对齐";
    static final String TRIGGER_ALIGN_QUEUED = "系统更新已排入下一轮";
    static final String TRIGGER_BUILD_STARTED = "设计已全部定稿，系统开始生成";
    static final String TRIGGER_ORDER_OPEN = "设计已全部定稿，可以确认下单了";

    /**
     * 设计物 prompt（首试）：任务＝本件设计物的多稿候选产出——重读 PRD（本件
     * 用途/尺寸/风格/验收要点）、参考图可读、多稿方向有实质差异、稿只落 design/
     * 互不覆盖；形态约定（界面类 HTML／平面类出图档位表）住执行体工作协议，本
     * prompt 只携任务与清单锚。解说约定与生成轨道同源（#225 关键节点才解说）。
     */
    static String itemPrompt(int index, int total, String item, boolean systemDesign) {
        StringBuilder prompt = new StringBuilder("设计稿产出（设计物 ").append(index).append('/')
                .append(total).append("）：请为「").append(item)
                .append("」产出设计稿候选。")
                .append("\n先完整阅读工作区 docs/PRD.md（需求正本）——本设计物的用途、")
                .append("尺寸、风格要点与验收要点以清单章该条目为准；用户上传的参考图在")
                .append("工作区 materials/ 目录，可读取参考。")
                .append("\n产出要求：")
                .append("\n- 默认一次产出 3 稿候选，各稿方向要有实质差异（不同构图、配色、")
                .append("风格取向），不是同方向的微调复制；")
                .append("\n- 每稿独立落盘、互不覆盖、旧稿不改写——设计稿只落 design/ 目录")
                .append("（文件名含设计物词干与稿序）。");
        if (systemDesign) {
            prompt.append("\n本项目终点为系统＋设计：设计为功能清单的页面定方向（本设计物")
                    .append("来自功能清单页面集），界面类稿为主——系统实现归后续构建，")
                    .append("设计稿不含后端逻辑。");
        }
        return prompt
                .append("\n收口前用一段话向用户说明本设计物产出了哪几稿、各自方向。")
                .append(GenerationAppService.NARRATION_CONVENTION)
                .toString();
    }

    /**
     * 重试续作 prompt（原地修口径，对偶生成轨 #221）：携带错误现场、不重做已对
     * 的工作——收口判据（design/ 有新稿）照旧。
     */
    static String retryPrompt(String item, String errorScene) {
        return "上一次尝试失败了（错误现场：" + errorScene + "）。已落盘的设计稿仍然有效"
                + "——不要重做已对的工作。请针对该错误继续完成设计物「" + item
                + "」的设计稿产出（本场至少一稿新落 design/ 目录才算完成）。";
    }

    /**
     * 改稿 prompt（#291 改稿同会话继续——审美上下文靠会话连续性）：携用户意见
     * 原文，产<b>新代候选</b>——新稿文件名与既有各代不重（旧代全部保留、横向
     * 可对照，ADR-0025 候选集语义）；意见不涉改稿时文字回应即合法收口（0 稿如实
     * ——改稿收口判据是 relaxed，对偶修正「判定无需改动也合法收口」）。
     */
    static String revisionPrompt(String item, String feedback) {
        return "设计稿改稿（设计物「" + item + "」）：请按用户意见继续推进设计稿。"
                + "\n用户意见：" + feedback
                + "\n产出要求："
                + "\n- 产出新一代候选稿：与既有各代方向有实质推进（不是重复旧稿），"
                + "默认 3 稿、各稿方向有实质差异；"
                + "\n- 新稿独立落盘、文件名与既有各代不重——旧稿不改写不覆盖"
                + "（旧代全部保留、横向可对照）；设计稿只落 design/ 目录；"
                + "\n- 若该意见不需要产出新稿（确认、提问、定稿意向等），直接用一段话"
                + "向用户说明即可，无需强行出稿。"
                + "\n收口前用一段话向用户说明本代产出了哪几稿、相对上一代改了什么。"
                + GenerationAppService.NARRATION_CONVENTION;
    }

    /** 改稿重试续作 prompt（#221 原地修同构——携带错误现场，relaxed 判据照旧）。 */
    static String revisionRetryPrompt(String item, String errorScene) {
        return "上一次改稿尝试失败了（错误现场：" + errorScene + "）。已落盘的设计稿"
                + "仍然有效——不要重做已对的工作。请继续完成设计物「" + item
                + "」的本次改稿（若用户意见已满足，用一段话说明即可）。";
    }

    /**
     * 按稿对齐交接任务（#291 定稿后续分岔——系统在途的更新 run 交接物）：携定稿
     * 稿引用（CONTEXT「交接物」：设计定稿在途时附定稿设计稿引用），更新 run 据
     * 此把系统向稿对齐（视觉规范级——一致性桥三件套归 #295/#296）。
     */
    static String alignTask(String item, String draftPath) {
        return "设计定稿按稿对齐：设计物「" + item + "」已定稿，定稿稿在工作区 "
                + draftPath + "（请先阅读）。请把系统相应界面的视觉呈现与定稿稿"
                + "对齐（配色、字体、圆角、整体视觉语感——以定稿稿为准）；"
                + "若系统已与稿一致，如实说明无需改动。";
    }

    /** 设计轨逐件会话寻址（每设计物一个设计会话；重试续本件会话、改稿同会话继续归 #291）。 */
    static String itemSession(Long projectId, int ord) {
        return SESSION_PREFIX + projectId + "-item-" + ord;
    }

    /** 设计清单派发前的 PRD 读取命令（清单源＝工作区 PRD 正本，存储正本不两处）。 */
    private static final String READ_PRD_COMMAND =
            "cat '" + WorkspaceLayout.absolute(WorkspaceLayout.PRD) + "'";

    private final ProjectRepository projectRepository;
    private final DesignItemRepository designItems;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final AgentSessionExecutor sessionExecutor;
    private final CoderRunAttempts coderRunAttempts;
    private final AgentEventBridge eventBridge;
    private final CodingRunTrack codingRunTrack;
    private final TransactionTemplate transactionTemplate;
    private final ProjectVersionAppService versions;
    private final IterationAppService iterationAppService;
    private final GenerationAppService generationAppService;
    private final OrderQueryAppService orderQueryAppService;
    private final ConversationHistoryAppService conversationHistory;

    /**
     * 设计轨在途的排队意见（#291 在途插话，projectId → FIFO）：当前稿代收口后
     * 排空受理（同构修正轨排队——多条按目标件合并成一场改稿 run）。进程内态
     * （重启即清，用户重提即兜底）；失败收场不排空（残队随下次派发合并受理）。
     */
    private final Map<Long, Deque<QueuedRevision>> queuedRevisions = new ConcurrentHashMap<>();

    /** 最近活跃件序（#291 改稿目标派生：无作用域意见的缺省目标——最近跑过设计
     * 会话的件；重启丢失后回落清单最高已收口/定稿件）。 */
    private final Map<Long, Integer> lastActiveOrd = new ConcurrentHashMap<>();

    /** 排队意见（#291）：目标件序（null = 受理时派目标）＋意见原文。 */
    record QueuedRevision(Integer ord, String feedback) {
    }

    public DesignProcessAppService(ProjectRepository projectRepository,
            DesignItemRepository designItems,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            AgentSessionExecutor sessionExecutor, CoderRunAttempts coderRunAttempts,
            AgentEventBridge eventBridge, CodingRunTrack codingRunTrack,
            TransactionTemplate transactionTemplate, ProjectVersionAppService versions,
            IterationAppService iterationAppService, GenerationAppService generationAppService,
            OrderQueryAppService orderQueryAppService,
            ConversationHistoryAppService conversationHistory) {
        this.projectRepository = projectRepository;
        this.designItems = designItems;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.sessionExecutor = sessionExecutor;
        this.coderRunAttempts = coderRunAttempts;
        this.eventBridge = eventBridge;
        this.codingRunTrack = codingRunTrack;
        this.transactionTemplate = transactionTemplate;
        this.versions = versions;
        this.iterationAppService = iterationAppService;
        this.generationAppService = generationAppService;
        this.orderQueryAppService = orderQueryAppService;
        this.conversationHistory = conversationHistory;
    }

    /** 一场设计轨道的运行标识（首件 run 的用户面身份）。 */
    public record DesignRun(String runId) {
    }

    /**
     * PRD 收口自动起设计（#289 触发律＝轨内无门，对偶生成无门 #101；#291 扩意见
     * 分岔与已生成承接）：主智能体意见轮收口处观测设计类终点（未生成＝PRD 收口
     * 起设计、已生成＝系统中途切换的设计轨承接——{@code MainAgentAppService#
     * dispatchOnTurnClose} 单点路由）即调本口，携意见原文（#291）。路由三岔：
     * <ol>
     * <li><b>设计在途</b>——意见排队（{@link #queuedRevisions}，当前稿代收口后
     * 受理不丢失；返回 null，调用方不误报派发事实）；</li>
     * <li><b>轨道空闲且有待跑/失败件</b>——清单解析落表后起首产轨道（续跑口径，
     * 意见原文不进首产 prompt——重提即续跑的既有语义，对话面主智能体已回应）；</li>
     * <li><b>轨道空闲且清单全部收口/定稿</b>——意见即改稿（{@link #runRevision}
     * 同会话继续，目标＝最近活跃件；意见空白且无排队返回 null）。</li>
     * </ol>
     * 清单为空（PRD 形态偏离——无清单章或无编号条目）如实不派（返回 null，不造假
     * 清单；用户重提意见即兜底）。
     *
     * @param opinion 本轮意见原文（可空——非意见路径的派发；空白即无改稿语义）
     * @return 派发的 run 标识；在途排队、清单为空或无可改稿目标返回 null
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档；PRJ_017
     *                              已生成且非设计类终点（防御位——路由层已分岔）；
     *                              PRJ_018 PRD 从未产出；WSP_002 PRD 读取失败
     */
    public DesignRun dispatchDesignOnTurnClose(Long projectId, String opinion) {
        Project project = requireDesignableProject(projectId);
        if (!codingRunTrack.begin(projectId)) {
            // 在途插话排队（#291）：不丢失、不打断——当前稿代收口后排空受理
            enqueueRevision(projectId, null, opinion);
            log.info("[design] 项目 {} 设计轨道在途，意见已排队受理", projectId);
            return null;
        }
        List<String> checklist;
        try {
            checklist = resolveChecklist(project).items();
            if (checklist.isEmpty()) {
                codingRunTrack.end(projectId);
                log.warn("[design] 项目 {} PRD 清单章无编号条目（形态偏离），设计不派——用户重提意见即兜底",
                        projectId);
                return null;
            }
            recordChecklist(project, checklist);
        }
        catch (RuntimeException e) {
            codingRunTrack.end(projectId);
            throw e;
        }
        List<DesignItem> items = designItems.findByProjectIdOrderByOrdAsc(projectId);
        boolean runnable = items.stream().anyMatch(item -> item.getStatus() == DesignItemStatus.PENDING
                || item.getStatus() == DesignItemStatus.FAILED);
        if (runnable) {
            String firstRunId = EventsAppService.newRunId();
            sessionExecutor.submit(SESSION_PREFIX + projectId, () -> {
                try {
                    runDesignTrack(project, firstRunId);
                }
                finally {
                    codingRunTrack.end(projectId);
                }
            });
            return new DesignRun(firstRunId);
        }
        // 清单现行且全部收口/定稿：意见即改稿（#291 改稿同会话继续）——与残队
        // （失败收场滞留的排队意见）合并受理；空白意见且无残队即无事可派
        //（重提续跑类触发不产改稿噪音）
        Integer target = resolveRevisionTarget(projectId);
        if (target == null || (isBlank(opinion) && !hasQueuedRevisions(projectId))) {
            codingRunTrack.end(projectId);
            return null;
        }
        enqueueRevision(projectId, target, opinion);
        String firstRunId = EventsAppService.newRunId();
        sessionExecutor.submit(SESSION_PREFIX + projectId, () -> {
            try {
                sweepQueuedRevisions(project, null, firstRunId);
            }
            finally {
                codingRunTrack.end(projectId);
            }
        });
        return new DesignRun(firstRunId);
    }

    /**
     * 作用域改稿派发（#291 跨件回溯的会话路由能力——画布点选随到，画布侧接线归
     * #294）：意见携目标件序直达该件设计会话（同会话继续），不经主智能体轮——
     * 作用域即「这是设计意见」的显式声明（对用户隐式：画布点选后随话发送）。设计
     * 轨在途即排队（目标件锚定，当前稿代收口后受理）；空闲即起改稿（顺带排空残队）。
     *
     * @return 派发的 run 标识；在途排队返回 null（不误报派发事实）
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档；PRJ_017
     *                              已生成且非设计类终点（防御位）；PRJ_018 PRD
     *                              从未产出；PRJ_047 设计物不存在；PRJ_048 件
     *                              状态不可改稿（未产出设计稿）
     */
    public DesignRun reviseDesignItem(Long projectId, int ord, String feedback) {
        Project project = requireRevisionableProject(projectId, ord);
        // 守卫后重载（竞态窗口失位如实 404，不裸 orElseThrow 500）
        DesignItem item = designItems.findByProjectIdAndOrd(projectId, ord)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.DESIGN_ITEM_NOT_FOUND));
        if (!codingRunTrack.begin(projectId)) {
            enqueueRevision(projectId, ord, feedback);
            log.info("[design] 项目 {} 设计轨道在途，件 {} 改稿意见已排队受理", projectId, ord);
            return null;
        }
        String firstRunId = EventsAppService.newRunId();
        sessionExecutor.submit(SESSION_PREFIX + projectId, () -> {
            try {
                runRevision(project, item, feedback, firstRunId);
                sweepQueuedRevisions(project, null, null);
            }
            finally {
                codingRunTrack.end(projectId);
            }
        });
        return new DesignRun(firstRunId);
    }

    /**
     * 作用域改稿守卫（#291 REST 同步拒绝面——发言落库前的预检）：项目可设计
     * （存在/未归档/未冻结/PRD 在）＋件在＋件已产出稿。拒绝即零副作用（发言不落
     * 库——「落库 ⟺ 说过且被受理」不漂移，对齐意见轮守卫先于落库的既有口径）。
     */
    public void requireRevisionableItem(Long projectId, int ord) {
        requireRevisionableProject(projectId, ord);
    }

    /** 作用域改稿守卫体：{@link #requireDesignableProject} ＋件在＋件可改稿。 */
    private Project requireRevisionableProject(Long projectId, int ord) {
        Project project = requireDesignableProject(projectId);
        DesignItem item = designItems.findByProjectIdAndOrd(projectId, ord)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.DESIGN_ITEM_NOT_FOUND));
        requireRevisionable(item);
        return project;
    }

    /**
     * 设计轨道（异步轨道内，#289 首产推进序；#291 排队意见的受理点）：按清单序
     * 逐件派设计会话——已收口/已定稿件跳过（续跑口径）、失败/待跑件重做；某件
     * 失败不自动跳下一件（完整性优先，同生成轨道）。每件收口后（含全清单收尾）
     * 排空排队意见——当前稿代收口后受理（#291 在途插话），无目标意见派向刚收口
     * 件。首件 run 的 runId 即本轨用户面首 run 身份（对偶生成轨首段）。
     */
    private void runDesignTrack(Project project, String firstRunId) {
        Long projectId = project.getId();
        List<DesignItem> items = designItems.findByProjectIdOrderByOrdAsc(projectId);
        int total = items.size();
        boolean first = true;
        for (DesignItem item : items) {
            if (item.getStatus() == DesignItemStatus.CLOSED
                    || item.getStatus() == DesignItemStatus.FINALIZED) {
                continue; // 已收口/已定稿件不重做（续跑/清单重产对照保留——定稿锚不覆写）
            }
            String runId = first ? firstRunId : EventsAppService.newRunId();
            first = false;
            String itemTitle = item.getTitle();
            boolean systemDesign = project.getEndpointType() == ProjectEndpointType.SYSTEM_DESIGN;
            CoderRunAttempts.RunResult result = coderRunAttempts.run(project, runId,
                    itemSession(projectId, item.getOrd()),
                    new CoderRunAttempts.Prompts(
                            itemPrompt(item.getOrd(), total, itemTitle, systemDesign),
                            errorScene -> retryPrompt(itemTitle, errorScene)),
                    (attemptRunId, attemptChanges) -> closeDesignItem(project, item,
                            attemptRunId, attemptChanges),
                    CoderRunAttempts.DESIGN_LABEL, /* injectKnowledge= */ false,
                    CoderRunAttempts.RunSeat.designer(itemTitle),
                    RunHeading.slice(itemTitle, item.getOrd(), total));
            if (!result.succeeded()) {
                failDesignItem(projectId, item.getOrd(), runId);
                eventBridge.emitRunFailed(projectId, project.getOwnerAccountId(), runId);
                return; // 残队不排空（#291）：随下次派发合并受理
            }
            sweepQueuedRevisions(project, item.getOrd(), null);
        }
        sweepQueuedRevisions(project, null, null);
    }

    /**
     * 排队意见入队（#291 在途插话/作用域改稿共口）：空白意见不入队（重提续跑类
     * 触发不产改稿噪音）；ord null＝受理时派目标（轨道内＝刚收口件，独立改稿轨
     * ＝最近活跃件）。
     */
    private void enqueueRevision(Long projectId, Integer ord, String feedback) {
        if (isBlank(feedback)) {
            return;
        }
        queuedRevisions.computeIfAbsent(projectId, key -> new ConcurrentLinkedDeque<>())
                .add(new QueuedRevision(ord, feedback.strip()));
    }

    private boolean hasQueuedRevisions(Long projectId) {
        Deque<QueuedRevision> queue = queuedRevisions.get(projectId);
        return queue != null && !queue.isEmpty();
    }

    /**
     * 排空排队意见（#291 当前稿代收口后受理）：按目标件分组、每组合并成一场改稿
     * run（多条意见以「；」连缀——对偶修正轨排队合并）；无目标意见派向
     * {@code justClosedOrd}（轨道内刚收口件；独立改稿轨传 null 派最近活跃件）。
     * 目标件竞态失位（清单重产换序/状态翻失败）如实跳过留日志——意见已从队列
     * 取出，用户重提即兜底（不静默重投，防改稿轰炸）。firstRunId 非空时锚定首场
     * 改稿（派发响应的用户面身份）。
     */
    private void sweepQueuedRevisions(Project project, Integer justClosedOrd, String firstRunId) {
        Long projectId = project.getId();
        Deque<QueuedRevision> queue = queuedRevisions.remove(projectId);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> grouped = new LinkedHashMap<>();
        for (QueuedRevision entry : queue) {
            Integer ord = entry.ord() != null ? entry.ord()
                    : justClosedOrd != null ? justClosedOrd : resolveRevisionTarget(projectId);
            if (ord != null) {
                grouped.computeIfAbsent(ord, key -> new ArrayList<>()).add(entry.feedback());
            }
        }
        boolean first = true;
        for (Map.Entry<Integer, List<String>> target : grouped.entrySet()) {
            DesignItem item = designItems.findByProjectIdAndOrd(projectId, target.getKey())
                    .orElse(null);
            if (item == null || !isRevisionable(item)) {
                log.warn("[design] 项目 {} 排队改稿的目标件 {} 失位（清单重产/状态翻转），如实跳过",
                        projectId, target.getKey());
                continue;
            }
            String runId = first && firstRunId != null ? firstRunId : EventsAppService.newRunId();
            first = false;
            log.info("[design] 项目 {} 受理排队改稿（件 {}，合并 {} 条意见）",
                    projectId, target.getKey(), target.getValue().size());
            runRevision(project, item, String.join("；", target.getValue()), runId);
        }
    }

    /** 件可改稿判定（#291）：已产出设计稿（已收口/已定稿）才有会话与稿可改。 */
    private static boolean isRevisionable(DesignItem item) {
        return item.getStatus() == DesignItemStatus.CLOSED
                || item.getStatus() == DesignItemStatus.FINALIZED;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private void requireRevisionable(DesignItem item) {
        if (!isRevisionable(item)) {
            throw new ApplicationException(ProjectMessage.DESIGN_ITEM_NOT_READY);
        }
    }

    /**
     * 改稿目标派生（#291 无作用域意见的缺省目标）：最近活跃件（进程内指针，随
     * 首产/改稿收口推进——「刚才那稿」的语义锚）；失位（重启丢失/清单重产）回落
     * 清单最高已收口/定稿件；无件可改返回 null。
     */
    private Integer resolveRevisionTarget(Long projectId) {
        Integer active = lastActiveOrd.get(projectId);
        if (active != null) {
            DesignItem item = designItems.findByProjectIdAndOrd(projectId, active).orElse(null);
            if (item != null && isRevisionable(item)) {
                return active;
            }
        }
        return designItems.findByProjectIdOrderByOrdAsc(projectId).stream()
                .filter(DesignProcessAppService::isRevisionable)
                .map(DesignItem::getOrd)
                .max(Integer::compareTo)
                .orElse(null);
    }

    /**
     * 一场改稿 run（#291 改稿同会话继续）：同件设计会话（审美上下文连续）、携
     * 意见原文产新代候选；收口判据＝对话收口（relaxed——意见不涉改稿的回应也是
     * 合法收口，drafts 如实可能 0 稿；对偶修正 finish_edit(changed=false)）。改稿
     * 失败发 {@code run-failed}（唯一失败终态）——件状态不翻失败（首产收口位
     * 保持、定稿锚不覆写），用户重提即兜底。
     *
     * @return 收场事实（成败）
     */
    private CoderRunAttempts.RunResult runRevision(Project project, DesignItem item,
            String feedback, String runId) {
        Long projectId = project.getId();
        CoderRunAttempts.RunResult result = coderRunAttempts.run(project, runId,
                itemSession(projectId, item.getOrd()),
                new CoderRunAttempts.Prompts(
                        revisionPrompt(item.getTitle(), feedback),
                        errorScene -> revisionRetryPrompt(item.getTitle(), errorScene)),
                (attemptRunId, attemptChanges) -> closeRevision(item, attemptRunId),
                CoderRunAttempts.DESIGN_LABEL, /* injectKnowledge= */ false,
                CoderRunAttempts.RunSeat.designer(item.getTitle()),
                RunHeading.slice(item.getTitle(), item.getOrd(),
                        designItems.findByProjectIdOrderByOrdAsc(projectId).size()));
        lastActiveOrd.put(projectId, item.getOrd());
        if (!result.succeeded()) {
            log.warn("[design] 项目 {} 件 {} 改稿超限转终态（runId={}）——件状态保持，用户重提即兜底",
                    projectId, item.getOrd(), runId);
            eventBridge.emitRunFailed(projectId, project.getOwnerAccountId(), runId);
        }
        return result;
    }

    /**
     * 改稿收口（#291 relaxed 判据）：对话收口即收口——不校验本场落稿（0 稿如实，
     * 判定行仍 PRD 未动/系统未动）；非定稿件的 run 锚随改稿刷新（最近设计会话
     * 事实），已定稿件的定稿锚不覆写（定稿选择保持到再定稿）。
     */
    private CoderRunAttempts.ClosingJudgment closeRevision(DesignItem item, String attemptRunId) {
        if (item.getStatus() != DesignItemStatus.FINALIZED) {
            recordItemStatus(item.getProjectId(), item.getOrd(),
                    row -> row.close(attemptRunId));
        }
        return CoderRunAttempts.ClosingJudgment.design(revisionClosingSummary(item.getTitle()));
    }

    /**
     * 单件收口判据与落位（#289）：本场 design/ 有新稿才收口——converse 无异常不
     * 构成成功（执行体可能道歉式放弃，对偶生成轨 8081 探门口径；判据看本场累计
     * 变更——重试尝试不因「本次没新写」误判）；核验不过抛异常，被尝试环当作该次
     * 尝试失败（走重试/终态）。收口即件状态落表（对偶片状态落表）；判定行＝PRD
     * 未动、系统未动（设计稿不是系统），summary＝本场设计物叙事，稿清单在
     * closing.drafts 扩载（拼装归尝试环）。
     */
    private CoderRunAttempts.ClosingJudgment closeDesignItem(Project project, DesignItem item,
            String attemptRunId, List<FileChange> sessionChanges) {
        boolean drafted = sessionChanges.stream()
                .map(FileChange::path)
                .anyMatch(CoderRunAttempts::designAnchored);
        if (!drafted) {
            throw new IllegalStateException(
                    "设计会话未产出设计稿（design/ 目录无新稿）——收口判据未过");
        }
        recordItemStatus(project.getId(), item.getOrd(), row -> row.close(attemptRunId));
        lastActiveOrd.put(project.getId(), item.getOrd());
        return CoderRunAttempts.ClosingJudgment.design(itemClosingSummary(item.getTitle()));
    }

    /** 件失败状态落表（尝试环超限终态）。 */
    private void failDesignItem(Long projectId, int ord, String runId) {
        recordItemStatus(projectId, ord, row -> row.fail(runId));
    }

    /**
     * 件状态落位：轨道表是推进进度的事实源，但落表失败不反噬 run——只记日志
     * （收口事实仍在收尾卡；缺行 = 续跑多做一件，安全向，同生成轨道口径）。
     * 幂等覆写（重派后再收口/失败即刷新——状态是「最近一次尝试的结局」）。
     */
    private void recordItemStatus(Long projectId, int ord, Consumer<DesignItem> transition) {
        try {
            designItems.findByProjectIdAndOrd(projectId, ord).ifPresent(row -> {
                transition.accept(row);
                designItems.save(row);
            });
        }
        catch (RuntimeException e) {
            log.warn("[design] 项目 {} 设计物 {} 状态落表失败（不反噬 run）：{}", projectId, ord,
                    e.toString());
        }
    }

    /**
     * 清单解析（清单源＝工作区 PRD 正本）：读回 PRD markdown 按终点类型解析清单
     * 章（{@link DesignChecklist}）。PRD 未读回（环境故障）如实上抛——设计不
     * 起跑（无清单事实不空跑）。
     */
    private DesignChecklist resolveChecklist(Project project) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(READ_PRD_COMMAND));
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "PRD 读取失败: " + result.stderr());
        }
        return DesignChecklist.parse(result.stdout(), project.getEndpointType(),
                project.designScope());
    }

    /**
     * 清单落轨道表（PRD 版本锚门，对偶计划落表 #220）：表内现行清单（锚一致）
     * 不重落——推进事实在表；锚漂（PRD 演进＝改序改清单）或首落＝整组替换，
     * 已收口/<b>已定稿</b>件按标题精确对照保留（推进序随新清单、收口不重做、
     * 定稿锚与选定稿随对照续承——#291 修正：定稿不因 PRD 演进蒸发重做；措辞
     * 漂移即对照不上、按待跑重做，降级方向安全：多跑不漏做）。短事务原子替换
     * （删后先冲刷再插——同事务冲刷序先插后删，不冲刷会撞 (project_id, ord)
     * 唯一键）。
     */
    private void recordChecklist(Project project, List<String> items) {
        Long projectId = project.getId();
        List<DesignItem> current = designItems.findByProjectIdOrderByOrdAsc(projectId);
        if (DesignItem.checklistMatchesPrd(current, project.getPrdProducedAt())) {
            return;
        }
        Map<String, DesignItem> completedByTitle = current.stream()
                .filter(row -> row.getStatus() == DesignItemStatus.CLOSED
                        || row.getStatus() == DesignItemStatus.FINALIZED)
                .collect(Collectors.toMap(DesignItem::getTitle, row -> row,
                        (earlier, later) -> later));
        transactionTemplate.executeWithoutResult(status -> {
            designItems.deleteByProjectId(projectId);
            designItems.flush();
            for (int index = 0; index < items.size(); index++) {
                DesignItem row = DesignItem.pending(projectId, index + 1, items.get(index),
                        project.getPrdProducedAt());
                DesignItem prior = completedByTitle.get(items.get(index));
                if (prior != null && prior.getStatus() == DesignItemStatus.FINALIZED) {
                    row.finalize(prior.getRunId(), prior.getFinalizedPath());
                }
                else if (prior != null) {
                    row.close(prior.getRunId());
                }
                designItems.save(row);
            }
        });
    }

    /**
     * 定稿（#291 定稿机制——显式动作收口，稿卡动作的 REST 面）：候选中锁定一稿。
     * 守卫序＝存在 → 未归档 → 未冻结（订单）→ 轨道空闲（在途 run 读写工作区，
     * 成版全量提交会卷入在途改动——收口后再定稿）→ 件在 → 件已产出稿 → 稿在
     * 工作区（容器事实——候选可被悬卡删除）。落定序＝件状态成版落表 → git commit
     * 成版（Run-Id 对偶锚定定稿收尾卡，「查看当时」即见定稿稿）→ 后续分岔派发 →
     * 定稿收尾卡入对话流（对话史落库＋run-finish 载 closing 直达——SSE 事件封闭
     * 零新增）。成版/落卡失败不反噬定稿（件状态是事实、回访经轨道表——quietly
     * 留日志，同收口成版先例）。
     *
     * <p><b>后续分岔</b>（ADR-0024 触发律：定稿自动转后续、紧跟的门是冗余门）：
     * 系统在途（已生成＋设计类终点＝中途切换）自动起更新 run 按稿对齐（交接物
     * 携定稿稿引用，撞在途排队）；系统＋设计未生成且全部定稿自动起首个构建（经
     * 计划补产链——PRD 收口的设计路由对全部定稿项目放行生成）；设计主线全部
     * 定稿即开放下单（确认下单可见性事实，下单面归交易票）。分岔的触发事实进
     * 收尾卡 triggers。</p>
     *
     * @return 定稿事实（runId＝定稿锚/成版 commit hash——hash 可空＝本轮未成版）
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档；ORD_006
     *                              订单处理中；PRJ_046 设计/更新轨在途；PRJ_047
     *                              设计物不存在；PRJ_048 件未产出稿；PRJ_049 稿
     *                              不在工作区；PRJ_020 稿路径不可浏览；WSP_002
     *                              存在性检查失败（环境故障）
     */
    public DesignFinalization finalizeDesignItem(Long projectId, int ord, String draftPath) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        orderQueryAppService.requireNoActiveOrder(projectId);
        if (codingRunTrack.isInFlight(projectId)) {
            throw new ApplicationException(ProjectMessage.DESIGN_FINALIZE_IN_FLIGHT);
        }
        DesignItem item = designItems.findByProjectIdAndOrd(projectId, ord)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.DESIGN_ITEM_NOT_FOUND));
        requireRevisionable(item);
        String relative = draftPath != null && draftPath.startsWith("/")
                ? draftPath.substring(1) : draftPath;
        if (!CoderRunAttempts.designAnchored(draftPath) || !ProjectFiles.isViewable(relative)) {
            throw new ApplicationException(ProjectMessage.FILE_PATH_INVALID);
        }
        ExecResultResponse exists = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()),
                new WorkspaceExecCommand(ProjectFiles.existenceCommand(relative)));
        if (exists == null || exists.exitCode() != 0) {
            throw new ApplicationException(ProjectMessage.DESIGN_DRAFT_NOT_FOUND);
        }
        Instant startedAt = Instant.now();
        // 定稿落位（先于成版——状态是事实源，成版/落卡失败不反噬）
        String runId = EventsAppService.newRunId();
        item.finalize(runId, draftPath);
        designItems.save(item);
        lastActiveOrd.put(projectId, ord);
        String versionHash = versions.commitAtClosing(project, runId,
                finalizeClosingSummary(item.getTitle()));
        List<String> triggers = dispatchFinalizeTriggers(project, item, draftPath);
        emitFinalizeClosingCard(project, item, draftPath, runId, versionHash, triggers,
                startedAt);
        return new DesignFinalization(runId, versionHash);
    }

    /** 定稿事实（#291 REST 面）：runId＝定稿锚（收尾卡/成版 Run-Id trailer 同锚）。 */
    public record DesignFinalization(String runId, String versionHash) {
    }

    /**
     * 定稿后续分岔（#291 三分岔——派发即事实，跟职责走：更新 run 归迭代编排、
     * 首个构建归生成编排、开放下单是可见性事实非派发）。失败不反噬定稿（分岔
     * 派发自身有兜底：更新撞在途排队、构建经补产链——异常如实上抛前定稿已落定，
     * 调用方收错误但定稿成立，重试幂等覆写）。
     */
    private List<String> dispatchFinalizeTriggers(Project project, DesignItem item,
            String draftPath) {
        boolean allFinalized = designFinalized(project);
        switch (project.getEndpointType()) {
            case SYSTEM_DESIGN -> {
                if (project.getGeneratedAt() != null) {
                    // 系统在途（中途切换）：定稿即起更新 run 按稿对齐（交接物携稿引用）
                    IterationAppService.FixDispatch dispatch = iterationAppService.startFixRun(
                            project.getId(), alignTask(item.getTitle(), draftPath), null);
                    return List.of(dispatch.queued()
                            ? TRIGGER_ALIGN_QUEUED : TRIGGER_ALIGN_STARTED);
                }
                if (allFinalized) {
                    // 系统＋设计设计先行：全部定稿自动起首个构建（计划补产链自然恢复）
                    generationAppService.dispatchGenerationOnTurnClose(project.getId(), null);
                    return List.of(TRIGGER_BUILD_STARTED);
                }
                return List.of();
            }
            case DESIGN -> {
                // 设计主线：全部定稿即开放下单（可见性事实——确认下单随全部定稿常驻）
                return allFinalized ? List.of(TRIGGER_ORDER_OPEN) : List.of();
            }
            default -> {
                // SYSTEM 终点：设计物无后续分岔（终点已回系统形，稿入版本流即全部）
                return List.of();
            }
        }
    }

    /**
     * 定稿收尾卡（#291）：closing 复用收口扩载形——判定行恒未动（定稿不是产出
     * 轮）、稿清单单条（定稿稿＋triggers 后续触发）、成版锚进 version（收尾卡
     * 版本控件即「查看当时」，对偶执行体收口成版）。对话史落库（回访水合）＋
     * run-finish 直达（live 入流）同载荷；落库/发射失败只记日志（呈现不反噬定稿）。
     */
    private void emitFinalizeClosingCard(Project project, DesignItem item, String draftPath,
            String runId, String versionHash, List<String> triggers, Instant startedAt) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("item", item.getTitle());
        draft.put("media", CoderRunAttempts.mediaOf(draftPath));
        draft.put("path", draftPath);
        draft.put("triggers", triggers);
        Map<String, Object> closing = new LinkedHashMap<>();
        closing.put(CoderRunAttempts.CLOSING_SUMMARY_FIELD,
                finalizeClosingSummary(item.getTitle()));
        closing.put("prdChanged", false);
        closing.put("systemChanged", false);
        closing.put("files", List.of());
        closing.put("durationMs", Duration.between(startedAt, Instant.now()).toMillis());
        if (versionHash != null) {
            closing.put(CoderRunAttempts.CLOSING_VERSION_FIELD, versionHash);
        }
        closing.put(CoderRunAttempts.CLOSING_DRAFTS_FIELD, List.of(draft));
        try {
            conversationHistory.recordClosing(project.getId(), runId, closing);
        }
        catch (RuntimeException e) {
            log.warn("[design] 项目 {} 定稿收尾卡落库失败（定稿事实不受影响）：{}",
                    project.getId(), e.toString());
        }
        eventBridge.emitClosingCard(project.getId(), project.getOwnerAccountId(), runId,
                itemSession(project.getId(), item.getOrd()), closing);
    }

    /**
     * 清单定稿完毕判定（#291 定稿后续分岔的守卫单口——路由与生成门共用）：
     * 现行清单（PRD 锚一致）且全部已定稿。空件集/锚漂（未落或过期）＝false。
     */
    public boolean designFinalized(Project project) {
        return DesignItem.checklistFinalized(
                designItems.findByProjectIdOrderByOrdAsc(project.getId()),
                project.getPrdProducedAt());
    }

    /**
     * 可设计守卫：存在 / 未归档 / 未冻结（订单——设计派发与改稿都是迭代动作，下单
     * 即冻结、取消即解冻）/ PRD 已产出（清单源事实——无 PRD 无清单可解析）。
     * 已生成项目对<b>设计类终点</b>放行（#291 系统中途切换的设计轨承接——生成过
     * 的系统＋切换终点即设计先行续做设计）；系统终点已生成不可达本口（路由层
     * 已分岔，PRJ_017 是防御位）。
     */
    private Project requireDesignableProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        orderQueryAppService.requireNoActiveOrder(projectId);
        if (project.getGeneratedAt() != null
                && !project.getEndpointType().designInvolved()) {
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        if (project.getPrdProducedAt() == null) {
            throw new ApplicationException(ProjectMessage.GENERATION_PRD_NOT_PRODUCED);
        }
        return project;
    }
}
