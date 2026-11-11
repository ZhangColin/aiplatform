package com.semgravio.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型路由判定（#354，ADR-0030——简单活快模型、难活强模型；信号面 #426 修订）：
 * run 执行体（{@link AgentProfile#EXECUTOR}——Demo 阶段〔首 Demo ＋ 迭代〕全部
 * 任务由其承载，模型档位正本 #341）的模型档位按任务特征可观测信号自动选档。调用
 * 点＝{@code CoderRunAttempts} 每次尝试的命令构建（起跑时读档口径，进行中 run
 * 不定格）。
 *
 * <p><b>协同口径（路由判定优先、人工覆盖兜底）</b>：路由开＝信号升强档优先
 * （路由判定），无信号回落<b>人工档兜底</b>（库覆盖 → 枚举缺省——覆盖即基础档，
 * 快档位可经 modelId 配置、不关路由）；路由关＝<b>钉死位</b>（显式关断即钉死，
 * 人工档恒生效——对账/对比场景的定档通道）。主智能体/设计执行体不参与路由
 * （调用方只在 executor 座调用本服务）。</p>
 *
 * <p><b>信号三件（#454 起结构性两件默认关、失败信号常开）</b>：
 * ①失败升档——重试（同段第 N 次尝试）与回滚重跑（首轮重试耗尽）是「难活」的
 * 直接事实（<b>不受结构性信号开关影响、常开</b>）；②文件面大小——<b>应用文件面</b>＝
 * 工作区已跟踪文件数扣基座文件基数（{@link #countApplicationFiles} 探针内扣除——
 * 基座基数随镜像内置模板 {@code /opt/baseline} 计数单源、基座换版自动跟随，不再以
 * 常数手工同步）；③规模标签——主智能体产计划时自报的片实现面积
 * （{@link SliceSizeLabel} 小/中/大封闭集，#423 结构化产出）按映射升档，缺省映射＝
 * 「大」升强档、小/中回落人工档（枚举密度信号退役——它度量描述写法而非实现面积，
 * 「描述短、功能重」的大片被系统性误留快档）。<b>结构性信号开关（#454 用户裁决，
 * ADR-0037）</b>：文件面/规模标签各持信号级开关（缺省 false＝不参与升档判定——
 * 观测值照留进判定快照、标未参与；开关开＝恢复对应判据，阈值/映射旋钮照旧）；
 * 理由：文件面只增不减、「越大越强模」激励错位，规模标签同理是静态自报。策略配
 * 置化：开关/强档/文件面阈值/升档标签映射/两信号开关走智能体运营配置面
 * （prj_agent_configs 扩展列，PUT /api/backoffice/agent-configs/executor 可调、
 * 不发版），null＝回落本类代码缺省。</p>
 *
 * <p><b>决策可观测（哪段用了哪档、为什么——留档可查）</b>：每次判定结构化
 * 日志（档位/来源/理由/信号）+ 收尾卡 closing.modelTier（随对话史落库——
 * 档位 + 来源 + 升档理由 + 判定依据快照〔含规模标签判定快照〕；失败段以日志为
 * 留档面）。</p>
 */
@Service
@Slf4j
public class ModelRoutingAppService {

    /**
     * 强模型档代码缺省（路由升档落点；运营配置面 routingStrongModelId 可换、
     * 不发版）。ADR-0031 起值＝档位语义键（prj_model_tiers 行）。
     */
    public static final String DEFAULT_STRONG_MODEL_ID = "pro";

    /**
     * 文件面阈值代码缺省：<b>应用文件面</b>（已跟踪文件数扣基座基数）≥ 此值即
     * 升强档（运营配置面可换）。#426 重校：原 60 计总量（含基座），排除基座基数
     * 后按实测重校——生产慢片带（最慢 6 个 flash 大片）落在 35～47 应用文件区间
     * 且 60 总量阈只在项目尾期才燃（调研 2026-11-06-llm-segment-efficiency-profile），
     * 30 使观测慢片带整带越阈、早期薄片与小型项目仍回落快档。
     */
    public static final int DEFAULT_FILE_COUNT_THRESHOLD = 30;

    /**
     * 升档规模标签映射代码缺省：「大」升强档、小/中回落人工档（运营配置面
     * routingEscalatingSizeLabels 可换、不发版——空集＝规模标签信号整体关停，
     * 合法运营选择）。#426 替换原枚举阈值配置项语义（枚举密度信号退役）。
     */
    public static final Set<SliceSizeLabel> DEFAULT_ESCALATING_SIZE_LABELS =
            Set.of(SliceSizeLabel.LARGE);

    /** 档位来源词表（closing.modelTier.source 与日志共用）：路由判定/钉死/枚举缺省。 */
    public static final String SOURCE_ROUTED = "routed";
    public static final String SOURCE_PINNED = "pinned";
    public static final String SOURCE_DEFAULT = "default";

    /**
     * 文件面大小探针（段起点工作区处于收口 commit 上，跟踪≈全量）：先验工作树
     * （非 git 工作区 exitCode 非 0——管道尾 wc 恒 0 会吞 git 失败，故前置
     * rev-parse 判定；cwd 缺省非工作区，显式 cd 同 WorkspaceVersions 先例）。
     * 输出＝<b>应用文件面</b>：已跟踪文件数 − 基座文件基数（#426）。基座基数随
     * 镜像内置模板单源——{@code find /opt/baseline}（dev-image.Dockerfile 的
     * COPY baseline/ /opt/baseline/）现场计数，基座扩容/换版自动跟随、不漂移
     * （node_modules 是构建期 pnpm install 的产物、git 亦不入版，排除在基数外）。
     * 命令形态遵 live 车道纪律：单引号、无 shell 变量中间层（数值回承例外）。
     */
    private static final String FILE_COUNT_PROBE =
            "cd /workspace && git rev-parse --is-inside-work-tree >/dev/null 2>&1"
                    + " && echo $(( $(git ls-files | wc -l)"
                    + " - $(find /opt/baseline -type f -not -path '*/node_modules/*' | wc -l) ))";

    private final WorkspaceLifecycleAppService workspaces;

    public ModelRoutingAppService(WorkspaceLifecycleAppService workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * 段级路由信号（轨道层拼装——规模标签的事实源在计划手里）：{@code sizeLabel}
     * 片规模标签（null＝信号缺位——阶段 0 / 合并裁决 / 未设标签的存量计划片）。
     */
    public record SegmentSignals(SliceSizeLabel sizeLabel) {

        /** 无规模标签段（阶段 0 / 修正 / 设计 / 合并裁决轨——信号缺位）。 */
        public static SegmentSignals none() {
            return new SegmentSignals(null);
        }

        /** 切片段（携片规模标签；null 标签＝存量计划未设，同信号缺位）。 */
        public static SegmentSignals slice(SliceSizeLabel sizeLabel) {
            return new SegmentSignals(sizeLabel);
        }
    }

    /**
     * 一次路由判定的收场：命令构建消费档位键（模型串与通道注入由
     * {@code ModelTierAppService#resolve} 解析——判定层不越层）；收尾卡留档消费
     * {@link #payload()} 面（哪段用了哪档、为什么）。
     */
    public record TierSelection(String modelId, String source,
            List<String> reasons, Map<String, Object> signals) {

        /** 收尾卡留档载荷（closing.modelTier）：档位 + 来源 + 升档理由 + 判定依据快照。 */
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("modelId", modelId);
            payload.put("source", source);
            if (!reasons.isEmpty()) {
                payload.put("reasons", reasons);
            }
            payload.put("signals", signals);
            return payload;
        }
    }

    /**
     * 路由判定（每次尝试的命令构建时调用）：钉死路径（路由关）零信号采集（exec
     * 不白跑），路由判定路径采集文件面大小（采集失败降级无此信号——不升档、
     * 不断流）。
     *
     * @param effective  execute 座生效配置（{@link AgentConfigAppService#effectiveOf}
     *                    已取——路由策略与人工档覆盖在其中，不重复查库；结构性信号
     *                    两开关 #454 亦在其中：缺省 false＝该信号不参与升档判定）
     * @param workspaceId 执行面工作区（fork 片＝fork 副本——文件面按执行面数）
     * @param attempt    本轮第几次尝试（≥2＝重试升档信号）
     * @param round      第几轮（≥2＝回滚重跑升档信号，#353 外环）
     */
    public TierSelection select(AgentConfigAppService.EffectiveConfig effective,
            String workspaceId, int attempt, int round, SegmentSignals signals) {
        SliceSizeLabel sizeLabel = signals == null ? null : signals.sizeLabel();
        if (!effective.routingEnabled()) {
            return decide(effective, attempt, round, null, sizeLabel);
        }
        // 结构性信号开关不影响采集——文件面探针照常跑（关了的信号留观测值、
        // 只不参与判定——对照实验数据不断档，#454 观测兼容要件）
        return decide(effective, attempt, round, countApplicationFiles(workspaceId), sizeLabel);
    }

    /**
     * 判定单点（纯函数——阈值/映射/强档回落与三信号 OR 语义全在此，单元测试直测）：
     * 路由关＝钉死（人工档恒生效：覆盖 pinned / 缺省 default）；路由开＝信号
     * 升强档（路由判定优先），无信号回落人工档兜底（覆盖 → 枚举缺省）。
     *
     * <p><b>结构性信号开关（#454 用户裁决）</b>：文件面/规模标签两信号各持开关
     * （生效配置束带入，缺省 false＝关）——关＝该信号的观测值照进判定快照但不参与
     * 升档判定；失败重试信号（重试/回滚重跑）不受两开关影响、常开。开关开＝恢复
     * 对应判据（阈值/映射旋钮照旧生效）。
     */
    static TierSelection decide(AgentConfigAppService.EffectiveConfig effective, int attempt,
            int round, Integer fileCount, SliceSizeLabel sizeLabel) {
        int fileThreshold = effective.routingFileCountThreshold() != null
                ? effective.routingFileCountThreshold() : DEFAULT_FILE_COUNT_THRESHOLD;
        Set<SliceSizeLabel> escalating = effective.routingEscalatingSizeLabels() != null
                ? effective.routingEscalatingSizeLabels() : DEFAULT_ESCALATING_SIZE_LABELS;
        String strongModelId = effective.routingStrongModelId() != null
                ? effective.routingStrongModelId() : DEFAULT_STRONG_MODEL_ID;
        boolean fileSignalOn = effective.routingFileCountSignalEnabled();
        boolean sizeSignalOn = effective.routingSizeLabelSignalEnabled();
        Map<String, Object> signalFacts = new LinkedHashMap<>();
        signalFacts.put("attempt", attempt);
        signalFacts.put("round", round);
        signalFacts.put("fileCount", fileCount);
        signalFacts.put("sizeLabel", sizeLabel == null ? null : sizeLabel.getName());
        signalFacts.put("fileCountThreshold", fileThreshold);
        signalFacts.put("escalatingSizeLabels", SliceSizeLabel.namesOf(escalating));
        signalFacts.put("fileCountSignalEnabled", fileSignalOn);
        signalFacts.put("sizeLabelSignalEnabled", sizeSignalOn);
        if (!effective.routingEnabled()) {
            // 钉死位＝路由关：人工档恒生效（覆盖或枚举缺省——既有口径）
            return new TierSelection(effective.modelId(),
                    effective.modelIdOverridden() ? SOURCE_PINNED : SOURCE_DEFAULT,
                    List.of(), signalFacts);
        }
        List<String> reasons = new ArrayList<>();
        boolean escalate = false;
        if (round > 1) {
            escalate = true;
            reasons.add("回滚重跑升档（首轮重试耗尽）");
        }
        else if (attempt > 1) {
            escalate = true;
            reasons.add("重试升档（第 " + attempt + " 次尝试）");
        }
        if (fileSignalOn && fileCount != null && fileCount >= fileThreshold) {
            escalate = true;
            reasons.add("文件面超出阈值（" + fileCount + " ≥ " + fileThreshold + "）");
        }
        if (sizeSignalOn && sizeLabel != null && escalating.contains(sizeLabel)) {
            escalate = true;
            reasons.add("规模标签升档（" + sizeLabel.getName() + "）");
        }
        // 路由判定优先：信号升强档；无信号回落人工档兜底（覆盖即基础档 → 枚举缺省）
        String modelId = escalate ? strongModelId : effective.modelId();
        return new TierSelection(modelId, SOURCE_ROUTED, reasons, signalFacts);
    }

    /**
     * 文件面大小信号：<b>应用文件面</b>＝工作区已跟踪文件数 − 基座文件基数（探针
     * 单次 exec 内一并扣除，输出即差值；负值〔阶段 0 空仓库——tracked 0 − 基座
     * 基数〕钳 0＝尚无应用文件）。exec 失败/输出不可解析返回 null＝无此信号
     * （判定降级不升档，采集面不断流）。
     */
    Integer countApplicationFiles(String workspaceId) {
        try {
            ExecResultResponse result = workspaces.exec(workspaceId,
                    new WorkspaceExecCommand(FILE_COUNT_PROBE));
            if (result.exitCode() != 0) {
                return null;
            }
            String output = result.stdout() == null ? "" : result.stdout().trim();
            if (output.isEmpty()) {
                return null;
            }
            return Math.max(0, Integer.valueOf(output));
        }
        catch (RuntimeException e) {
            log.warn("[routing] 文件面大小采集失败（无此信号、降级不升档）：{}", e.toString());
            return null;
        }
    }
}
