package com.aieducenter.aiplatform.business.project.application;

import java.util.List;
import java.util.Map;
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
import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignItem;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.DesignItemStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.repository.DesignItemRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 设计过程编排（#289 设计执行体槽位＋首产编排，ADR-0024/0025——与生成轨道
 * {@link GenerationAppService} 并列的两套产出逻辑，不挂 run 第三型、不套编码
 * 五段循环）：设计类终点项目 PRD 收口后自动起设计（轨内无门——触发律同生成
 * 无门，#101 对偶；入口＝终点属性本身，对话区提设计不自动转轨），按<b>首产
 * 推进序逐件串行</b>——序源自 PRD 清单章（设计主线＝设计物清单、系统＋设计＝
 * 功能清单按设计范围圈定，见 {@link DesignChecklist}；改序＝改 PRD：PRD 演进
 * 即清单重产、推进序随新清单，已收口件按标题对照保留不重做），<b>每设计物
 * 一个设计会话</b>（{@code designer-{projectId}-item-{ord}}——审美上下文靠会话
 * 连续性，改稿同会话继续归 #291）、内部切分对用户透明（过程呈现复用事件封闭
 * 集：run-start 带 agent=designer 与设计物标题、写稿动作照常 part-action 播报
 * ——零新增 part 型）。
 *
 * <p><b>执行体座席</b>：设计执行体（{@link com.aieducenter.aiplatform.business.project.domain.model.AgentProfile#DESIGNER}）
 * 无 shell（ProjectDesign 工作区面：写文件件在、命令执行结构性关闭——界面类稿
 * 写可交互 HTML 落 design/，平面类出图经 generate_image 归 #292 真跑）；模型档位
 * 与 systemPrompt 经智能体运营配置覆盖（三座齐，ADR-0021）。收口判据＝本场
 * design/ 有新稿（平台可观察的文件变更事实，对偶生成轨 8081 探活——converse
 * 无异常不构成成功）；设计候选不自动成版（ADR-0025 候选与版本两套语义不打通，
 * 定稿机制归 #291）。收尾卡走收口扩载（closing.drafts 带本轮稿清单与去向），
 * 对话史落库同载荷（写口唯一口径不破）。</p>
 *
 * <p><b>失败与续跑</b>：某件超限转终态即发 {@code run-failed}、不自动跳下一件
 * （完整性优先，同生成轨道）；用户重提意见即经收口链再触发本编排——轨道表件行
 * 事实源（对偶断点续跑：已收口件跳过、失败/待跑件重做）。设计在途的新派发静默
 * 跳过（在途插话排队归 #291）。</p>
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

    public DesignProcessAppService(ProjectRepository projectRepository,
            DesignItemRepository designItems,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            AgentSessionExecutor sessionExecutor, CoderRunAttempts coderRunAttempts,
            AgentEventBridge eventBridge, CodingRunTrack codingRunTrack,
            TransactionTemplate transactionTemplate) {
        this.projectRepository = projectRepository;
        this.designItems = designItems;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.sessionExecutor = sessionExecutor;
        this.coderRunAttempts = coderRunAttempts;
        this.eventBridge = eventBridge;
        this.codingRunTrack = codingRunTrack;
        this.transactionTemplate = transactionTemplate;
    }

    /** 一场设计轨道的运行标识（首件 run 的用户面身份）。 */
    public record DesignRun(String runId) {
    }

    /**
     * PRD 收口自动起设计（#289 触发律＝轨内无门，对偶生成无门 #101）：主智能体
     * 意见轮收口处观测「设计类终点 && 未生成 && 已产出 PRD」即调本口（{@code
     * MainAgentAppService#dispatchOnTurnClose} 单点路由）。守卫＝存在/未归档/
     * PRD 已产出（守的是动作成立的前置事实）；清单解析（读工作区 PRD）→ 清单落
     * 轨道表（PRD 锚门）→ 异步提交设计轨道。清单为空（PRD 形态偏离——无清单章
     * 或无编号条目）如实不派（返回 null，不造假清单；用户重提意见即兜底）。设计
     * 在途静默跳过（新意见排队归 #291）。已生成项目不走本口（系统中途切换的
     * 设计过程启动接线归后续票）。
     *
     * @return 派发的 run 标识；在途或清单为空返回 null——调用方据此区分「已派」
     *         与「未派」，不误报派发事实
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档；PRJ_018
     *                              PRD 从未产出；WSP_002 PRD 读取失败（环境故障）
     */
    public DesignRun dispatchDesignOnTurnClose(Long projectId) {
        Project project = requireDesignableProject(projectId);
        if (!codingRunTrack.begin(projectId)) {
            log.info("[design] 项目 {} 设计轨道在途，收口自动派发静默跳过", projectId);
            return null;
        }
        List<String> items;
        try {
            items = resolveChecklist(project).items();
            if (items.isEmpty()) {
                codingRunTrack.end(projectId);
                log.warn("[design] 项目 {} PRD 清单章无编号条目（形态偏离），设计不派——用户重提意见即兜底",
                        projectId);
                return null;
            }
            recordChecklist(project, items);
        }
        catch (RuntimeException e) {
            codingRunTrack.end(projectId);
            throw e;
        }
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

    /**
     * 设计轨道（异步轨道内，#289 首产推进序）：按清单序逐件派设计会话——已收口
     * 件跳过（续跑口径）、失败/待跑件重做；某件失败不自动跳下一件（完整性优先，
     * 同生成轨道）。首件 run 的 runId 即本轨用户面首 run 身份（对偶生成轨首段）。
     */
    private void runDesignTrack(Project project, String firstRunId) {
        Long projectId = project.getId();
        List<DesignItem> items = designItems.findByProjectIdOrderByOrdAsc(projectId);
        int total = items.size();
        boolean first = true;
        for (DesignItem item : items) {
            if (item.getStatus() == DesignItemStatus.CLOSED) {
                continue; // 已收口件不重做（续跑/清单重产对照保留）
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
                return;
            }
        }
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
     * 已收口件按标题精确对照保留（推进序随新清单、已收口不重做；措辞漂移即
     * 对照不上、按待跑重做，降级方向安全：多跑不漏做）。短事务原子替换（删后
     * 先冲刷再插——同事务冲刷序先插后删，不冲刷会撞 (project_id, ord) 唯一键）。
     */
    private void recordChecklist(Project project, List<String> items) {
        Long projectId = project.getId();
        List<DesignItem> current = designItems.findByProjectIdOrderByOrdAsc(projectId);
        if (DesignItem.checklistMatchesPrd(current, project.getPrdProducedAt())) {
            return;
        }
        Map<String, String> closedTitles = current.stream()
                .filter(row -> row.getStatus() == DesignItemStatus.CLOSED)
                .collect(Collectors.toMap(DesignItem::getTitle, DesignItem::getRunId,
                        (earlier, later) -> later));
        transactionTemplate.executeWithoutResult(status -> {
            designItems.deleteByProjectId(projectId);
            designItems.flush();
            for (int index = 0; index < items.size(); index++) {
                DesignItem row = DesignItem.pending(projectId, index + 1, items.get(index),
                        project.getPrdProducedAt());
                String closedRunId = closedTitles.get(items.get(index));
                if (closedRunId != null) {
                    row.close(closedRunId);
                }
                designItems.save(row);
            }
        });
    }

    /**
     * 可设计守卫：存在 / 未归档 / 未生成（已生成项目的系统中途切换设计接线归
     * 后续票）/ PRD 已产出（清单源事实——无 PRD 无清单可解析）。设计类终点判定
     * 在路由点（MainAgentAppService 收口路由），本守卫不重复判（系统终点项目
     * 不可达本口）。
     */
    private Project requireDesignableProject(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        if (project.getGeneratedAt() != null) {
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        if (project.getPrdProducedAt() == null) {
            throw new ApplicationException(ProjectMessage.GENERATION_PRD_NOT_PRODUCED);
        }
        return project;
    }
}
