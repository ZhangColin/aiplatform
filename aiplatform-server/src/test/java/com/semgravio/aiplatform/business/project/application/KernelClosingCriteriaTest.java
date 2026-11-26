package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;

/**
 * 生成内核收尾判据（#597，ADR-0054／CONTEXT.md「机器面唯一硬判据＝build ✓＋探活 ✓」）：
 * {@code pnpm build} 过＋8081 探活有响应才判完成——双件逐件记录（调用方按件呈现/
 * 重试），探活腿消费沙箱宿主现成探针件 {@link ClosingProbes}（8081 探活＝预览转
 * 可用的门）。本类为行为测试：exec 缝与探针件皆替身，只断「双件齐备才 complete」
 * 与「逐件结果如实记录」的外部行为。
 */
class KernelClosingCriteriaTest {

    private final WorkspaceLifecycleAppService workspaceLifecycle =
            Mockito.mock(WorkspaceLifecycleAppService.class);
    private final ClosingProbes closingProbes = Mockito.mock(ClosingProbes.class);
    private final KernelClosingCriteria criteria =
            new KernelClosingCriteria(workspaceLifecycle, closingProbes);

    private void givenBuildExitCode(int exitCode) {
        when(workspaceLifecycle.exec(eq("42"), any(WorkspaceExecCommand.class)))
                .thenAnswer(invocation -> {
                    WorkspaceExecCommand command = invocation.getArgument(1);
                    assertThat(command.command()).isEqualTo(KernelClosingCriteria.BUILD_PROBE);
                    return new ExecResultResponse("", "stderr", exitCode);
                });
    }

    @Test
    void given_build_passes_and_probe_responds_when_assess_then_complete() {
        givenBuildExitCode(0);

        KernelClosingCriteria.KernelClosingResult result = criteria.assess(42L);

        assertThat(result.buildPassed()).isTrue();
        assertThat(result.probePassed()).isTrue();
        assertThat(result.complete()).isTrue();
        verify(closingProbes).requireReachable(42L);
    }

    @Test
    void given_build_fails_when_assess_then_not_complete_and_probe_still_checked() {
        // build 红＝整体不完成，但探活照跑——逐件结果都要（收尾卡按件呈现）
        givenBuildExitCode(1);

        KernelClosingCriteria.KernelClosingResult result = criteria.assess(42L);

        assertThat(result.buildPassed()).isFalse();
        assertThat(result.probePassed()).isTrue();
        assertThat(result.complete()).isFalse();
        verify(closingProbes).requireReachable(42L);
    }

    @Test
    void given_probe_unreachable_when_assess_then_not_complete_and_build_recorded() {
        // 探活腿红（现成探针件抛 IllegalStateException）＝不完成；build 结果照实记录
        givenBuildExitCode(0);
        doThrow(new IllegalStateException("8081 不可达（curl 退出码 7）"))
                .when(closingProbes).requireReachable(42L);

        KernelClosingCriteria.KernelClosingResult result = criteria.assess(42L);

        assertThat(result.buildPassed()).isTrue();
        assertThat(result.probePassed()).isFalse();
        assertThat(result.complete()).isFalse();
    }

    @Test
    void given_both_fail_when_assess_then_not_complete() {
        givenBuildExitCode(2);
        doThrow(new IllegalStateException("8081 不可达"))
                .when(closingProbes).requireReachable(42L);

        KernelClosingCriteria.KernelClosingResult result = criteria.assess(42L);

        assertThat(result.buildPassed()).isFalse();
        assertThat(result.probePassed()).isFalse();
        assertThat(result.complete()).isFalse();
    }

    @Test
    void given_assess_when_runs_then_build_leg_executes_build_command_in_workspace() {
        // build 腿命令正本：容器内 /workspace pnpm build（工作区根固定、无用户可控片段）
        when(workspaceLifecycle.exec(eq("42"), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "", 0));

        criteria.assess(42L);

        ArgumentCaptor<WorkspaceExecCommand> captor =
                ArgumentCaptor.forClass(WorkspaceExecCommand.class);
        verify(workspaceLifecycle).exec(eq("42"), captor.capture());
        assertThat(captor.getValue().command()).isEqualTo(KernelClosingCriteria.BUILD_PROBE);
    }

    @Test
    void given_build_leg_errors_environmentally_when_assess_then_exception_propagates() {
        // exec 缝环境故障（容器不可达等）不是「build 红」——如实上抛，由调用方按
        // 失败面处置（不吞成判据红，免得把环境故障误呈现为「构建失败」）
        when(workspaceLifecycle.exec(eq("42"), any(WorkspaceExecCommand.class)))
                .thenThrow(new IllegalStateException("workspace 不可达"));

        assertThatThrownBy(() -> criteria.assess(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace 不可达");
        verify(closingProbes, never()).requireReachable(any());
    }
}
