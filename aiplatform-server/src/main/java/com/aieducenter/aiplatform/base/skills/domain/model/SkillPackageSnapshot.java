package com.aieducenter.aiplatform.base.skills.domain.model;

import java.util.List;

/**
 * 一次安装拉取的仓库快照（#248 快照安装，ADR-0021）：clone 时刻的整体固化
 * 物——版本标识（装时 HEAD commit，快照锚）＋全部解析态技能。快照即上限：
 * 不带回拨、不挂远端句柄（订阅式自动同步已否决；远端后续前进走 T4 显式更新）。
 *
 * <p>来源包标识不入快照：它是调用方（安装用例）对仓库 URL 的规范化身份，
 * 非拉取产物——同源去重据此先行，不必等 clone。</p>
 *
 * @param version 版本标识（装时 HEAD commit 全 SHA）
 * @param skills  解析态技能清单（仓库内排序稳定——按 SKILL.md 路径序）
 */
public record SkillPackageSnapshot(
        String version,
        List<ParsedSkill> skills) {
}
