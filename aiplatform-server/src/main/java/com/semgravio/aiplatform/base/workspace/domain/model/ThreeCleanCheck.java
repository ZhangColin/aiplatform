package com.semgravio.aiplatform.base.workspace.domain.model;

import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;

/**
 * 销毁三清校验事实（#485 校验面起算，#490 回收报告消费）：销毁尾部对容器/卷/
 * 注册行逐一复核的原始事实——三面各自「在否」＋探查成否。销毁通道（受理即删）
 * 只消费 WARN 观测面；一键回收通道（#490）据本事实组「四面/三面零残留」报告
 * （<b>UNKNOWN 不算清</b>——探查未成时各面清态恒 false，不冒充零残留）。
 *
 * @param probed            探查成否（false＝销毁级联未全成或探查自身失败，各面状态未知）
 * @param container         容器实态（四态直判；ABSENT＝清）
 * @param volumePresent     卷在否（false＝已清）
 * @param registryRowPresent 注册行在否（false＝已清）
 */
public record ThreeCleanCheck(boolean probed, ContainerState container,
        boolean volumePresent, boolean registryRowPresent) {

    /** 探查未成形（销毁级联失败/探查未成——三面状态未知，如实不冒充清）。 */
    private static final ThreeCleanCheck UNPROBED = new ThreeCleanCheck(
            false, ContainerState.UNKNOWN, true, true);

    /** 探查成形（调用方保证三面事实已取）。 */
    public static ThreeCleanCheck of(ContainerState container, boolean volumePresent,
            boolean registryRowPresent) {
        return new ThreeCleanCheck(true, container, volumePresent, registryRowPresent);
    }

    /** 探查未成形：销毁未全成/探查未成——各面清态恒 false（状态未知）。 */
    public static ThreeCleanCheck unprobed() {
        return UNPROBED;
    }

    /** 容器面清（ABSENT；UNKNOWN 不算清——探查失败≠容器不在）。 */
    public boolean containerClean() {
        return probed && container == ContainerState.ABSENT;
    }

    /** 卷面清。 */
    public boolean volumeClean() {
        return probed && !volumePresent;
    }

    /** 注册行面清。 */
    public boolean registryRowClean() {
        return probed && !registryRowPresent;
    }

    /** 三面全清（回收报告的零残留判据——任一面残留/未知即 false）。 */
    public boolean allClean() {
        return containerClean() && volumeClean() && registryRowClean();
    }
}
