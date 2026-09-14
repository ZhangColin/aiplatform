package com.aieducenter.aiplatform.base.workspace.domain.model;

import java.time.Duration;
import java.time.LocalDateTime;

import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;

/**
 * 闲置休眠判定（#171，ADR-0016）：纯函数——入参 last-touch / now / run 在途 /
 * 期望态，全部由调用方给定（时钟归应用层），直测不依赖时钟魔法。
 *
 * <p>闲置 = last-touch 严格逾阈值（恰好达阈值还算在用）；休眠 = 闲置 ∧ 无生成
 * run 在途（run 恒活跃——工作区正被 run 执行体读写）∧ 期望态为运行（已休眠/
 * 封存不重复休眠）。封存（#172）= 已休眠 ∧ 休眠满期（闲置时长逾「闲置阈值＋
 * 封存阈值」，即进入休眠后又满封存阈值）∧ 无 run 在途——封存只发生在休眠态上，
 * 绝不对运行中/唤醒中项目动手。归档与休眠正交（ADR-0016）：归档项目走同一判定，
 * 无特殊分支——判定根本不看归档。</p>
 */
public final class WorkspaceHibernationPolicy {

    private WorkspaceHibernationPolicy() {
    }

    /** 闲置探针：last-touch 逾阈值（严格超过；达阈值瞬间仍算在用）。 */
    public static boolean isIdle(LocalDateTime lastTouchAt, LocalDateTime now, Duration idleThreshold) {
        return now.isAfter(lastTouchAt.plus(idleThreshold));
    }

    /** 休眠判定：闲置 ∧ 无 run 在途 ∧ 期望运行。 */
    public static boolean shouldHibernate(LocalDateTime lastTouchAt, LocalDateTime now,
            boolean runInFlight, DesiredState desiredState, Duration idleThreshold) {
        return desiredState == DesiredState.RUNNING && !runInFlight && isIdle(lastTouchAt, now, idleThreshold);
    }

    /**
     * 封存判定（#172）：期望休眠 ∧ 无 run 在途 ∧ 休眠满期——闲置时长严格逾
     * {@code idleThreshold + sealThreshold}（休眠自闲置满 idleThreshold 起算，
     * 再满 sealThreshold 即「休眠满 30 天」口径）。
     */
    public static boolean shouldSeal(LocalDateTime lastTouchAt, LocalDateTime now,
            boolean runInFlight, DesiredState desiredState,
            Duration idleThreshold, Duration sealThreshold) {
        return desiredState == DesiredState.HIBERNATED && !runInFlight
                && now.isAfter(lastTouchAt.plus(idleThreshold).plus(sealThreshold));
    }
}
