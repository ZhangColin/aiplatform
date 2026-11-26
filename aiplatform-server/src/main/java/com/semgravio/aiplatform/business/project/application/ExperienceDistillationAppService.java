package com.semgravio.aiplatform.business.project.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.cartisan.data.jpa.id.TsidGenerator;

import com.semgravio.aiplatform.base.agentscope.AgentCommand;
import com.semgravio.aiplatform.base.agentscope.AgentReply;
import com.semgravio.aiplatform.base.agentscope.AgentscopeAgentClient;
import com.semgravio.aiplatform.base.agentscope.ModelCandidateQueue;
import com.semgravio.aiplatform.base.agentscope.UsageContext;
import com.semgravio.aiplatform.base.eventhub.application.EventsAppService;
import com.semgravio.aiplatform.base.knowledge.application.ExperienceDistillationLedgerAppService;
import com.semgravio.aiplatform.base.knowledge.domain.enums.ExperienceDistillationStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentKind;
import com.semgravio.aiplatform.base.knowledge.domain.model.ExperienceDistillationRecord;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftProposal;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;
import com.semgravio.aiplatform.business.project.domain.aggregate.Project;
import com.semgravio.aiplatform.business.project.domain.model.AgentEdgePoint;
import com.semgravio.aiplatform.business.project.domain.model.UsageDims;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersion;
import com.semgravio.aiplatform.business.project.domain.repository.ProjectRepository;

import io.agentscope.harness.agent.skill.curator.SkillSecurityScanner;

/**
 * 事后提炼（#641，阶段六-3；ADR-0058）：每出一个用户面版本，后台用便宜模型读
 * 「自上次成功提炼以来新增的修改意见」，提出最多 3 条可跨项目复用的候选经验，
 * 带来源三件（项目／版本／原句）落待审表；同时判定每条修改意见是否属于「需求
 * 写明了但没做对」，供飞轮三个数统计。
 *
 * <p><b>触发与窗口</b>：成版成功后由 {@link ProjectVersionAppService
 * #commitUserVersion} 触发（fire-and-forget）。窗口＝「上次成功处理过的版本」
 * （处理记录的最近非失败版本号）之后的全部用户面版本；首次处理某项目时基线取
 * 触发版本的前一版，只处理触发版本本身，不倒灌历史。窗口内没有修改意见（第一版
 * 或只有首建）时不调用模型，只写一条「无修改意见」记录，游标照常前进。</p>
 *
 * <p><b>输入</b>：工作区最新需求文档全文＋窗口内每个版本的标签（用户原话；多轮
 * 合并补派按句分行，保持逐句可辨）。不读对话记录、不新增修改意见表。</p>
 *
 * <p><b>输出与校验</b>：模型只输出一个 JSON 对象（候选经验 + 逐条意见判断）。
 * 候选类别限三类、标题/摘要/正文沿用草稿上限、最多取前 3 条；来源版本号必须落在
 * 本窗口、来源原句必须是该版本标签原文（空白归一后包含判断）；校验不过的丢弃并
 * 记日志。</p>
 *
 * <p><b>静态扫描</b>：每条候选过 {@link SkillSecurityScanner}——危险内容丢弃不
 * 落库，可疑内容放行并随草稿保存扫描判定与明细。</p>
 *
 * <p><b>调用形态</b>：独立一次性会话、无工具、不触项目工作区、无 SSE；超时 120 秒；
 * 后台单线程队列按触发顺序串行。模型走「模型配置→候选队列→通道行」供给链（带
 * 故障切换），计量归属项目、智能体种类＝refinement（进项目成本切片）。失败/超时/
 * 输出不可解析只写一条失败记录——不写草稿、游标不前进、不影响成版，下一版本自然
 * 重试失败窗口。</p>
 */
@Service
@Slf4j
public class ExperienceDistillationAppService implements DisposableBean {

    /** 后台提炼队列线程名（单线程串行，按触发顺序处理）。 */
    static final String THREAD_NAME = "experience-distillation";

    /** 一次性会话标识前缀（refinement-{projectId}-v{versionNo}）。 */
    static final String SESSION_PREFIX = "refinement-";

    /** 每次提炼最多接受的候选条数（超出只取前 3 条；#642 拆在途名额后这是唯一上限）。 */
    static final int MAX_CANDIDATES = 3;

    /** 提炼会话超时（一次性判定，120 秒总墙钟）。 */
    static final Duration REFINEMENT_TIMEOUT = Duration.ofSeconds(120);

    /** 平台生成的回滚版本标签（「回到 vN」/「回到「主题」」）——不是用户修改原话。 */
    private static final Pattern ROLLBACK_LABEL = Pattern.compile("^回到 (v\\d+|「.*」)$");

    /** 多轮合并补派的分隔符（#626 以「；」合并；换行/分号防御兼容）。 */
    private static final Pattern OPINION_SEPARATOR = Pattern.compile("[；;\\r\\n]");

    /** 候选经验提炼协议（编排内建，不属运营配置）。 */
    static final String SYSTEM_PROMPT = """
            你是软件平台的知识提炼助手。平台会在用户每完成一次系统修改后，把最新需求文档和本轮新增的用户修改意见交给你，请据此提出可跨项目复用的候选经验，并判断每条修改意见是否属于「需求文档里已经写明、但做出来的系统没做对」。

            输出要求：只输出一个 JSON 对象，不要输出任何解释文字或 Markdown 代码块。JSON 形状如下：
            {
              "candidates": [
                {
                  "kind": "CASE 或 MODULE 或 RULE",
                  "title": "一句话标题（不超过 200 字）",
                  "summary": "一句话说明这是什么、何时复用（不超过 1024 字）",
                  "content": "候选经验的完整正文（Markdown）",
                  "sourceVersion": 2,
                  "sourceSentence": "取自该版本修改意见的原始句子"
                }
              ],
              "opinions": [
                {
                  "sourceVersion": 2,
                  "sourceSentence": "该版本的一条修改意见原句",
                  "missed": true,
                  "reason": "一句话理由"
                }
              ]
            }

            规则：
            1. 候选经验最多 3 条；只提跨项目可复用的经验（需求案例 CASE／功能模块 MODULE／业务规则 RULE 三类）。
            2. 不要写任何项目名、用户名、公司名或其他用户隐私；不要写只对当前项目成立的内容。
            3. 没有可复用内容时输出空的 candidates 数组。
            4. 不做方法论文档、不生成技能。
            5. sourceVersion 必须是下面列出的版本号之一；sourceSentence 必须逐字取自该版本的修改意见原句（不要改写）。
            6. opinions 覆盖下面列出的每条修改意见：missed=true 表示「需求文档写明了但系统没做对」，否则 false。""";

    private final ProjectRepository projectRepository;
    private final WorkspaceVersionLogReader versionLogReader;
    private final ProjectQueryAppService projectQueryAppService;
    private final AgentConfigAppService agentConfigs;
    private final ModelConfigAppService modelConfigs;
    private final AgentscopeAgentClient agentClient;
    private final ExperienceDistillationLedgerAppService ledger;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 提交通道（生产＝自有单线程队列；测试＝直通同步）。 */
    private final Executor executor;
    /** 生产队列生命周期（测试注入直通道时为 null）。 */
    private final ExecutorService ownedExecutor;

    @Autowired
    public ExperienceDistillationAppService(ProjectRepository projectRepository,
            WorkspaceVersionLogReader versionLogReader,
            ProjectQueryAppService projectQueryAppService,
            AgentConfigAppService agentConfigs,
            ModelConfigAppService modelConfigs,
            AgentscopeAgentClient agentClient,
            ExperienceDistillationLedgerAppService ledger) {
        this.projectRepository = projectRepository;
        this.versionLogReader = versionLogReader;
        this.projectQueryAppService = projectQueryAppService;
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
        this.agentClient = agentClient;
        this.ledger = ledger;
        this.ownedExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        this.executor = ownedExecutor;
    }

    /** 测试便利构造（直通道，无队列生命周期）。 */
    ExperienceDistillationAppService(ProjectRepository projectRepository,
            WorkspaceVersionLogReader versionLogReader,
            ProjectQueryAppService projectQueryAppService,
            AgentConfigAppService agentConfigs,
            ModelConfigAppService modelConfigs,
            AgentscopeAgentClient agentClient,
            ExperienceDistillationLedgerAppService ledger,
            Executor executor) {
        this.projectRepository = projectRepository;
        this.versionLogReader = versionLogReader;
        this.projectQueryAppService = projectQueryAppService;
        this.agentConfigs = agentConfigs;
        this.modelConfigs = modelConfigs;
        this.agentClient = agentClient;
        this.ledger = ledger;
        this.executor = executor;
        this.ownedExecutor = null;
    }

    @Override
    public void destroy() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    /**
     * 成版后异步触发提炼（fire-and-forget）：任何失败只记日志，绝不反噬成版；
     * 队列提交失败同样吞掉。
     */
    public void triggerOnVersionAsync(Long projectId, Integer versionNo) {
        if (projectId == null || versionNo == null) {
            return;
        }
        try {
            executor.execute(() -> distillQuietly(projectId, versionNo));
        }
        catch (RuntimeException e) {
            log.warn("[distill] 项目 {} 版本 {} 提炼任务提交失败（降级跳过，不阻断成版）：{}",
                    projectId, versionNo, e.toString());
        }
    }

    // ---------- 内部 ----------

    /** 任务体（吞异常版）：任何失败只记日志——游标不前进，下一版本自然重试窗口。 */
    private void distillQuietly(Long projectId, int versionNo) {
        try {
            doDistill(projectId, versionNo);
        }
        catch (RuntimeException e) {
            log.warn("[distill] 项目 {} 版本 {} 提炼异常（降级跳过，游标不前进）：{}",
                    projectId, versionNo, e.toString());
        }
    }

    private void doDistill(Long projectId, int versionNo) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            log.info("[distill] 项目 {} 已不存在，提炼跳过", projectId);
            return;
        }
        ExperienceDistillationRecord existing = ledger.find(projectId, versionNo);
        if (existing != null && existing.status() != ExperienceDistillationStatus.FAILED) {
            // 重复触发同一版本：已成功处理过（含无修改意见），不重复调模型/计费
            log.info("[distill] 项目 {} 版本 {} 已有 {} 处理记录，重复触发跳过", projectId,
                    versionNo, existing.status().getName());
            return;
        }
        List<WorkspaceVersion> allVersions = versionLogReader.read(project);
        WorkspaceVersion trigger = findVersion(allVersions, versionNo);
        if (trigger == null) {
            log.warn("[distill] 项目 {} 在版本列表中找不到 v{}，提炼跳过（游标不前进）",
                    projectId, versionNo);
            return;
        }
        Integer cursor = ledger.latestNonFailedVersionNo(projectId);
        List<WorkspaceVersion> window = windowOf(allVersions, trigger, cursor);
        List<Opinion> opinions = opinionsOf(window);
        String batchId = Long.toString(TsidGenerator.newInstance().generate());
        if (opinions.isEmpty()) {
            // 第一版或窗口内没有修改意见：不调用模型，只记一条「无修改意见」，游标前进
            ledger.land(record(project, trigger, batchId,
                    ExperienceDistillationStatus.NO_OPINION, 0, 0, 0, null), List.of());
            log.info("[distill] 项目 {} 版本 {} 窗口内无修改意见，记无修改意见记录（游标前进到 v{}）",
                    projectId, versionNo, versionNo);
            return;
        }
        DistillationOutput output;
        try {
            output = callModel(projectId, versionNo, opinions);
        }
        catch (RuntimeException e) {
            landFailed(project, trigger, batchId, e);
            return;
        }
        List<SedimentDraftRecord> drafts = validateAndScan(output, window, project,
                trigger, batchId);
        int missed = countMissed(output.opinions(), window);
        ledger.land(record(project, trigger, batchId,
                ExperienceDistillationStatus.DISTILLED, opinions.size(), missed,
                drafts.size(), null), drafts);
        log.info("[distill] 项目 {} 版本 {} 提炼完成：意见 {} 条、没做对 {} 条、候选落库 {} 条（批次 {}）",
                projectId, versionNo, opinions.size(), missed, drafts.size(), batchId);
    }

    /**
     * 窗口：游标之后的全部用户面版本（含触发版本）。首次处理（游标 null）只处理
     * 触发版本本身——基线取前一版，不倒灌历史。
     */
    private static List<WorkspaceVersion> windowOf(List<WorkspaceVersion> all,
            WorkspaceVersion trigger, Integer cursor) {
        if (cursor == null) {
            return List.of(trigger);
        }
        return all.stream()
                .filter(version -> version.versionNo() != null)
                .filter(version -> version.versionNo() > cursor
                        && version.versionNo() <= trigger.versionNo())
                .sorted(Comparator.comparingInt(WorkspaceVersion::versionNo))
                .toList();
    }

    private static WorkspaceVersion findVersion(List<WorkspaceVersion> all, int versionNo) {
        return all.stream()
                .filter(version -> version.versionNo() != null
                        && version.versionNo() == versionNo)
                .findFirst()
                .orElse(null);
    }

    /**
     * 窗口内的修改意见（逐句）：首建版本（v1）的标签是初始需求不是修改意见；多轮
     * 合并补派的标签按「；」/换行拆句，保持逐句可辨；平台回滚标签（「回到 vN」）
     * 不是用户原话，排除。
     */
    private static List<Opinion> opinionsOf(List<WorkspaceVersion> window) {
        List<Opinion> opinions = new ArrayList<>();
        for (WorkspaceVersion version : window) {
            if (version.versionNo() == null || version.versionNo() <= 1) {
                continue;
            }
            for (String sentence : splitOpinions(version.subject())) {
                if (!ROLLBACK_LABEL.matcher(sentence).matches()) {
                    opinions.add(new Opinion(version.versionNo(), sentence));
                }
            }
        }
        return opinions;
    }

    private static List<String> splitOpinions(String label) {
        if (label == null || label.isBlank()) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        for (String part : OPINION_SEPARATOR.split(label)) {
            String sentence = part.trim();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    /** 一次性提炼调用：供给链解析模型与通道（带故障切换）、空 sink、无工具、本地工作区。 */
    private DistillationOutput callModel(Long projectId, int versionNo,
            List<Opinion> opinions) {
        ModelCandidateQueue model = modelConfigs.resolveQueue(
                agentConfigs.effectiveEdgeModelConfigKey(AgentEdgePoint.REFINEMENT));
        String sessionId = SESSION_PREFIX + projectId + "-v" + versionNo;
        AgentCommand command = new AgentCommand(
                EventsAppService.newRunId(),
                userPrompt(projectId, opinions),
                SYSTEM_PROMPT,
                model.modelString(),
                model.channel(),
                sessionId,
                null, // 一次性会话，无 (userId, sessionId) 槽位复用语义
                new UsageContext(Long.toString(projectId),
                        UsageDims.of(projectId, UsageDims.AGENT_KIND_REFINEMENT, sessionId)),
                null, // 本地兜底工作区：提炼不读写项目工作区
                Map.of(),
                REFINEMENT_TIMEOUT,
                /* silenceTimeout= */ null,
                /* toolSilenceTimeout= */ null,
                null, // 一次性判定无配置语境：空工具面
                /* workspaceReadOnly= */ false,
                /* heading= */ null,
                /* toolSpec= */ null,
                model);
        AgentReply reply = agentClient.converse(command, event -> {
        });
        return parse(reply == null ? null : reply.text());
    }

    /** 提炼输入：需求文档全文＋窗口内逐句修改意见（带来源版本号）。 */
    private String userPrompt(Long projectId, List<Opinion> opinions) {
        String prd = prdOf(projectId);
        StringBuilder prompt = new StringBuilder();
        prompt.append("最新需求文档（工作区 docs/PRD.md）：\n");
        prompt.append(prd);
        prompt.append("\n\n本次新增的用户修改意见（每条带来源版本号；多轮合并的已按句分行）：\n");
        for (Opinion opinion : opinions) {
            prompt.append("v").append(opinion.versionNo()).append("：")
                    .append(opinion.sentence()).append("\n");
        }
        prompt.append("\n请按约定的 JSON 形状输出候选经验与逐条意见判断。");
        return prompt.toString();
    }

    /** 读取最新需求文档（失败/未产出降级为空串——提炼仍可据意见进行）。 */
    private String prdOf(Long projectId) {
        try {
            String content = projectQueryAppService.currentPrdContent(projectId);
            return content == null || content.isBlank() ? "（暂无需求文档）" : content;
        }
        catch (RuntimeException e) {
            log.warn("[distill] 项目 {} 需求文档读取失败（降级为空，继续提炼）：{}",
                    projectId, e.toString());
            return "（暂无需求文档）";
        }
    }

    /**
     * 解析模型输出（容错外壳）：取首个 '{' 到末个 '}' 之间的 JSON 对象；不可解析
     * 抛异常（调用方记失败记录）。缺 candidates/opinions 视为空数组；单条畸形
     * 条目在后续校验阶段丢弃。
     */
    DistillationOutput parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("提炼输出为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("提炼输出不是 JSON 对象");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw.substring(start, end + 1));
        }
        catch (JsonProcessingException e) {
            throw new IllegalArgumentException("提炼输出不可解析：" + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("提炼输出不是 JSON 对象");
        }
        List<ExtractedCandidate> candidates = new ArrayList<>();
        JsonNode candidatesNode = root.get("candidates");
        if (candidatesNode != null && candidatesNode.isArray()) {
            for (JsonNode node : candidatesNode) {
                candidates.add(new ExtractedCandidate(
                        text(node, "kind"), text(node, "title"), text(node, "summary"),
                        text(node, "content"), integer(node, "sourceVersion"),
                        text(node, "sourceSentence")));
            }
        }
        List<ExtractedOpinion> opinions = new ArrayList<>();
        JsonNode opinionsNode = root.get("opinions");
        if (opinionsNode != null && opinionsNode.isArray()) {
            for (JsonNode node : opinionsNode) {
                opinions.add(new ExtractedOpinion(
                        integer(node, "sourceVersion"), text(node, "sourceSentence"),
                        bool(node, "missed"), text(node, "reason")));
            }
        }
        return new DistillationOutput(candidates, opinions);
    }

    /**
     * 候选校验 + 静态扫描 → 待审草稿。只接受模型输出的前 3 条（超出取前 3）；
     * 校验不过的丢弃；DANGEROUS 丢弃、CAUTION/SAFE 放行并留扫描回执。
     */
    private List<SedimentDraftRecord> validateAndScan(DistillationOutput output,
            List<WorkspaceVersion> window, Project project, WorkspaceVersion trigger,
            String batchId) {
        List<SedimentDraftRecord> drafts = new ArrayList<>();
        int considered = Math.min(output.candidates().size(), MAX_CANDIDATES);
        for (int index = 0; index < considered; index++) {
            ExtractedCandidate candidate = output.candidates().get(index);
            String violation = candidateViolation(candidate, window);
            if (violation != null) {
                log.info("[distill] 项目 {} 候选 #{} 丢弃：{}", project.getId(), index + 1,
                        violation);
                continue;
            }
            SkillSecurityScanner.ScanResult scan = SkillSecurityScanner.scan(
                    candidate.title(), scanSurfaceOf(candidate), Map.of());
            if (scan.verdict() == SkillSecurityScanner.Verdict.DANGEROUS) {
                log.info("[distill] 项目 {} 候选 #{} 扫描拒写 DANGEROUS（{}）", project.getId(),
                        index + 1, patternIdsOf(scan));
                continue;
            }
            SedimentKind kind = SedimentKind.byKey(candidate.kind()).orElseThrow();
            drafts.add(new SedimentDraftRecord(
                    TsidGenerator.newInstance().generate(),
                    kind,
                    candidate.title(),
                    candidate.summary(),
                    candidate.content(),
                    Map.of(),
                    project.getId(),
                    batchId,
                    AgentEdgePoint.REFINEMENT.key(),
                    scan.verdict().name(),
                    findingsOf(scan),
                    SedimentDraftStatus.PENDING,
                    null, null, null, null, null,
                    project.getName(),
                    candidate.sourceVersion(),
                    commitHashOf(window, candidate.sourceVersion()),
                    candidate.sourceSentence(),
                    batchId));
        }
        return drafts;
    }

    /** 候选校验（违例描述；通过返回 null）。 */
    private static String candidateViolation(ExtractedCandidate candidate,
            List<WorkspaceVersion> window) {
        if (candidate == null) {
            return "候选为空";
        }
        var contentViolation = SedimentDraftProposal.contentViolation(candidate.kind(),
                candidate.title(), candidate.summary(), candidate.content());
        if (contentViolation.isPresent()) {
            return contentViolation.get();
        }
        if (candidate.sourceVersion() == null) {
            return "缺来源版本号";
        }
        WorkspaceVersion source = findVersion(window, candidate.sourceVersion());
        if (source == null) {
            return "来源版本 " + candidate.sourceVersion() + " 不在本窗口";
        }
        if (candidate.sourceSentence() == null || candidate.sourceSentence().isBlank()) {
            return "缺来源原句";
        }
        if (!normalize(source.subject()).contains(normalize(candidate.sourceSentence()))) {
            return "来源原句不是该版本标签原文";
        }
        return null;
    }

    /** 没做对条数：来源版本在窗口、原句为标签原文、missed=true 的有效判断。 */
    private static int countMissed(List<ExtractedOpinion> opinions,
            List<WorkspaceVersion> window) {
        int missed = 0;
        for (ExtractedOpinion opinion : opinions) {
            if (opinion == null || !opinion.missed() || opinion.sourceVersion() == null
                    || opinion.sourceVersion() <= 1) {
                continue;
            }
            WorkspaceVersion source = findVersion(window, opinion.sourceVersion());
            if (source == null || opinion.sourceSentence() == null) {
                continue;
            }
            if (normalize(source.subject()).contains(normalize(opinion.sourceSentence()))) {
                missed++;
            }
        }
        return missed;
    }

    /** 来源版本提交哈希（校验已保证在窗口；查无防御性 null）。 */
    private static String commitHashOf(List<WorkspaceVersion> window, Integer versionNo) {
        WorkspaceVersion version = versionNo == null ? null : findVersion(window, versionNo);
        return version == null ? null : version.commitHash();
    }

    /** 失败记录（不写草稿、游标不前进）。失败记录本身落库失败只记日志。 */
    private void landFailed(Project project, WorkspaceVersion trigger, String batchId,
            RuntimeException error) {
        String summary = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        try {
            ledger.land(record(project, trigger, batchId,
                    ExperienceDistillationStatus.FAILED, 0, 0, 0, truncate(summary)), List.of());
            log.warn("[distill] 项目 {} 版本 {} 提炼失败（不写草稿、游标不前进）：{}",
                    project.getId(), trigger.versionNo(), summary);
        }
        catch (RuntimeException e) {
            log.warn("[distill] 项目 {} 版本 {} 失败记录落库也失败（降级跳过）：{}",
                    project.getId(), trigger.versionNo(), e.toString());
        }
    }

    private ExperienceDistillationRecord record(Project project, WorkspaceVersion trigger,
            String batchId, ExperienceDistillationStatus status, int opinionCount,
            int missedCount, int candidateCount, String errorSummary) {
        return new ExperienceDistillationRecord(
                TsidGenerator.newInstance().generate(),
                project.getId(),
                project.getName(),
                trigger.versionNo(),
                trigger.commitHash(),
                status,
                opinionCount,
                missedCount,
                candidateCount,
                batchId,
                errorSummary,
                null);
    }

    /** 空白归一：连续空白折单空格、首尾裁剪（来源原句包含判断口径）。 */
    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String scanSurfaceOf(ExtractedCandidate candidate) {
        return candidate.title() + "\n" + candidate.summary() + "\n\n" + candidate.content();
    }

    private List<Map<String, Object>> findingsOf(SkillSecurityScanner.ScanResult scan) {
        return scan.findings().stream()
                .map(finding -> objectMapper.convertValue(finding,
                        new TypeReference<Map<String, Object>>() { }))
                .toList();
    }

    private static String patternIdsOf(SkillSecurityScanner.ScanResult scan) {
        return scan.findings().stream()
                .map(SkillSecurityScanner.Finding::patternId)
                .distinct()
                .toList()
                .toString();
    }

    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 499) + "…";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText().trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return false;
        }
        return value.isBoolean() ? value.asBoolean()
                : Boolean.parseBoolean(value.asText().trim());
    }

    /** 窗口内一条修改意见（来源版本号＋原句）。 */
    record Opinion(int versionNo, String sentence) {
    }

    /** 模型输出的一条候选（未校验）。 */
    record ExtractedCandidate(String kind, String title, String summary, String content,
            Integer sourceVersion, String sourceSentence) {
    }

    /** 模型输出的一条意见判断（未校验）。 */
    record ExtractedOpinion(Integer sourceVersion, String sourceSentence, boolean missed,
            String reason) {
    }

    /** 模型输出整体（候选 + 意见判断）。 */
    record DistillationOutput(List<ExtractedCandidate> candidates,
            List<ExtractedOpinion> opinions) {
    }
}
