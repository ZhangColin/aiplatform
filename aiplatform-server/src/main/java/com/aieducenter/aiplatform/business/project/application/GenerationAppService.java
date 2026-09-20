package com.aieducenter.aiplatform.business.project.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentscope.AgentSessionExecutor;
import com.aieducenter.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.aieducenter.aiplatform.base.agentscope.RunHeading;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.domain.aggregate.GenerationSegment;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.enums.GenerationSegmentStatus;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;
import com.aieducenter.aiplatform.business.project.domain.repository.GenerationSegmentRepository;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 生成编排（#22 片2-1；#101 生成无门自动发起——「开始做系统」按钮退役，生成
 * 触发权归平台；#104 生成轨道——单 run 换「阶段 0 先起服 + 纵向切片逐段」多 run）：
 * 主智能体产出 PRD 后意见轮收口即自动派首次生成轨
 * （{@link #dispatchGenerationOnTurnClose}），显式端点（POST /generate）与中断
 * 「继续生成」兜底走同一编排（{@link #startGeneration}——重发即断点续跑，#221）。
 * run 执行体与主智能体
 * 同构（AgentScope HarnessAgent 经 {@link AgentscopeAgentClient} 直调——编排缝
 * 极薄），仅资产与工具不同：会话每片/每 run 换新（#114 会话有界——每片新会话
 * {@code coder-{projectId}-slice-{n}}，重试续本片会话）、配置 = 平台技术约定
 * + 实现协议（{@link AgentProfile#EXECUTOR}）、无业务工具（编码工具由 harness
 * 内核自带）。
 *
 * <p><b>生成轨道（#104）</b>：按切片计划（{@link BuildPlan}，主智能体产出的有序
 * 纵向切片）顺序多 run——阶段 0（先起服白底页）固定前置，此后逐片长出。每片收口
 * 判据 = 8081 可达（平台侧检查点） + 成版 + run-finish（收口扩载；「端到端可操作」
 * 由片内 self-test 兜）。失败语义：某片超限转终态即发 {@code run-failed} 收口、
 * 不自动跳下一片；{@code generated_at} 落最后一片收口（口径不变——阶段 0 / 中间
 * 片不落位）。</p>
 *
 * <p><b>生成轨道表（#220，ADR-0020）</b>：切片计划与每片收口/失败状态落平台库
 * （{@code prj_generation_segments}，「run 无表、重启即清」口径的精确例外）
 * ——进程重启后计划与片进度仍可查，断点 = 表中最深收口片（续跑事实源）。计划
 * 生命周期跟 PRD 版本走：落库时记 {@code prd_produced_at} 版本锚，PRD 演进即
 * 锚不一致、旧计划不沿用，重产 = 整组替换。计划缺失不兜假计划（minimalFallback
 * 已删）——重派主智能体按 PRD 补产（{@link MainAgentAppService#requestBuildPlan}，
 * 补产轮收口自动再派生成）。</p>
 *
 * <p><b>断点续跑（#221，ADR-0020）</b>：「继续生成」从断点接续——跳过已收口片、
 * 只重跑失败/中断片（断点后首段），起手带现状盘点（工作区现状指引＋切片进度＋
 * 中断原因＋中断前摘要），交接机制与片间交接同构（平台拼轨道表事实、不产自由
 * 文本）；全场景同一脏续口径（重试耗尽 run-failed 后重发、进程重启后重发）。
 * 断点以轨道表为准、git 收口 commit（Run-Id 锚定收尾卡）为旁证；干净重做不设
 * 默认路径——PRD 演进即计划重产从头来（用户的显式选择）。</p>
 *
 * <p><b>存量恢复（#223，ADR-0020「存量在途项目走计划重派路径恢复」）</b>：生成
 * 早于轨道表的在途项目（表内无片行）发起「继续生成」＝计划重派恢复——补产轮
 * 携已收口成果清单（收尾卡叙事，要求已完片照录原文），计划落库时对照收尾卡
 * <b>精确匹配</b>（片收口叙事逐字相等）标已完片（run 锚 = 往次收口 run），续跑只补
 * 缺口；措辞漂移即对照不上、按待跑重做（降级方向安全：多跑不漏做）。PRD 已演进
 * 的换锚重产（表内有旧片行）不适用存量对照——整组替换重置、从头来是既定口径。</p>
 *
 * <p><b>纯动作无门</b>：待定项未清也可发起（守卫只有项目存在 / 未归档 /
 * 未生成过 / PRD 已产出）；重复触发（已生成或生成在途）拒绝 PRJ_017。</p>
 *
 * <p><b>过程事件恒挂</b>：消息部件（解说段 + 动作卡）随全部智能体
 * 事件流产出（parts 契约，前端长成工作消息）。</p>
 *
 * <p><b>知识命中前置注入</b>（#24 生成环③）：下发前以首试任务 prompt 检索知识库
 * （query 截 2000 字、topK=5），命中块拼在任务 prompt <b>前</b>（知识是背景非
 * 指令）；检索失败降级空注入、run 照旧下发。一次切入一次注入（#114 生成链首片）——
 * 只在阶段 0 注入、切片不重注入（注入口径不膨胀）；单 run 内重试续本片会话（注入
 * 块已在会话历史），不重检索不重注入。</p>
 *
 * <p><b>工作区布局资产就位</b>：下发前把平台约定写入工作区 AGENTS.md
 * （幂等覆写，内容平台所有）——run 执行体经 harness 工作区上下文自读；
 * PRD（docs/PRD.md）由主智能体先前写出，同样是智能体自读，平台不搬运。</p>
 *
 * <p><b>失败自动静默重试有限次＝原地修（#221）</b>（同工作区不丢数据——重试续
 * 本片会话，已落盘成果保留；新尝试携带错误现场继续修，不重做已对的工作）：中间
 * 失败不出用户面事件；每片超限转终态失败即发 {@code run-failed} 收口事件（#56，
 * run 失败为唯一失败终态），由用户「继续生成」脏续兜底（断点续跑同路，不重头；
 * generated_at 不落位 = 出口口径仍在）。最后一片
 * 成功收口才落 {@code generated_at}（首次生成时点，单向置位——「确认下单」
 * 可见性口径）。</p>
 */
@Service
@Slf4j
public class GenerationAppService {

    /**
     * 任务级解说约定（#225 叙说密度放宽的拼装单点，任务 prompt 统一携带；执行体
     * systemPrompt 的协议条同向但更详——两处措辞独立维护）。
     */
    static final String NARRATION_CONVENTION =
            "过程解说只在关键节点（开工、重大转向、失败、收口）用一两句平实中文说明，"
                    + "不必每组动作都配解说。";

    /**
     * 阶段 0 prompt（先起服，#104 生成轨道首段；#113 收敛为「模板就位 + 起服 +
     * curl 确认」）：读 PRD 了解整体目标，工作区已内置基座工程（依赖预装），直接
     * 在基座上把应用以最小可运行形态跑上 8081（白底骨架页即可），收口即白底页——
     * 先于任何切片，解决「全程 503」的早可见；免选型、免初始化，总时长下压。
     */
    static final String STAGE0_RUN_PROMPT =
            "系统初始化（先起服）：请完整阅读工作区 docs/PRD.md（需求正本）了解整体目标。"
                    + "工作区已内置可运行的基座工程（TypeScript / Next.js / pnpm，依赖已预装），"
                    + "无需选型或初始化——直接在基座上把应用以最小可运行形态跑上 8081 端口"
                    + "（后台常驻，白底骨架页即可，暂不实现业务功能），收口前用 curl 确认 8081 可访问。"
                    + NARRATION_CONVENTION;

    /**
     * 阶段 0 工作消息头部标题（#118）：非切片（先起服的固定水平工序）——只出标题、
     * 无「（n/N）」进度（切片计划只含纵向切片，阶段 0 不在其列）。
     */
    static final String STAGE0_TITLE = "系统初始化";

    /**
     * 阶段 0 重试续作的本段任务描述（与首试 prompt 同口径的段叙事，重试拼装用）。
     */
    static final String STAGE0_RETRY_DESC = "先起服：应用以最小可运行形态跑上 8081";

    /** 阶段 0 收口叙事（收尾卡 summary；#223 存量对照的匹配键）。 */
    static final String STAGE0_CLOSING_SUMMARY = "起服了系统骨架";

    /** 切片收口叙事前缀（收尾卡 summary；#223 存量对照的匹配键）。 */
    static final String SLICE_CLOSING_PREFIX = "完成切片：";

    /** 切片收口叙事（收尾卡 summary 的拼装单点——落口与存量对照同键）。 */
    static String sliceClosingSummary(String slice) {
        return SLICE_CLOSING_PREFIX + slice;
    }

    /**
     * 重试续作 prompt（#104 分段口径；#221 原地修——携带错误现场）：同工作区不丢
     * 数据——已落盘成果保留、不重做已对的工作，针对上次错误在现有成果上继续修完
     * <b>本段</b>任务（阶段 0 / 某片），不越段——切片逐段由平台顺序派 run 决定，
     * 重试不替执行体跨到下一片（「做一点展示一点」的完整性优先）。segmentDesc 即
     * 本段任务描述（与首试 prompt 同口径）；errorScene = 上次尝试的错误现场
     * （尝试环捕获的异常事实，平台不加工）。
     */
    static String retryRunPrompt(String segmentDesc, String errorScene) {
        return "上一次尝试失败了（错误现场：" + errorScene + "）。工作区内已完成的成果"
                + "仍然有效——不要重做已对的工作。请先检查现状（代码、依赖、数据、8081 "
                + "端口服务是否在跑），针对该错误在现有成果上继续修复、完成本段任务"
                + "（" + segmentDesc + "），收口前确认 8081 端口服务在跑、curl 可访问。";
    }

    /**
     * 切片 prompt（#104 生成轨道逐段长出）：第 {@code index}/{@code total} 片，在
     * 现有系统上增量实现「用户能 X」这一纵向切片（前端→后端→落库端到端走通、用户
     * 可操作），系统其余部分保持可用。切片计划由主智能体产出（每片一句用户语言），
     * 平台只顺序执行、不解析自由文本（ADR 0009）。
     */
    static String sliceRunPrompt(int index, int total, String slice) {
        return "系统增量（切片 " + index + "/" + total + "）：请在现有系统上增量实现"
                + "这一纵向切片——「" + slice + "」（前端到后端、数据落库端到端走通，"
                + "用户可操作），保持系统其余部分可用，收口前确认 8081 端口服务在跑、"
                + "curl 可访问。" + NARRATION_CONVENTION;
    }

    /**
     * 交接摘要产出约定（#114 片间交接）：每片/阶段 0 收口前，执行体自产交接摘要
     * 作为最后一段话——平台取其收口终文（{@link CoderRunAttempts.RunResult#closingText}）
     * 落 .platform/ 并注入下一片 prompt。三要素（做了什么/关键文件/下一片须知）由
     * prompt 约定，平台不解析自由文本（不自造摘要机制，ADR 0012）。
     */
    static final String HANDOFF_PRODUCTION =
            "\n收口前，用你最后一段话输出本片交接摘要（供下一片执行体接手），包含三要素："
                    + "① 本片做了什么；② 关键文件（新增或修改的关键文件路径）；③ 下一片须知"
                    + "（下一片执行体需要知道的关键上下文、未完成事项或注意事项）。";

    /** 切片计划轨迹（#114 每片新会话后执行体自见全局）：全量有序清单，本片位置由切片任务自述。 */
    static String planTrajectory(BuildPlan plan) {
        StringBuilder trajectory = new StringBuilder("整体切片计划（按实现顺序）：");
        for (int i = 0; i < plan.slices().size(); i++) {
            trajectory.append('\n').append(i + 1).append(". ").append(plan.slices().get(i));
        }
        return trajectory.toString();
    }

    /** 前片交接摘要注入块（#114 片间交接）：下一片 prompt 直接携带前片终文。 */
    static String previousHandoffBlock(String previousHandoff) {
        return "上一片交接摘要（前片执行体所留，供你接手）：\n" + previousHandoff;
    }

    /**
     * 片级上下文（#114 每片新会话）：首试与重试共用——重试可能落在空会话（首试
     * converse 在引擎建会话前就失败，#84 静默重试恰覆盖此路径），故重试不能依赖
     * 「会话历史自持前片摘要」，须自足携带同一份上下文。拼装 = PRD 引用 + 切片
     * 计划轨迹 + 前片交接摘要 + 交接摘要产出约定。
     */
    static String segmentContext(BuildPlan plan, String previousHandoff) {
        StringBuilder context = new StringBuilder("\n\n")
                .append("先重读工作区 docs/PRD.md（需求正本）了解整体目标。")
                .append("\n").append(planTrajectory(plan));
        if (previousHandoff != null) {
            context.append("\n\n").append(previousHandoffBlock(previousHandoff));
        }
        return context.append(HANDOFF_PRODUCTION).toString();
    }

    /** 阶段 0 首试 prompt（#114）：起服任务 + 片级上下文。 */
    static String stage0Prompt(BuildPlan plan) {
        return STAGE0_RUN_PROMPT + segmentContext(plan, null);
    }

    /** 切片首试 prompt（#114）：切片任务 + 片级上下文。 */
    static String slicePrompt(BuildPlan plan, int index, String previousHandoff) {
        String slice = plan.slices().get(index);
        return sliceRunPrompt(index + 1, plan.slices().size(), slice)
                + segmentContext(plan, previousHandoff);
    }

    /** 生成轨重试续作 prompt（#114；#221 重试携带错误现场）：续作任务 + 片级上下文（与首试同上下文）。 */
    static String generationRetryPrompt(BuildPlan plan, String segmentDesc, String previousHandoff,
            String errorScene) {
        return retryRunPrompt(segmentDesc, errorScene) + segmentContext(plan, previousHandoff);
    }

    /** 生成轨会话寻址（#114 每片新会话）：段号 0 = 阶段 0，1..N = 切片，重试续本段会话。 */
    static String sliceSession(Long projectId, int segment) {
        return CoderRunAttempts.SESSION_PREFIX + projectId + "-slice-" + segment;
    }

    /**
     * 续跑段会话寻址（#221 脏续新会话）：断点段（失败/中断片）续跑起新会话——不续
     * 失败旧会话（重试耗尽的旧会话带着失败循环历史，且引擎会话状态不保证跨进程在），
     * 现状盘点即起手交接（与片间交接同构）；会话内重试照旧续本会话。runId 后缀同
     * 修正轨 {@code fix-{runId}} 惯例——每场续跑会话唯一。
     */
    static String resumeSession(Long projectId, int segment, String runId) {
        return sliceSession(projectId, segment) + "-" + runId;
    }

    /** 交接摘要落盘路径（#114 .platform/ 平台产物目录，不进用户 git 成版）：段号对应切片序。 */
    static String handoffFile(int segment) {
        return WorkspaceLayout.PLATFORM_DIR + "/slice-handoff-" + segment + ".md";
    }

    /**
     * 前片交接摘要读回（#221 续跑起手）：{@code .platform/slice-handoff-{n}.md} 是
     * 交接摘要的落盘正本（#114 片间交接写下的透明面）——进程重启后内存终文不在，
     * 续跑从工作区读回断点前片的交接作「中断前摘要」。读不回（未落盘/文件缺失）
     * 降级 null：执行体重读 PRD 与计划轨迹自足（同重试落空会话的口径），不阻断续跑。
     */
    private String readSliceHandoff(Project project, int segment) {
        try {
            ExecResultResponse result = workspaceLifecycleAppService.exec(
                    Long.toString(project.getWorkspaceId()),
                    new WorkspaceExecCommand("cat '" + WorkspaceLayout.absolute(handoffFile(segment)) + "'"));
            if (result.exitCode() == 0 && !result.stdout().isBlank()) {
                return result.stdout().strip();
            }
            log.warn("[generate] 项目 {} 片 {} 交接摘要读回缺失（降级空交接，续跑不断流）：exitCode={}",
                    project.getId(), segment, result.exitCode());
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 片 {} 交接摘要读回失败（降级空交接，续跑不断流）：{}",
                    project.getId(), segment, e.toString());
        }
        return null;
    }

    /**
     * 续跑现状盘点块（#221 全场景脏续的起手交接，机制与片间交接同构——平台拼事实、
     * 不产自由文本）：续跑断点段首试/重试 prompt 前置——已收口进度（轨道表事实：
     * 阶段 0 + 逐片清单）、中断原因（失败 = 重试耗尽〔携最近错误现场〕/ 中断 =
     * 未及收口）、脏续纪律（已收口成果不重做、已对的工作保留）。工作区现状的检查
     * 指引与「中断前摘要」（前片交接）由任务 prompt 与 {@link #segmentContext}
     * 携带，同片间交接口径。
     */
    static String resumeInventoryBlock(int startOrd, List<GenerationSegment> segments,
            String failureScene) {
        int totalSlices = segments.size() - 1;
        StringBuilder progress = new StringBuilder();
        GenerationSegment target = null;
        for (GenerationSegment segment : segments) {
            if (segment.getOrd() >= startOrd) {
                target = segment; // 断点段（失败/中断片）——中断原因的叙事源
                break;
            }
            if (progress.length() > 0) {
                progress.append("、");
            }
            progress.append(segment.getOrd() == 0
                    ? STAGE0_TITLE
                    : "切片 " + segment.getOrd() + "/" + totalSlices + "「" + segment.getDescription() + "」");
        }
        String reason = target != null && target.getStatus() == GenerationSegmentStatus.FAILED
                ? "上一次运行在本段自动重试耗尽后失败"
                        + (failureScene == null ? "" : "（最近错误现场：" + failureScene + "）")
                : "上一次运行在本段中断（未及收口）";
        return "【续跑现状盘点】本次生成是从中断处接续，不是从头重来：\n"
                + (progress.length() > 0
                        ? "- 已收口进度：" + progress + "——这些段的成果都在工作区，不要重做。\n"
                        : "- 已收口进度：尚无——前次运行未收口任何段，本次从第一段接续"
                                + "（已做的工作若有仍在工作区）。\n")
                + "- 中断原因：" + reason + "。\n"
                + "- 接手本段先检查工作区现状（代码、依赖、数据、8081 端口服务是否在跑），"
                + "保留已对的工作，从中断处继续完成本段任务。\n\n";
    }

    /**
     * AGENTS.md 平台约定正文（工作区布局资产，#22 就位）：工作区物理约定的正本
     * ——harness 工作区上下文自动注入 run 执行体，也是后续迭代 run / 引擎资产的
     * 演进载体。内容平台所有（无用户可控片段），幂等覆写。
     */
    static final String AGENTS_MD_CONTENT = """
            # 工作区平台约定

            本工作区是平台的单容器沙箱：Node.js 运行时与 PostgreSQL、Redis 同容器，/workspace 是唯一持久卷——容器可随时销毁重建，卷内数据不丢、卷外皆可弃。

            ## 基座技术栈

            工作区开箱即是一个可运行的基座工程（镜像内置、依赖已预装），技术栈固定：TypeScript / Next.js / React 19 / pnpm / shadcn（Tailwind 4）/ PostgreSQL / Redis。在基座上增量长出系统——不换栈、不重选型、不重新搭骨架；基座覆盖不了的依赖才在工作区现场安装（pnpm add），不要引入替代性框架。

            - 应用代码放工作区根目录；docs/ 放文档（docs/PRD.md 是需求正本，只读）。
            - external/ 是外部仓库资料目录：PRD 引用外部仓库时，把仓库浅克隆进 external/（git clone --depth 1 <仓库地址> external/<仓库名>），只读参考其 README/文档/源码结构、不合并进系统；此目录不进交付源码包、不在文件树显示、不进版本正本（随封存保全，非缓存）。
            - 数据库连接串读 .env 的 DATABASE_URL（平台生成、唯一注入通道，勿手改）；需要缓存用 .env 的 REDIS_URL。
            - 应用自用的文件数据必须落 data/ 目录（卷内才持久）。
            - 应用服务必须监听 0.0.0.0:8081——平台预览从该端口取流量。
            - 应用服务一开工就跑起来：先把可运行形态（最小骨架或空壳页面即可）跑上 8081 后台常驻，此后增量长出页面与功能、随改随生效——不要写完全部代码才起服务。
            - 基座依赖已在镜像预装；新增依赖才在工作区现场安装（node_modules 属可重建物，打包交付时排除）。
            - .platform/ 是平台产物目录，不要写入；本文件（AGENTS.md）由平台维护，不要修改。
            - 每轮生成或修正收尾前，确认 8081 端口的服务在后台常驻运行——预览呈现的是活的服务。
            """;

    /**
     * 收口判据核验探针（#35）：converse 无异常不构成成功——run 执行体可能道歉式
     * 放弃或被 maxIters 掐断而照常返回。8081 可达才算收口（与 EXECUTOR systemPrompt
     * 的收口判据对齐）。{@code -s} 静默、{@code -o /dev/null} 弃正文，exitCode 0 =
     * 端口有 HTTP 应答（连接拒绝即非 0）；{@code --max-time} 上限（#183）——收口
     * 线程不被无响应对端挂死。
     */
    static final String CLOSING_PROBE = "curl -s --max-time 2 -o /dev/null http://localhost:8081";

    private final ProjectRepository projectRepository;
    private final AgentSessionExecutor sessionExecutor;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;
    private final CoderRunAttempts coderRunAttempts;
    private final AgentEventBridge eventBridge;
    private final EventsAppService eventsAppService;
    private final CodingRunTrack codingRunTrack;
    private final GenerationSegmentRepository generationSegments;
    private final TransactionTemplate transactionTemplate;
    private final MainAgentAppService mainAgentAppService;
    private final ConversationHistoryAppService conversationHistory;

    /**
     * 计划补产账（#220 计划缺失兜底的防烧护栏，projectId → 已补产过的 PRD 版本锚）：
     * 同一 PRD 版本只补产一次——补产轮收口仍无计划即止（用户重提意见即兜底；PRD
     * 演进换锚可再补产）。进程内账，重启即清（重启后至多多补一轮）；计划落库即清账
     * （补产已兑现）。
     */
    private final Map<Long, LocalDateTime> planProductionRequests = new ConcurrentHashMap<>();

    /**
     * 终态失败现场账（#221 续跑现状盘点的「中断原因」旁证，projectId → 最近一次
     * 终态失败末次尝试的错误现场）：run-failed 时记下，同进程内「继续生成」的现状
     * 盘点携带；跨进程丢失即降级为不携现场的失败叙事（轨道表状态才是事实源，本账
     * 只是旁证）。续跑收口成功即清，再失败即覆写。
     */
    private final Map<Long, String> terminalFailureScenes = new ConcurrentHashMap<>();

    /**
     * @param mainAgentAppService 计划补产口（{@link MainAgentAppService#requestBuildPlan}）。
     *        {@code @Lazy} 破主智能体编排与本服务的构造环：MainAgentAppService 收口派发
     *        依赖本服务（既有方向），本服务计划缺失时重派主智能体补产（#220 新增方向）
     *        ——环只此一处、以懒代理收口，行为仍是进程内直调。
     */
    public GenerationAppService(ProjectRepository projectRepository,
            AgentSessionExecutor sessionExecutor,
            WorkspaceLifecycleAppService workspaceLifecycleAppService,
            CoderRunAttempts coderRunAttempts, AgentEventBridge eventBridge,
            EventsAppService eventsAppService, CodingRunTrack codingRunTrack,
            GenerationSegmentRepository generationSegments, TransactionTemplate transactionTemplate,
            @Lazy MainAgentAppService mainAgentAppService,
            ConversationHistoryAppService conversationHistory) {
        this.projectRepository = projectRepository;
        this.sessionExecutor = sessionExecutor;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
        this.coderRunAttempts = coderRunAttempts;
        this.eventBridge = eventBridge;
        this.eventsAppService = eventsAppService;
        this.codingRunTrack = codingRunTrack;
        this.generationSegments = generationSegments;
        this.transactionTemplate = transactionTemplate;
        this.mainAgentAppService = mainAgentAppService;
        this.conversationHistory = conversationHistory;
    }

    /**
     * 触发生成的显式入口（#101 生成无门后为中断「继续生成」兜底——POST /generate
     * 端点与收口自动派发共用同一编排；#221 断点续跑：轨道表已有收口/失败片即从
     * 断点脏续、已收口片不重做）：守卫 → AGENTS.md 资产就位 → 异步提交编码
     * run（首试 runId 随响应回，过程事件经 SSE；失败重试与超限兜底在异步轨道内）。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 项目已归档；
     *                              PRJ_017 已生成或生成在途（重复发起）；
     *                              PRJ_018 PRD 从未产出（编码 run 的任务就是读
     *                              PRD 实现，无 PRD 起跑只会空烧重试）
     */
    public GenerationRun startGeneration(Long projectId) {
        Project project = requireGeneratableProject(projectId);
        GenerationRun run = dispatchGeneration(project, /* rejectInFlight= */ true, /* plan= */ null);
        if (run == null) {
            // 计划缺失且该 PRD 版本已补产过（补产账拦截）：同步 409——重复触发口径
            // 同在途拒绝（收口自动路径的 null 静默跳过不经过本入口）
            throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
        }
        return run;
    }

    /**
     * 意见轮收口自动派首次生成（#101 生成无门自动发起）：主智能体产出 PRD 后，
     * 平台在意见轮收口处观测「未生成 && 已产出 PRD」即自动派首次生成 run，用户
     * 无需确认或点击。守卫沿用 {@link #startGeneration}（存在 / 未归档 / 未生成过
     * / PRD 已产出——{@link #requireGeneratableProject} 单点），差异只在在途口径：
     * 生成进行中静默跳过（不派不报错——此时用户新意见照旧随对话收口，不触发
     * 重复生成）；按钮路径在途拒绝 PRJ_017。run-failed 后项目仍「未生成」，用户
     * 重提一句即经本入口再触发，不自动重试（防空烧 token）。
     *
     * @param plan 切片计划交接物（saveBuildPlan 事实终值；null = 本轮未产出——沿用
     *             表内现行计划〔PRD 锚一致才有效〕，仍无则计划缺失走补产兜底）
     * @return 派发的 run 标识；在途（生成进行中）或计划缺失不再派返回 null——调用方
     *         据此区分「已派」与「未派」，不误报派发事实
     * @throws ApplicationException 守卫组同 {@link #startGeneration}（收口观测处
     *                              已判定过未归档 / 未生成 / PRD 已产出，此处守卫
     *                              兜其余竞态调用面）
     */
    public GenerationRun dispatchGenerationOnTurnClose(Long projectId, BuildPlan plan) {
        Project project = requireGeneratableProject(projectId);
        return dispatchGeneration(project, /* rejectInFlight= */ false, plan);
    }

    /**
     * 首次生成派发（显式与收口自动两入口共用）：切片计划解析（#220 计划跟 PRD 版本
     * 走）→ AGENTS.md 资产就位 → 计划落轨道表 → 异步提交编码 run。在途口径按调用方
     * 分岔——按钮路径拒绝 PRJ_017、收口自动路径静默跳过（返回 null 即「未派」，调用
     * 方不关心）。资产就位/计划落库失败如实上抛并释放在途标记（环境故障口径，生成
     * 不起跑）。计划缺失不派 run（不造假计划）——重派主智能体补产。
     */
    private GenerationRun dispatchGeneration(Project project, boolean rejectInFlight, BuildPlan plan) {
        Long projectId = project.getId();
        if (!codingRunTrack.begin(projectId)) {
            if (rejectInFlight) {
                throw new ApplicationException(ProjectMessage.GENERATION_ALREADY_REQUESTED);
            }
            log.info("[generate] 项目 {} 生成在途，收口自动派发静默跳过", projectId);
            return null;
        }
        // 切片计划解析（#220 计划跟 PRD 版本走）：显式交接物优先（即计划重产——PRD
        // 演进随修订轮重交）；无交接物沿用表内现行计划（仅当 PRD 版本锚一致——PRD
        // 未演进）。计划缺失：释放在途占位后走补产兜底（占位不跨补产轮——补产轮
        // 收口再派时可重新占位，不自锁）
        BuildPlan resolved = resolvePlan(project, plan);
        if (resolved == null) {
            codingRunTrack.end(projectId);
            return dispatchPlanProduction(project);
        }
        try {
            writeConventionsAsset(workspaceLifecycleAppService, project);
            // 交接物在位 = 计划（重）产——整组替换落表；沿用表内计划的再派（REST 兜底、
            // PRD 未演进）不重落——片状态是续跑事实，重落即清零
            if (plan != null) {
                recordPlan(project, resolved);
            }
        } catch (RuntimeException e) {
            codingRunTrack.end(projectId);
            throw e;
        }

        String firstRunId = EventsAppService.newRunId();
        sessionExecutor.submit(CoderRunAttempts.SESSION_PREFIX + projectId, () -> {
            try {
                runGenerationTrack(project, firstRunId, resolved);
            }
            finally {
                codingRunTrack.end(projectId);
            }
        });
        return new GenerationRun(firstRunId);
    }

    /**
     * 切片计划解析（#220 计划生命周期跟 PRD 版本走）：表内现行计划还原为
     * {@link BuildPlan}——片行 prd 锚与项目 {@code prd_produced_at} 一致（PRD 未
     * 演进）才有效；表空或锚不一致（PRD 已修订，旧计划是过期结构）返回 null
     * （计划缺失/过期）。锚取自库读回值（与比对值同为库回读形，等值比较稳定）。
     */
    private BuildPlan resolvePlan(Project project, BuildPlan handed) {
        if (handed != null) {
            return handed;
        }
        List<GenerationSegment> segments =
                generationSegments.findByProjectIdOrderByOrdAsc(project.getId());
        if (!GenerationSegment.planMatchesPrd(segments, project.getPrdProducedAt())) {
            return null;
        }
        return new BuildPlan(segments.stream().skip(1)
                .map(GenerationSegment::getDescription).toList());
    }

    /**
     * 切片计划落轨道表（#220）：整组替换——清现行片集后按「阶段 0 + 切片计划」插入
     * 待跑片行，PRD 版本锚取项目当下 {@code prd_produced_at}（此后 PRD 演进即锚
     * 不一致、计划不沿用）。短事务原子替换（旧片不残留；删后先冲刷再插——JPA 同
     * 事务冲刷序先插后删，不冲刷会撞 (project_id, ord) 唯一键）；失败如实上抛（无
     * 轨道事实不空跑生成）。落库成功清补产账（补产已兑现）。
     *
     * <p><b>存量首录对照标已完片（#223）</b>：表内无片行 = 往次生成早于轨道表——
     * 补产计划落库时对照项目收尾卡（已收口成果，git 收口 commit 经 Run-Id trailer
     * 锚定同一收尾卡）把匹配片直接置已收口（证据级精确匹配：片收口叙事与收尾卡
     * summary 逐字相等——补产 prompt 要求已完片照录原文，措辞漂移即对照不上、按
     * 待跑重做，降级方向安全：多跑不漏做）；表内有旧片行（PRD 已演进换锚重产）
     * 不适用——旧片整组替换重置、从头来是既定口径。</p>
     */
    private void recordPlan(Project project, BuildPlan plan) {
        Long projectId = project.getId();
        LocalDateTime prdAnchor = project.getPrdProducedAt();
        Map<String, String> legacyClosed = legacyClosedSummaries(projectId);
        transactionTemplate.executeWithoutResult(status -> {
            generationSegments.deleteByProjectId(projectId);
            generationSegments.flush();
            GenerationSegment stage0 = GenerationSegment.pending(projectId, 0, STAGE0_TITLE, prdAnchor);
            markLegacyClosed(stage0, legacyClosed);
            generationSegments.save(stage0);
            for (int index = 0; index < plan.slices().size(); index++) {
                GenerationSegment segment = GenerationSegment.pending(projectId, index + 1,
                        plan.slices().get(index), prdAnchor);
                markLegacyClosed(segment, legacyClosed);
                generationSegments.save(segment);
            }
        });
        if (!legacyClosed.isEmpty()) {
            log.info("[generate] 项目 {} 存量计划首录：对照收尾卡标已完片后只补缺口", projectId);
        }
        planProductionRequests.remove(projectId);
    }

    /**
     * 存量已完片对照标已收口（#223）：片收口叙事（阶段 0 固定句 / 完成切片句）与
     * 收尾卡 summary 逐字相等即置已收口，run 锚 = 往次收口的用户面 run（收尾卡锚）。
     */
    private static void markLegacyClosed(GenerationSegment segment, Map<String, String> legacyClosed) {
        String summary = segment.getOrd() == 0
                ? STAGE0_CLOSING_SUMMARY : sliceClosingSummary(segment.getDescription());
        String runId = legacyClosed.get(summary);
        if (runId != null) {
            segment.close(runId);
        }
    }

    /**
     * 存量已收口成果清单（#223 对照源，补产携载与落库标定的共用读口）：表内无片行
     * = 往次生成早于轨道表——取项目收尾卡叙事（summary → 收口 run 锚）；表内有片行
     * （PRD 已演进换锚重产）恒空——重产从头来是既定口径，不适用存量对照。
     */
    private Map<String, String> legacyClosedSummaries(Long projectId) {
        if (!generationSegments.findByProjectIdOrderByOrdAsc(projectId).isEmpty()) {
            return Map.of();
        }
        return conversationHistory.closedGenerationSummaries(projectId);
    }

    /**
     * 计划缺失兜底（#220，替位已删的 minimalFallback）：重派主智能体按 PRD 补产
     * 切片计划——补产轮（main 会话、不记用户发言）收口即经既有链必达自动再派生成。
     * 同一 PRD 版本只补产一次（{@link #planProductionRequests} 防烧护栏）：已补产过
     * 即静默不派（返回 null），用户重提意见即兜底；PRD 演进换锚可再补产。
     *
     * <p><b>存量对照（#223）</b>：表内无片行 = 往次生成早于轨道表（无持久化计划但
     * 已有收口成果）——补产 prompt 携已收口成果清单（要求已完片照录原文），补产
     * 计划落库时 {@link #recordPlan} 据此标已完片、只补缺口；表内有旧片行（PRD 已
     * 演进换锚）不携——重产从头来是既定口径。</p>
     */
    private GenerationRun dispatchPlanProduction(Project project) {
        Long projectId = project.getId();
        if (project.getPrdProducedAt().equals(planProductionRequests.get(projectId))) {
            log.info("[generate] 项目 {} 计划缺失且该 PRD 版本已补产过，不再补产（用户重提意见即兜底）",
                    projectId);
            return null;
        }
        // 记账先于补产提交（补产轮收口的再派要靠它止住——同步执行器下后置记账会
        // 递归失控）；补产轮起跑被守卫拒（挂起问答等）则清账——该版本仍可再补
        planProductionRequests.put(projectId, project.getPrdProducedAt());
        List<String> legacyClosedOutcomes =
                List.copyOf(legacyClosedSummaries(projectId).keySet());
        log.info("[generate] 项目 {} 计划缺失，重派主智能体按 PRD 补产切片计划（存量对照清单 {} 项）",
                projectId, legacyClosedOutcomes.size());
        try {
            return new GenerationRun(
                    mainAgentAppService.requestBuildPlan(projectId, legacyClosedOutcomes).runId());
        }
        catch (RuntimeException e) {
            planProductionRequests.remove(projectId);
            throw e;
        }
    }

    /**
     * 生成交接物的切片计划探针（#220 轨道表读回）：表内现行计划（PRD 锚一致才
     * 有效；无/过期 = null）。测试断言交接物落定的读口，也是「重启后计划仍可查」
     * 的事实面——计划唯一事实源是表，进程内无副本。
     */
    BuildPlan planOf(Long projectId) {
        return projectRepository.findById(projectId)
                .map(project -> resolvePlan(project, null)).orElse(null);
    }

    /**
     * 断点推导探针（#220 断点 = 表中最深收口片；「继续生成」续跑〔#221〕的消费面）：
     * -1 = 无收口片。锚门自持——片行 PRD 锚与项目不一致（PRD 已修订、计划待重产）
     * 时旧收口片不算断点（断点属现行计划，过期计划的进度不是新断点事实）。
     */
    int deepestClosedSegmentOf(Long projectId) {
        List<GenerationSegment> segments =
                generationSegments.findByProjectIdOrderByOrdAsc(projectId);
        if (segments.isEmpty()) {
            return -1;
        }
        return projectRepository.findById(projectId)
                .filter(project -> GenerationSegment.planMatchesPrd(segments, project.getPrdProducedAt()))
                .map(project -> segments.stream()
                        .filter(segment -> segment.getStatus() == GenerationSegmentStatus.CLOSED)
                        .mapToInt(GenerationSegment::getOrd)
                        .max().orElse(-1))
                .orElse(-1);
    }

    /** 一场生成的运行标识 = 用户面 run 身份（前端挂智能体事件 ?runId= 的锚；#84 静默重试——重试不换新锚，全程同值）。 */
    public record GenerationRun(String runId) {
    }

    // ---------- 内部 ----------

    /**
     * 生成轨道（异步轨道内，#104 先起服 + 纵向切片逐段；#221 断点续跑；#223 存量
     * 对照续跑）：断点以轨道表为准（表中最深收口片；git 收口 commit 为旁证）——
     * <b>续跑跳过已收口片、只重跑失败/中断片</b>（首个未收口段起），起手带现状盘点
     * （{@link #resumeInventoryBlock}）从新会话脏续（{@link #resumeSession}），前片
     * 交接摘要取 {@code .platform/} 落盘件（{@link #readSliceHandoff}——进程重启后
     * 内存终文不在的事实源）。已收口片逐段跳过（任意分布正确——存量对照标的已完
     * 片理论上非前缀，#223）。全场景同一口径：重试耗尽 run-failed 后「继续生成」
     * 与中断续跑同路（不重头）；PRD 演进则计划重产整组替换、从头再来（推倒重来是
     * 显式选择，走对话区改 PRD）。片全收口（末片收口而 {@code generated_at} 落位
     * 失败的边角、存量对照标满的空缺口）直接补落位。
     *
     * <p>每片收口判据 = 8081 可达（{@link #requireReachable} 核验）+ 成版 +
     * run-finish（收口扩载，端到端可操作由片内 self-test 兜）；最后一片收口才落
     * {@code generated_at}。失败语义：某片超限转终态即发 {@code run-failed} 收口
     * （锚该片 runId）、不自动跳下一片——「做一点展示一点」的完整性优先。每片
     * 收口/失败状态随片落轨道表（#220）。知识命中前置注入只在阶段 0 的 run（一次
     * 切入一次注入，#114——续跑断点在阶段 0 时同注入，断点在切片则不注入）。</p>
     */
    private void runGenerationTrack(Project project, String firstRunId, BuildPlan plan) {
        Long projectId = project.getId();
        List<String> slices = plan.slices();
        List<GenerationSegment> segments =
                generationSegments.findByProjectIdOrderByOrdAsc(projectId);
        // 待跑段集与已收口段集（#221 断点续跑；#223 存量对照后已收口段可非前缀分布
        // ——补产照录已完片在前只是提示词约定，平台不强求，逐段跳过已收口片对任意
        // 分布正确）；断点段 = 首个未收口段
        Set<Integer> closedOrds = segments.stream()
                .filter(segment -> segment.getStatus() == GenerationSegmentStatus.CLOSED)
                .map(GenerationSegment::getOrd).collect(Collectors.toUnmodifiableSet());
        List<Integer> openOrds = segments.stream()
                .filter(segment -> segment.getStatus() != GenerationSegmentStatus.CLOSED)
                .map(GenerationSegment::getOrd).toList();
        boolean resumed = segments.stream()
                .anyMatch(segment -> segment.getStatus() != GenerationSegmentStatus.PENDING);
        if (openOrds.isEmpty()) {
            // 片全收口即生成完成（末片收口而 generated_at 落位失败的补落 + 存量对照
            // 标满的空缺口边角，#223）
            terminalFailureScenes.remove(projectId);
            markGenerated(projectId);
            return;
        }
        int startOrd = openOrds.get(0);
        // 续跑起手交接（#221）：断点段（失败/中断片）prompt 前置现状盘点；前片交接
        // 摘要取断点前最深收口片的 .platform/ 落盘件（内存终文随进程消失，落盘件是
        // 续跑事实源）
        String resumePrefix = "";
        String previousHandoff = null;
        if (resumed) {
            resumePrefix = resumeInventoryBlock(startOrd, segments,
                    terminalFailureScenes.get(projectId));
            int handoffSource = closedOrds.stream()
                    .filter(ord -> ord < startOrd).max(Integer::compare).orElse(-1);
            if (handoffSource >= 0) {
                previousHandoff = readSliceHandoff(project, handoffSource);
            }
        }
        // 轨道逐段（阶段 0 起跑时 ord=0）：断点段 = 本轨首 run（首试 runId 即用户面
        // 首 run 身份），此后逐片新 runId；某片失败不自动跳下一片（完整性优先）。
        // 末段 = 最深待跑段（其后段已收口——存量对照的分布边角，全收口即落位）
        int lastOrd = openOrds.get(openOrds.size() - 1);
        for (int index = startOrd; index <= slices.size(); index++) {
            int ord = index; // 片段号（轨道表 ord 口径；lambda 捕获需实际最终）
            if (closedOrds.contains(ord)) {
                continue; // 已收口片不重做（#221 断点续跑 / #223 存量对照）
            }
            boolean stage0 = ord == 0;
            String slice = stage0 ? null : slices.get(ord - 1);
            boolean last = ord == lastOrd;
            boolean resumeTarget = ord == startOrd;
            String runId = resumeTarget ? firstRunId : EventsAppService.newRunId();
            String prefix = resumeTarget ? resumePrefix : "";
            String handoff = stage0 ? null : previousHandoff;
            String segmentDesc = stage0 ? STAGE0_RETRY_DESC : "实现切片「" + slice + "」";
            CoderRunAttempts.RunResult result = coderRunAttempts.run(project, runId,
                    resumed && resumeTarget
                            ? resumeSession(projectId, ord, runId)
                            : sliceSession(projectId, ord),
                    new CoderRunAttempts.Prompts(
                            prefix + (stage0 ? stage0Prompt(plan) : slicePrompt(plan, ord - 1, previousHandoff)),
                            errorScene -> prefix + generationRetryPrompt(plan, segmentDesc, handoff, errorScene)),
                    attemptRunId -> closeGenerationStage(project, ord, last, runId,
                            stage0 ? STAGE0_CLOSING_SUMMARY : sliceClosingSummary(slice)),
                    CoderRunAttempts.GENERATE_LABEL, stage0,
                    stage0 ? RunHeading.titled(STAGE0_TITLE)
                            : RunHeading.slice(slice, ord, slices.size()));
            if (!result.succeeded()) {
                failSegment(projectId, ord, runId);
                if (result.lastError() != null) {
                    terminalFailureScenes.put(projectId, result.lastError());
                }
                eventBridge.emitRunFailed(projectId, project.getOwnerAccountId(), runId);
                return;
            }
            // 前片交接摘要（#114 片间交接）：本片收口终文即交接，落 .platform/ 并注入下一片
            previousHandoff = result.closingText();
            placeSliceHandoff(project, ord, previousHandoff);
        }
        terminalFailureScenes.remove(projectId);
    }

    /**
     * 交接摘要落盘（#114 片间交接；#221 兼续跑事实源）：前片收口终文写工作区
     * .platform/slice-handoff-{n}.md（平台产物目录，不进用户 git 成版，与 #107 同向）。
     * 正文经 base64 传参防 shell 元字符（执行体终文非平台常量）；失败只记日志不断流
     * ——片间交接的注入走内存终文（{@link CoderRunAttempts.RunResult#closingText}），
     * 续跑读回（{@link #readSliceHandoff}）读不回即降级空交接，落盘不承担正确性。
     */
    private void placeSliceHandoff(Project project, int segment, String content) {
        try {
            String encoded = Base64.getEncoder()
                    .encodeToString(content.getBytes(StandardCharsets.UTF_8));
            String command = "printf '%s' '" + encoded + "' | base64 -d > '"
                    + WorkspaceLayout.absolute(handoffFile(segment)) + "'";
            ExecResultResponse result = workspaceLifecycleAppService.exec(
                    Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(command));
            if (result.exitCode() != 0) {
                log.warn("[generate] 项目 {} 交接摘要落盘失败（透明面，不断流）：{}",
                        project.getId(), result.stderr());
            }
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 交接摘要落盘失败（透明面，不断流）：{}",
                    project.getId(), e.getMessage());
        }
    }

    /**
     * 单段收口判据（#104 生成轨道的平台侧检查点）：8081 可达才收口——converse 无异常
     * 不构成成功（智能体可能道歉式放弃 / 被 maxIters 掐断）。核验不过抛异常，被共用件
     * 尝试环当作该次尝试失败（走重试/终态路径）；「端到端可操作」由片内 self-test 兜
     * （收口扩载 selfTest 统计），平台侧不新增探针。收口即片状态落表（#220，ord 段号
     * 口径一致）；最后一片收口才落 {@code generated_at}（口径不变——阶段 0 / 中间片
     * 不落位 = 拆片不漂移「确认下单」可见性）。
     */
    private CoderRunAttempts.ClosingJudgment closeGenerationStage(Project project, int ord,
            boolean markGenerated, String runId, String summary) {
        requireReachable(project);
        emitPreviewReady(project);
        closeSegment(project.getId(), ord, runId);
        if (markGenerated) {
            markGenerated(project.getId());
        }
        // 生成轮判定（#88 判定行）：PRD 未动（生成不改 PRD——正本由主智能体先行写出）、
        // 系统产出（8081 探活收口事实）；summary = 本段叙事（阶段 0 / 切片完成）
        return CoderRunAttempts.ClosingJudgment.generation(summary);
    }

    /** 片收口状态落表（#220）：真收口（8081 探活过）即置已收口 + 用户面 run 锚。 */
    private void closeSegment(Long projectId, int ord, String runId) {
        recordSegmentStatus(projectId, ord, segment -> segment.close(runId));
    }

    /** 片失败状态落表（#220）：尝试环超限终态即置失败（用户面 run 锚）。 */
    private void failSegment(Long projectId, int ord, String runId) {
        recordSegmentStatus(projectId, ord, segment -> segment.fail(runId));
    }

    /**
     * 片状态落位（#220）：轨道表是断点推导与续跑的事实源，但落表失败不反噬 run——
     * 只记日志（收口事实仍在收尾卡；缺行 = 续跑多跑一片，安全向，同
     * {@link #markGenerated} 的尽力而为口径）。幂等覆写（重派后再收口/失败即刷新
     * ——状态是「最近一次尝试的结局」）。
     */
    private void recordSegmentStatus(Long projectId, int ord, Consumer<GenerationSegment> transition) {
        try {
            generationSegments.findByProjectIdAndOrd(projectId, ord).ifPresent(segment -> {
                transition.accept(segment);
                generationSegments.save(segment);
            });
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} 片 {} 状态落表失败（不反噬 run）：{}", projectId, ord,
                    e.toString());
        }
    }

    /** 8081 可达核验（#35 收口判据探针）：不可达即抛异常（驱动尝试环重试/终态）。 */
    private void requireReachable(Project project) {
        ExecResultResponse result = workspaceLifecycleAppService.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(CLOSING_PROBE));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("8081 不可达（curl 退出码 " + result.exitCode() + "）");
        }
    }

    /**
     * 切片收口推 URL（#105 URL 事件驱动推送）：8081 已在容器内探活通过（执行体已起服），
     * 此处经 exposePreview 取平台侧可访问的预览 URL 并推 {@code preview-ready}（载荷
     * projectId + url）——前端 bridge 消费写预览查询缓存，免 3s 轮询拿 URL。整个推送
     * 是「让 UI 活」的面、不承担正确性：URL 取不到或通知发射失败只记日志不断流——
     * 收口判据仍只有容器内 8081 探活（#104），URL 推送失败不反噬 run；前端兜底靠
     * run-finish 失效重拉 REST preview 补 URL。
     */
    private void emitPreviewReady(Project project) {
        try {
            URI url = workspaceLifecycleAppService.exposePreview(
                    Long.toString(project.getWorkspaceId()));
            eventsAppService.publishNotification(ProjectEventTypes.PREVIEW_READY, Map.of(
                    ProjectEventTypes.PROJECT_ID_FIELD, project.getId().toString(),
                    ProjectEventTypes.URL_FIELD, url.toString(),
                    EventsAppService.OWNER_FIELD, EventsAppService.ownerPayload(project.getOwnerAccountId())));
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} preview-ready 推送失败（UI 面，不断流）：{}",
                    project.getId(), e.getMessage());
        }
    }

    /** 首次生成时点落位：重载置位（单向），失败记日志不炸异步轨道（run 已成功）。 */
    private void markGenerated(Long projectId) {
        try {
            projectRepository.findById(projectId).ifPresentOrElse(project -> {
                project.markGenerated();
                projectRepository.save(project);
            }, () -> log.warn("[generate] 项目 {} 生成成功但记录已不存在（generated_at 无处落）",
                    projectId));
        }
        catch (RuntimeException e) {
            log.warn("[generate] 项目 {} generated_at 落位失败：{}", projectId, e.toString());
        }
    }

    /**
     * AGENTS.md 平台约定写入命令（幂等覆写，生成与更新 run 起手共用）：heredoc
     * 单引号定界不做展开，正文为平台常量（无用户可控片段、无单引号）。
     */
    static String agentsMdWriteCommand() {
        return "cat > '" + WorkspaceLayout.absolute(WorkspaceLayout.AGENTS_MD)
                + "' <<'PLATFORM_EOF'\n" + AGENTS_MD_CONTENT + "\nPLATFORM_EOF";
    }

    /**
     * 工作区布局资产就位（生成与更新 run 起手共用，#214 幂等覆写刷新既有工作区）：
     * AGENTS.md 平台约定写入。退出码非 0 即写入失败（环境故障口径如实上抛，run
     * 不起跑）。
     */
    static void writeConventionsAsset(WorkspaceLifecycleAppService workspace, Project project) {
        ExecResultResponse result = workspace.exec(
                Long.toString(project.getWorkspaceId()), new WorkspaceExecCommand(agentsMdWriteCommand()));
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "AGENTS.md 平台约定写入失败: " + result.stderr());
        }
    }

    /**
     * 可生成守卫：存在 / 未归档 / 未生成过（已生成项目的调整走对话区意见，迭代环）/
     * PRD 已产出（「无门」指待定项不设门；编码 run 的任务就是读 PRD，无 PRD 起跑
     * 只会空烧重试——守的是动作成立的前置事实，不是流程门）。
     */
    private Project requireGeneratableProject(Long projectId) {
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
