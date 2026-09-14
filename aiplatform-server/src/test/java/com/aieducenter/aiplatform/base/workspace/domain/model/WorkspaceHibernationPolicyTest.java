package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.Workspace;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闲置休眠判定（#171，ADR-0016）：纯函数直测——入参 last-touch / now / run 在途 /
 * 期望态全由测试显式给定，不依赖时钟魔法（now 是参数不是环境）。
 */
class WorkspaceHibernationPolicyTest {

    private static final Duration THRESHOLD = Duration.ofMinutes(60);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 12, 0);

    /** 闲置 = last-touch 逾阈值（严格超过；恰好达阈值还算在用）。 */
    @Test
    void given_touch_beyond_threshold_when_is_idle_then_true() {
        assertThat(WorkspaceHibernationPolicy.isIdle(
                NOW.minus(THRESHOLD).minusSeconds(1), NOW, THRESHOLD)).isTrue();
    }

    @Test
    void given_touch_within_threshold_when_is_idle_then_false() {
        assertThat(WorkspaceHibernationPolicy.isIdle(
                NOW.minus(THRESHOLD), NOW, THRESHOLD)).isFalse();
        assertThat(WorkspaceHibernationPolicy.isIdle(
                NOW.minus(THRESHOLD).plusSeconds(1), NOW, THRESHOLD)).isFalse();
    }

    /** 闲置 + 期望运行 + 无 run 在途 → 休眠。 */
    @Test
    void given_idle_running_no_run_when_should_hibernate_then_true() {
        assertThat(WorkspaceHibernationPolicy.shouldHibernate(
                NOW.minusHours(2), NOW, false, DesiredState.RUNNING, THRESHOLD)).isTrue();
    }

    /** 阈值内触碰过 → 不动（活跃项目永不休眠）。 */
    @Test
    void given_recent_touch_when_should_hibernate_then_false() {
        assertThat(WorkspaceHibernationPolicy.shouldHibernate(
                NOW.minusMinutes(10), NOW, false, DesiredState.RUNNING, THRESHOLD)).isFalse();
    }

    /** 生成 run 进行中恒活跃——即使超阈值也不休眠。 */
    @Test
    void given_run_in_flight_when_should_hibernate_then_false() {
        assertThat(WorkspaceHibernationPolicy.shouldHibernate(
                NOW.minusHours(8), NOW, true, DesiredState.RUNNING, THRESHOLD)).isFalse();
    }

    /** 已休眠/已封存的期望态不再休眠（幂等；封存归 #172）。 */
    @Test
    void given_not_running_desired_state_when_should_hibernate_then_false() {
        assertThat(WorkspaceHibernationPolicy.shouldHibernate(
                NOW.minusHours(8), NOW, false, DesiredState.HIBERNATED, THRESHOLD)).isFalse();
        assertThat(WorkspaceHibernationPolicy.shouldHibernate(
                NOW.minusHours(8), NOW, false, DesiredState.SEALED, THRESHOLD)).isFalse();
    }

    /** 判定只吃参数：同一入参在任意时刻调用结论一致（聚合字段即入参源）。 */
    @Test
    void given_workspace_last_touch_when_policy_reads_only_inputs_then_decides() {
        Workspace workspace = Workspace.registerPending(
                WorkspaceId.of("42"), EnvKind.DEV);
        workspace.markTouched(NOW.minusHours(3));

        assertThat(WorkspaceHibernationPolicy.shouldHibernate(workspace.getLastTouchAt(),
                NOW, false, workspace.getDesiredState(), THRESHOLD)).isTrue();
    }

    // ---------- 封存判定（#172） ----------

    private static final Duration SEAL_THRESHOLD = Duration.ofDays(30);

    /** 休眠满期：闲置逾「闲置阈值＋封存阈值」且期望休眠 → 封存。 */
    @Test
    void given_hibernated_beyond_seal_threshold_when_should_seal_then_true() {
        assertThat(WorkspaceHibernationPolicy.shouldSeal(
                NOW.minus(THRESHOLD).minus(SEAL_THRESHOLD).minusSeconds(1),
                NOW, false, DesiredState.HIBERNATED, THRESHOLD, SEAL_THRESHOLD)).isTrue();
    }

    /** 恰好达阈值 = 未满期（严格超过口径与闲置一致）。 */
    @Test
    void given_hibernated_exactly_at_seal_threshold_when_should_seal_then_false() {
        assertThat(WorkspaceHibernationPolicy.shouldSeal(
                NOW.minus(THRESHOLD).minus(SEAL_THRESHOLD),
                NOW, false, DesiredState.HIBERNATED, THRESHOLD, SEAL_THRESHOLD)).isFalse();
    }

    /** 封存只发生在休眠态上：运行/封存的期望态绝不封存。 */
    @Test
    void given_running_or_sealed_desired_state_when_should_seal_then_false() {
        assertThat(WorkspaceHibernationPolicy.shouldSeal(
                NOW.minusDays(60), NOW, false, DesiredState.RUNNING, THRESHOLD, SEAL_THRESHOLD))
                .isFalse();
        assertThat(WorkspaceHibernationPolicy.shouldSeal(
                NOW.minusDays(60), NOW, false, DesiredState.SEALED, THRESHOLD, SEAL_THRESHOLD))
                .isFalse();
    }

    /** run 在途恒活跃——已休眠满期也不动手（封存绝不对活跃项目动手）。 */
    @Test
    void given_run_in_flight_when_should_seal_then_false() {
        assertThat(WorkspaceHibernationPolicy.shouldSeal(
                NOW.minusDays(60), NOW, true, DesiredState.HIBERNATED,
                THRESHOLD, SEAL_THRESHOLD)).isFalse();
    }
}
