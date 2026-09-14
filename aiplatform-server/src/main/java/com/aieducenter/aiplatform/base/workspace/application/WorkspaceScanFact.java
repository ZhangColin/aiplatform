package com.aieducenter.aiplatform.base.workspace.application;

/**
 * 工作区扫描事实（#171）：休眠扫描的调用方（项目域驱动器）对本 BC 消费方状态的
 * 申报——base.workspace 不反向依赖 business，跨 BC 事实由组合方随扫描一并递入。
 *
 * <ul>
 *   <li>{@code runInFlight}：该项目有生成/修正 run 在途（恒活跃——工作区正被
 *       run 执行体读写，即使闲置超阈值也不休眠）；</li>
 *   <li>{@code startAppOnWake}：已生成项目（漂移收敛唤醒后拉起 8081 应用；从未
 *       生成的工作区恢复到未生成态，不拉）。</li>
 * </ul>
 *
 * <p>归档与休眠正交（ADR-0016）：归档项目走同一判定，调用方不筛归档。</p>
 *
 * @param runInFlight    生成 run 是否在途
 * @param startAppOnWake 唤醒后是否拉起应用（已生成）
 */
public record WorkspaceScanFact(boolean runInFlight, boolean startAppOnWake) {

    /** 无消费方申报时的缺省事实：无 run 在途、未生成（防御——今日工作区恒有项目）。 */
    public static final WorkspaceScanFact ABSENT = new WorkspaceScanFact(false, false);
}
