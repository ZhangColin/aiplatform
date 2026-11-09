package com.semgravio.aiplatform.business.project.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;
import com.semgravio.aiplatform.business.project.domain.model.WorkspaceVersions;

import lombok.extern.slf4j.Slf4j;

/**
 * 产出证明闸（#420 空转片防呆，#405 裁决）：收口判据在既有硬门（服务探活＋入口
 * 文件完整）上新增「产出证明」维度——<b>本段起点以来工作区变更 diff＞0、或验证
 * 确认「能力已存在」，二者必居一</b>（平台侧判定、不经模型自报；触发信号只取
 * diff=0 这一个确定性信号）。
 *
 * <p><b>拒绝回路（同设计规范 lint 违规修轮先例的喂回形）</b>：零 diff＋无确认＝
 * 结构性拒绝——判定件抛出 {@link IllegalStateException}，被共用尝试环当作该次
 * 尝试失败，拒绝回执经重试现场（{@code Prompts#retry} 的错误现场）喂回执行体
 * <b>同 run 补做</b>（补实现／补验证确认「能力已存在」）；有限重试耗尽走段级
 * 自愈重跑（#353 既有回滚重跑机制，重跑轮即经既有路由「回滚重跑升档」信号自动
 * 升档——不建新通道、路由面零改动）。</p>
 *
 * <p><b>豁免与标注</b>：验证确认「能力已存在」（report_verification 的
 * existing 确认形态）＝活已被前片干完的薄片，合法收口；豁免依据与空转拒绝史
 * （本场被拒次数）均随 {@link #proof()} 返回的判别标注进收尾卡如实呈现
 * （normal 干活收口且无拒绝史＝null 不携带）。</p>
 *
 * <p><b>段起点锚</b>：起跑捕获（对偶 #353 {@code captureRollbackBase} 的操作序
 * ——ensureRepo → HEAD 回读 → 无 HEAD 锚定 commit）；捕获不成（环境故障）闸
 * 降级跳过——环境故障不是空转片，同「无回滚基准不重跑」取舍。fork 片在 fork
 * 工作区内以同一探针判（fork 基线即段起点——fork 差异口径）。探针只读不动
 * 索引；探测不成降级放行（fail-open：坏探针不拦正常收口，如实记日志）。</p>
 *
 * <p><b>开关化（#433；#435 翻转缺省）</b>：本闸接入运营配置面开关
 * （executor 行 {@code proof_of_output_gate_enabled}，#435 起缺省 false＝
 * 闸关＝#432 Step 1 砍验证税落地态）——闸关＝整场 run 的判定件
 * {@link Checking#proof()} 直返 null（零 diff 放行、
 * 不累计拒绝史、段起点捕获不跑）；开关在 {@link #forRun} 起跑时读档一次（本
 * run 闸态恒定），不发版、进行中 run 不定格。</p>
 */
@Component
@Slf4j
class ProofOfOutputGate {

    /** 判别标注·空转拒绝史键（本场被拒次数——曾拒绝后补做收口的如实呈现）。 */
    static final String IDLE_REJECTIONS_FIELD = "idleRejections";

    /** 判别标注·豁免键（验证确认「能力已存在」的合法收口）。 */
    static final String EXEMPTED_FIELD = "exempted";

    /** 判别标注·豁免依据键（确认项名拼装的「验证确认」叙事）。 */
    static final String EXEMPTION_BASIS_FIELD = "exemptionBasis";

    private final WorkspaceLifecycleAppService workspaceLifecycle;
    private final VerificationFacts verificationFacts;
    private final AgentConfigAppService agentConfigs;

    ProofOfOutputGate(WorkspaceLifecycleAppService workspaceLifecycle,
            VerificationFacts verificationFacts, AgentConfigAppService agentConfigs) {
        this.workspaceLifecycle = workspaceLifecycle;
        this.verificationFacts = verificationFacts;
        this.agentConfigs = agentConfigs;
    }

    /**
     * 一场 run 的产出证明判定件：起跑先读闸态开关（#433；#435 起缺省 false＝
     * 闸关＝砍验证税落地态，本 run 恒定），闸开再捕获段起点锚；收口判据核验时
     * （探活/入口完整之后、成版与片落表之前）调 {@link Checking#proof()}。
     */
    Checking forRun(Long workspaceId) {
        boolean gateEnabled = agentConfigs.effectiveOf(AgentProfile.EXECUTOR)
                .proofOfOutputGateEnabled();
        return new Checking(workspaceId, gateEnabled,
                gateEnabled ? captureSegmentStart(workspaceId) : null);
    }

    /** 段起点捕获（#353 同操作序）：无 HEAD 锚定；拿不到返回 null（闸降级跳过）。 */
    private String captureSegmentStart(Long workspaceId) {
        String workspace = Long.toString(workspaceId);
        try {
            workspaceLifecycle.exec(workspace,
                    new WorkspaceExecCommand(WorkspaceVersions.ensureRepoCommand()));
            ExecResultResponse head = workspaceLifecycle.exec(workspace,
                    new WorkspaceExecCommand(WorkspaceVersions.headRefCommand()));
            if (head.exitCode() == 0 && !head.stdout().isBlank()) {
                return head.stdout().trim();
            }
            ExecResultResponse anchor = workspaceLifecycle.exec(workspace,
                    new WorkspaceExecCommand(WorkspaceVersions.anchorStartCommand()));
            if (anchor.exitCode() == 0 && !anchor.stdout().isBlank()) {
                return anchor.stdout().trim();
            }
            log.warn("[proof] 工作区 {} 段起点捕获失败（产出证明闸本场降级跳过）：{}",
                    workspaceId, anchor.stderr());
            return null;
        }
        catch (RuntimeException e) {
            log.warn("[proof] 工作区 {} 段起点捕获异常（产出证明闸本场降级跳过）：{}",
                    workspaceId, e.toString());
            return null;
        }
    }

    /**
     * 单场 run 的判定件状态（空转拒绝计数跨尝试累计——同 run 补做的拒绝史）。
     */
    class Checking {

        private final Long workspaceId;
        private final boolean gateEnabled;
        private final String segmentStart;
        private int idleRejections = 0;

        private Checking(Long workspaceId, boolean gateEnabled, String segmentStart) {
            this.workspaceId = workspaceId;
            this.gateEnabled = gateEnabled;
            this.segmentStart = segmentStart;
        }

        /**
         * 产出证明核验（收口判据增补件）：闸关（#433 运营面开关）直返 null（零
         * diff 放行、不累计拒绝史）；闸开＝有 diff 或有确认即过——返回判别标注
         * （null＝无事可标：非豁免收口且无拒绝史）；零 diff＋无确认抛拒绝回执
         * （喂回执行体同 run 补做：补实现／补验证）。
         */
        Map<String, Object> proof() {
            if (!gateEnabled) {
                return null; // 闸关（#432 砍验证税开关态）——不探测、不拒绝、不标注
            }
            if (segmentStart == null) {
                return null; // 段起点捕获不成——闸降级跳过（环境故障不是空转片）
            }
            if (hasWorkspaceDiff()) {
                return idleRejections > 0
                        ? Map.of(IDLE_REJECTIONS_FIELD, idleRejections)
                        : null;
            }
            String confirmation = verificationFacts.capabilityExistsConfirmation(
                    Long.toString(workspaceId));
            if (confirmation != null) {
                Map<String, Object> annotation = new LinkedHashMap<>();
                if (idleRejections > 0) {
                    annotation.put(IDLE_REJECTIONS_FIELD, idleRejections);
                }
                annotation.put(EXEMPTED_FIELD, true);
                annotation.put(EXEMPTION_BASIS_FIELD, "验证确认「能力已存在」：" + confirmation);
                return annotation;
            }
            idleRejections++;
            throw new IllegalStateException(rejectionReceipt(idleRejections));
        }

        /** 拒绝回执（重试现场的携带物——执行体据此定位补做什么，两出路都点名）。 */
        private static String rejectionReceipt(int rejectionNo) {
            return "产出证明未过（第 " + rejectionNo
                    + " 次拒绝）：本段起点以来工作区零文件变更，且无「能力已存在」的"
                    + "验证确认——空转收口被拒。收口要件＝动了文件、或验证确认本片能力"
                    + "已在：本片功能尚未实现的请补实现（修改工作区文件）；活已被前片"
                    + "干完的请把本片验收要点委派 verify 子智能体验证，确认已存在的项"
                    + "经 report_verification 以 existing=true 登记。";
        }

        /** 段起点以来的 diff 探测（#405 裁决的唯一确定性信号）：探测不成降级放行。 */
        private boolean hasWorkspaceDiff() {
            try {
                ExecResultResponse result = workspaceLifecycle.exec(
                        Long.toString(workspaceId),
                        new WorkspaceExecCommand(
                                WorkspaceVersions.diffSinceStartCommand(segmentStart)));
                if (result.exitCode() != 0) {
                    log.warn("[proof] 工作区 {} 产出证明探针失败（降级放行）：{}",
                            workspaceId, result.stderr());
                    return true;
                }
                return !result.stdout().isBlank();
            }
            catch (RuntimeException e) {
                log.warn("[proof] 工作区 {} 产出证明探针异常（降级放行）：{}",
                        workspaceId, e.toString());
                return true;
            }
        }
    }
}
