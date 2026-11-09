package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.model.AgentProfile;

/**
 * 产出证明闸（#420 空转片防呆）判定件：收口判据增补「产出证明」——本段起点以来
 * 工作区变更 diff＞0、或验证确认「能力已存在」，二者必居一（平台侧判定，不经
 * 模型自报；diff＝唯一确定性信号）。零 diff＋无确认＝结构性拒绝（拒绝回执喂回
 * 执行体同 run 补做）；豁免收口与空转拒绝史均出判别标注（随收尾卡如实呈现）。
 */
class ProofOfOutputGateTest {

    private static final String FAKE_SEGMENT_START = "a1b2c3d4e5f6";

    private final WorkspaceLifecycleAppService workspaceLifecycle =
            Mockito.mock(WorkspaceLifecycleAppService.class);
    private final VerificationFacts verificationFacts = new VerificationFacts();
    private final AgentConfigAppService agentConfigs = Mockito.mock(AgentConfigAppService.class);
    private final ProofOfOutputGate gate = new ProofOfOutputGate(workspaceLifecycle,
            verificationFacts, agentConfigs);
    /** diff 探针脚本位（测试中随时改写：空＝零 diff 空转；有文件＝干了活）。 */
    private final AtomicReference<String> diffStdout = new AtomicReference<>("");

    /** 闸态装配桩：true＝闸开（#435 起为运营切回旧行为）、false＝闸关（#435 缺省态）。 */
    private void givenGateEnabled(boolean enabled) {
        when(agentConfigs.effectiveOf(AgentProfile.EXECUTOR)).thenReturn(
                new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                        true, true, false, true, null, null, null, null, 3, 2,
                        true, enabled, true));
    }

    /** 每例闸开先行（#435 起闸缺省关——闸开行为面由本桩显式承载）。 */
    @org.junit.jupiter.api.BeforeEach
    void defaultGateOpen() {
        givenGateEnabled(true);
    }

    /** 段起点捕获可得＋diff 探针读脚本位（一次打桩，测试经 {@link #diffStdout} 改写）。 */
    private void givenSegmentStartCapturedAndDiffScripted() {
        when(workspaceLifecycle.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            if (cmd == null) {
                return new ExecResultResponse("", "", 0);
            }
            if (cmd.command().contains("git rev-parse")) {
                return new ExecResultResponse(FAKE_SEGMENT_START + "\n", "", 0);
            }
            if (cmd.command().contains("git diff --name-only")) {
                return new ExecResultResponse(diffStdout.get(), "", 0);
            }
            return new ExecResultResponse("", "", 0);
        });
    }

    @Test
    void given_zero_diff_and_no_confirmation_when_proof_then_rejected_with_receipt() {
        givenSegmentStartCapturedAndDiffScripted();
        diffStdout.set("");

        ProofOfOutputGate.Checking checking = gate.forRun(42L);

        // 结构性拒绝：回执点名两出路（补实现／补验证确认），供重试现场喂回执行体
        assertThatThrownBy(checking::proof)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("产出证明未过")
                .hasMessageContaining("第 1 次")
                .hasMessageContaining("补实现")
                .hasMessageContaining("能力已存在");
    }

    @Test
    void given_rejection_then_work_done_when_proof_then_annotation_carries_history() {
        givenSegmentStartCapturedAndDiffScripted();
        diffStdout.set("");
        ProofOfOutputGate.Checking checking = gate.forRun(42L);
        assertThatThrownBy(checking::proof).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(checking::proof).isInstanceOf(IllegalStateException.class);

        // 补做完成（探针见文件）——正常收口，拒绝史如实入标注（空转拒绝 2 次）
        diffStdout.set("src/app/guestbook/page.tsx\n");
        Map<String, Object> annotation = checking.proof();

        assertThat(annotation).containsEntry(ProofOfOutputGate.IDLE_REJECTIONS_FIELD, 2);
    }

    @Test
    void given_work_done_when_proof_then_no_annotation() {
        // 真·薄片不误伤：diff＞0 即有产出证明——无事可标（null＝收尾卡不携带）
        diffStdout.set("src/app/page.tsx\n");

        assertThat(gate.forRun(42L).proof()).isNull();
    }

    @Test
    void given_confirmation_when_zero_diff_then_exempt_close_with_basis() {
        // 豁免路径：活已被前片干完的薄片——验证确认「能力已存在」即合法收口，
        // 豁免依据（确认项名）随标注落收尾卡
        givenSegmentStartCapturedAndDiffScripted();
        diffStdout.set("");
        verificationFacts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", true, null, true),
                new VerificationFacts.Check("首页可访问", "browser", true, null)));

        Map<String, Object> annotation = gate.forRun(42L).proof();

        assertThat(annotation)
                .containsEntry(ProofOfOutputGate.EXEMPTED_FIELD, true)
                .containsEntry(ProofOfOutputGate.EXEMPTION_BASIS_FIELD,
                        "验证确认「能力已存在」：留言提交");
    }

    @Test
    void given_rejection_then_confirmation_when_proof_then_annotation_carries_both() {
        // 拒绝史与豁免依据并存：先被拒（未验证）、补验证确认后豁免收口——两者如实
        givenSegmentStartCapturedAndDiffScripted();
        diffStdout.set("");
        ProofOfOutputGate.Checking checking = gate.forRun(42L);
        assertThatThrownBy(checking::proof).isInstanceOf(IllegalStateException.class);
        verificationFacts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", true, null, true)));

        Map<String, Object> annotation = checking.proof();

        assertThat(annotation)
                .containsEntry(ProofOfOutputGate.IDLE_REJECTIONS_FIELD, 1)
                .containsEntry(ProofOfOutputGate.EXEMPTED_FIELD, true);
    }

    @Test
    void given_segment_start_uncaptured_when_proof_then_gate_skipped() {
        // 段起点捕获不成（环境故障）＝闸降级跳过——同「无回滚基准不重跑」取舍：
        // 环境故障不是空转片，不拦正常收口
        when(workspaceLifecycle.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));

        assertThat(gate.forRun(42L).proof()).isNull();
    }

    @Test
    void given_probe_failing_when_proof_then_fail_open() {
        // 探针 exec 失败＝探测不成（环境故障非空转）——降级放行，如实记日志
        when(workspaceLifecycle.exec(any(), any())).thenAnswer(invocation -> {
            WorkspaceExecCommand cmd = invocation.getArgument(1);
            if (cmd.command().contains("git rev-parse")) {
                return new ExecResultResponse(FAKE_SEGMENT_START + "\n", "", 0);
            }
            if (cmd.command().contains("git diff --name-only")) {
                return new ExecResultResponse("", "fatal: bad object", 128);
            }
            return new ExecResultResponse("", "", 0);
        });

        assertThat(gate.forRun(42L).proof()).isNull();
    }

    // ---------- 闸开关化（#433 开关承载面；#435 翻转缺省＝闸关；闸开＝零 diff 也拒） ----------

    @Test
    void given_gate_closed_when_zero_diff_then_passes_without_probe() {
        // 闸关（#432 砍验证税开关态）：段起点以来零 diff 且无确认——空转片也放行，
        // 不抛拒绝回执（无重跑税）；段起点探针不跑（exec 桩全空也稳过）
        givenGateEnabled(false);
        when(workspaceLifecycle.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));

        assertThat(gate.forRun(42L).proof()).isNull();
    }

    @Test
    void given_gate_closed_then_no_idle_rejection_history_accumulates() {
        // 闸关下多次 proof() 均放行且不累计空转拒绝史（拒绝史只属闸开语义）
        givenGateEnabled(false);
        when(workspaceLifecycle.exec(any(), any()))
                .thenReturn(new ExecResultResponse("", "", 0));
        ProofOfOutputGate.Checking checking = gate.forRun(42L);

        assertThat(checking.proof()).isNull();
        assertThat(checking.proof()).isNull();
    }
}
