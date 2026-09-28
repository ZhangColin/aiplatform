package com.aieducenter.aiplatform.base.skills.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.model.SkillDraftRecord;

/**
 * 技能草稿存取（#259，{@code skl_skill_drafts} 单表，照 {@link SkillStore} 先例：
 * JdbcTemplate 原生 SQL、接口在 domain/repository、实现在 infrastructure）。草稿
 * 是平台资产暂存态（人审队列），无用户会话语境。撞名并发兜底＝在途部分唯一索引
 * （应用层先查后插回执原因，索引拦并发窗）。
 */
public interface SkillDraftStore {

    /**
     * 在途同名检查（撞名拒的草稿侧腿）：任一状态＝在途的行占名即 true——已拒绝
     * 草稿不占名（终态可重提为新草稿），已晋升草稿占名由技能库侧同名行承担。
     */
    boolean existsPendingByName(String name);

    /**
     * 草稿落库（自荐写入）：id/status/扫描回执由应用服务赋定；createdAt 库列
     * DEFAULT（插入忽略）。
     */
    void insert(SkillDraftRecord record);

    /**
     * 活跃面列表（管理读面）：在途草稿按时间倒序（最近先）——终态（已晋升/已拒绝）
     * 不列（T2 起草稿审核面只见待办；终态详情仍可按 id 查）。
     */
    List<SkillDraftRecord> findPending();

    /**
     * 按 id 直读（详情寻址，任意状态——终态留档可查）。
     *
     * @return 查无返回 null，由调用方定 404 语义
     */
    SkillDraftRecord find(long id);
}
