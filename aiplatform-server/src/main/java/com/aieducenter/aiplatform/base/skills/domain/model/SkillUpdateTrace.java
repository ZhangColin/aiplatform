package com.aieducenter.aiplatform.base.skills.domain.model;

import java.time.LocalDateTime;

/**
 * 显式更新版本留痕（#250，ADR-0021）：一次「重拉快照入库」的 from→to 版本事实
 * ＋操作者，append-only——历史版本可查（装时版本经链条可回溯）。更新永远显式点
 * （永不自动跟新），故每次更新必留一痕；库行卸载不删留痕（历史事实不随行消失）。
 *
 * @param id            留痕标识（TSID，更新用例生成）
 * @param sourcePackage 来源包标识（规范化仓库地址；与条目行同串）
 * @param fromVersion   更新前版本（装时/上次更新 commit）
 * @param toVersion     更新后版本（更新时刻远端 HEAD commit）
 * @param operatorId    更新操作者 id（显式动作必留痕）
 * @param operatorName  更新操作者名（直读展示）
 * @param createdAt     留痕时刻（＝更新动作时刻）
 */
public record SkillUpdateTrace(
        long id,
        String sourcePackage,
        String fromVersion,
        String toVersion,
        String operatorId,
        String operatorName,
        LocalDateTime createdAt) {
}
