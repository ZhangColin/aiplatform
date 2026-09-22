package com.aieducenter.aiplatform.base.skills.domain.port;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.model.SkillPackageSnapshot;

/**
 * 技能包快照拉取口（#248 快照安装＋#250 更新检查）：给定仓库地址一次性 clone＋
 * 解析全部 SKILL.md 为解析态快照（版本＝装时 HEAD commit）——拉完即弃，不留
 * 工作副本、不挂远端（订阅式自动同步已否决，ADR-0021）；远端前进检查走
 * {@link #remoteHead} 只读探查（同一否决决策的 HEAD 检查复用面）。
 *
 * <p>排除语义：{@code excludeDirs} 为目录段名单——技能的仓库相对路径任一段
 * 命中即不入快照（如 deprecated 类目目录）；含 {@code .git} 在内的隐藏目录
 * 结构性跳过。解析管道与内置目录同一（agentscope {@code SkillUtil}——审核面
 * 所见即运行时注入面）。</p>
 *
 * <p>失败语义（抛 {@code ApplicationException}）：仓库不可达/克隆失败/超时
 * SKL_004；SKILL.md 不合格（缺 name/description/正文）SKL_006——安装链路
 * fail-fast 整体不入库，畸形文件路径进日志。仓库可克隆但未解析到技能不为
 * 端口错误（返回空清单），由安装用例裁决 SKL_005。</p>
 */
public interface SkillPackageFetcher {

    /**
     * 拉取仓库快照：clone（浅历史即可，版本只需 HEAD）→ 解析全部（未排除的）
     * SKILL.md。
     *
     * @param repoUrl     仓库地址（已规范化的来源包身份，clone 与留痕同串）
     * @param excludeDirs 排除目录段名单（已归一：去空白、去重；可空）
     */
    SkillPackageSnapshot fetch(String repoUrl, List<String> excludeDirs);

    /**
     * 只读探查远端 HEAD（#250 更新检查）：{@code git ls-remote}——不 clone、
     * 不落任何本地物，仓库可达性失败即抛（SKL_004，与 fetch 同码同因：仓库
     * 不可达）。调用方（定期扫描）自持静默降级策略：捕获后留待下轮，不抛入
     * 运行面；显式更新链路不调本口（更新即重拉快照，版本自快照来）。
     *
     * @param repoUrl 仓库地址（已规范化的来源包身份）
     * @return 远端 HEAD commit 全 SHA
     */
    String remoteHead(String repoUrl);
}
