package com.aieducenter.aiplatform.base.skills.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;

/**
 * 技能库存取（条目表 {@code skl_skills}）：技能是平台资产非用户数据，无用户
 * 会话语境，JdbcTemplate 原生 SQL 足矣（照 {@code KnowledgeStore} 先例：接口在
 * domain/repository，实现在 infrastructure，不挂 Spring Data 仓储）。T1 只立
 * 读侧；写口（安装/启停/卸载，#248）后续在本接口扩展。
 */
public interface SkillStore {

    /**
     * 全量清单（#247 管理读面）：技能库是有界目录（装什么是运营决策），不分页
     * 不过滤；排序来源包、名称（跨包同名列区分呈现，同包技能聚簇审阅）。
     */
    List<SkillRecord> findAll();

    /**
     * 条目按 id 直读（#247 详情寻址：条目 id 即 URL 柄）。
     *
     * @return 查无返回 null，由调用方定 404 语义
     */
    SkillRecord find(long id);
}
