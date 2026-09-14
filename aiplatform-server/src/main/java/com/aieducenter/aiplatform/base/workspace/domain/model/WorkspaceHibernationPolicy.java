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
 * 封存不重复休眠）。归档与休眠正交（ADR-0016）：归档项目走同一判定，无特殊
 * 分支——判定根本不看归档。</p>
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
}
