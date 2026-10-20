package com.aieducenter.aiplatform.business.project.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.business.project.domain.model.AgentProfile;

import lombok.extern.slf4j.Slf4j;

/**
 * 模型路由判定（#354，ADR-0030——简单活快模型、难活强模型）：run 执行体
 * （{@link AgentProfile#EXECUTOR}——Demo 阶段〔首 Demo ＋ 迭代〕全部任务由其
 * 承载，模型档位正本 #341）的模型档位按任务特征可观测信号自动选档。调用点＝
 * {@code CoderRunAttempts} 每次尝试的命令构建（起跑时读档口径，进行中 run 不
 * 定格）。
 *
 * <p><b>协同口径（路由判定优先、人工覆盖兜底）</b>：路由开＝信号升强档优先
 * （路由判定），无信号回落<b>人工档兜底</b>（库覆盖 → 枚举缺省——覆盖即基础档，
 * 快档位可经 modelId 配置、不关路由）；路由关＝<b>钉死位</b>（显式关断即钉死，
 * 人工档恒生效——对账/对比场景的定档通道）。主智能体/设计执行体不参与路由
 * （调用方只在 executor 座调用本服务）。</p>
 *
 * <p><b>信号三件（任一越阈即升强档，否则回落人工档兜底）</b>：①失败升档——
 * 重试（同段第 N 次尝试）与回滚重跑（首轮重试耗尽）是「难活」的直接事实；
 * ②文件面大小——工作区已跟踪文件数（{@link #countTrackedFiles}，代码面越
 * 大、改动一致性越难）；③片复杂度——切片描述字数（主智能体「宁粗勿碎」，
 * 长描述即粗片）。策略配置化：开关/强档/两阈值走智能体运营配置面
 * （prj_agent_configs 扩展列，PUT /api/backoffice/agent-configs/executor
 * 可调、不发版），null＝回落本类代码缺省。</p>
 *
 * <p><b>决策可观测（哪段用了哪档、为什么——留档可查）</b>：每次判定结构化
 * 日志（档位/来源/理由/信号）+ 收尾卡 closing.modelTier（随对话史落库——
 * 档位 + 来源 + 升档理由 + 判定依据快照；失败段以日志为留档面）。</p>
 */
@Service
@Slf4j
public class ModelRoutingAppService {

    /** 强模型档代码缺省（路由升档落点；运营配置面 routingStrongModelId 可换、不发版）。 */
    public static final String DEFAULT_STRONG_MODEL_ID = "deepseek-v4-pro";

    /** 文件面阈值代码缺省：工作区已跟踪文件数 ≥ 此值即升强档（运营配置面可换）。 */
    public static final int DEFAULT_FILE_COUNT_THRESHOLD = 60;

    /** 片复杂度阈值代码缺省：切片描述字数 ≥ 此值即升强档（运营配置面可换）。 */
    public static final int DEFAULT_SLICE_LENGTH_THRESHOLD = 20;

    /** 档位来源词表（closing.modelTier.source 与日志共用）：路由判定/钉死/枚举缺省。 */
    public static final String SOURCE_ROUTED = "routed";
    public static final String SOURCE_PINNED = "pinned";
    public static final String SOURCE_DEFAULT = "default";

    /**
     * 文件面大小探针（段起点工作区处于收口 commit 上，跟踪≈全量）：先验工作树
     * （非 git 工作区 exitCode 非 0——管道尾 wc 恒 0 会吞 git 失败，故前置
     * rev-parse 判定；cwd 缺省非工作区，显式 cd 同 WorkspaceVersions 先例）。
     */
    private static final String FILE_COUNT_PROBE =
            "cd /workspace && git rev-parse --is-inside-work-tree >/dev/null 2>&1"
                    + " && git ls-files | wc -l";

    private final WorkspaceLifecycleAppService workspaces;

    public ModelRoutingAppService(WorkspaceLifecycleAppService workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * 段级路由信号（轨道层拼装——片复杂度信号的字面事实源在轨道层手里）：
     * {@code sliceDescription} 切片描述原文（null＝非切片段〔阶段 0 / 合并裁决
     * 等〕，不参与片复杂度判定）。
     */
    public record SegmentSignals(String sliceDescription) {

        /** 非切片段（片复杂度信号缺位）。 */
        public static SegmentSignals none() {
            return new SegmentSignals(null);
        }

        /** 切片段（携片描述原文）。 */
        public static SegmentSignals slice(String description) {
            return new SegmentSignals(description);
        }
    }

    /**
     * 一次路由判定的收场：命令构建消费档位与模型串；收尾卡留档消费
     * {@link #payload()} 面（哪段用了哪档、为什么）。
     */
    public record TierSelection(String modelId, String chatModelString, String source,
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
     *                    已取——路由策略与人工档覆盖在其中，不重复查库）
     * @param workspaceId 执行面工作区（fork 片＝fork 副本——文件面按执行面数）
     * @param attempt    本轮第几次尝试（≥2＝重试升档信号）
     * @param round      第几轮（≥2＝回滚重跑升档信号，#353 外环）
     */
    public TierSelection select(AgentConfigAppService.EffectiveConfig effective,
            String workspaceId, int attempt, int round, SegmentSignals signals) {
        String sliceDescription = signals == null ? null : signals.sliceDescription();
        if (!effective.routingEnabled()) {
            return decide(effective, attempt, round, null, sliceDescription);
        }
        return decide(effective, attempt, round, countTrackedFiles(workspaceId), sliceDescription);
    }

    /**
     * 判定单点（纯函数——阈值/强档回落与三信号 OR 语义全在此，单元测试直测）：
     * 路由关＝钉死（人工档恒生效：覆盖 pinned / 缺省 default）；路由开＝信号
     * 升强档（路由判定优先），无信号回落人工档兜底（覆盖 → 枚举缺省）。
     */
    static TierSelection decide(AgentConfigAppService.EffectiveConfig effective, int attempt,
            int round, Integer fileCount, String sliceDescription) {
        int fileThreshold = effective.routingFileCountThreshold() != null
                ? effective.routingFileCountThreshold() : DEFAULT_FILE_COUNT_THRESHOLD;
        int sliceThreshold = effective.routingSliceLengthThreshold() != null
                ? effective.routingSliceLengthThreshold() : DEFAULT_SLICE_LENGTH_THRESHOLD;
        String strongModelId = effective.routingStrongModelId() != null
                ? effective.routingStrongModelId() : DEFAULT_STRONG_MODEL_ID;
        Map<String, Object> signalFacts = new LinkedHashMap<>();
        signalFacts.put("attempt", attempt);
        signalFacts.put("round", round);
        signalFacts.put("fileCount", fileCount);
        Integer sliceLength = sliceDescription == null
                ? null : sliceDescription.strip().length();
        signalFacts.put("sliceLength", sliceLength);
        signalFacts.put("fileCountThreshold", fileThreshold);
        signalFacts.put("sliceLengthThreshold", sliceThreshold);
        if (!effective.routingEnabled()) {
            // 钉死位＝路由关：人工档恒生效（覆盖或枚举缺省——既有口径）
            return new TierSelection(effective.modelId(), effective.chatModelString(),
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
        if (fileCount != null && fileCount >= fileThreshold) {
            escalate = true;
            reasons.add("文件面超出阈值（" + fileCount + " ≥ " + fileThreshold + "）");
        }
        if (sliceLength != null && sliceLength >= sliceThreshold) {
            escalate = true;
            reasons.add("片复杂度超出阈值（" + sliceLength + " 字 ≥ " + sliceThreshold + "）");
        }
        // 路由判定优先：信号升强档；无信号回落人工档兜底（覆盖即基础档 → 枚举缺省）
        String modelId = escalate ? strongModelId : effective.modelId();
        return new TierSelection(modelId, AgentProfile.chatModelStringOf(modelId),
                SOURCE_ROUTED, reasons, signalFacts);
    }

    /**
     * 文件面大小信号：工作区已跟踪文件数（exec 失败/输出不可解析返回 null＝无此
     * 信号——判定降级不升档，采集面不断流）。git 惰性初始化的空仓库输出 0
     * （阶段 0 起跑面——无升档可言）。
     */
    Integer countTrackedFiles(String workspaceId) {
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
            return Integer.valueOf(output);
        }
        catch (RuntimeException e) {
            log.warn("[routing] 文件面大小采集失败（无此信号、降级不升档）：{}", e.toString());
            return null;
        }
    }
}
