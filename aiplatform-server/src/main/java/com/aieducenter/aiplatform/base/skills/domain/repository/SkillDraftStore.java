package com.aieducenter.aiplatform.base.skills.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
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

    /**
     * 草稿标已晋升（#262 T2 审结写口）：终态四件（状态/操作者两列/审结时刻）
     * 同写；{@code WHERE status = 在途} 的条件更新即 CAS——并发审结（另一管理
     * 员抢先晋升/拒绝）零行命中返 false，由调用方定冲突语义并回滚事务
     * （晋升事务内的技能库插入随之撤销）。
     */
    boolean markPromoted(long id, Operator operator);

    /**
     * 草稿标已拒绝（#262 T2 审结写口）：终态五件（状态/操作者两列/审结时刻/
     * 拒绝理由）同写；CAS 语义同 {@link #markPromoted}——拒绝是纯草稿侧写，
     * 无事务伴写，CAS 失败即整体无事发生。
     */
    boolean markRejected(long id, String reason, Operator operator);
}
