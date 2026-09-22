package com.aieducenter.aiplatform.base.skills.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;

/**
 * 技能库存取（条目表 {@code skl_skills}）：技能是平台资产非用户数据，无用户
 * 会话语境，JdbcTemplate 原生 SQL 足矣（照 {@code KnowledgeStore} 先例：接口在
 * domain/repository，实现在 infrastructure，不挂 Spring Data 仓储）。T1 读侧；
 * 写口（#248 安装/启停/卸载）在此扩展——事务由应用层 {@code @Transactional}
 * 界定（安装批量插入原子，失败整体回滚）。
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

    /**
     * 同源已装检查（#248 安装前置）：来源包身份（规范化仓库地址）已有库行即
     * 同源重复——安装前先行拦截，不必等 clone。
     */
    boolean existsBySourcePackage(String sourcePackage);

    /**
     * 安装批量落库（#248 快照安装）：一次装时快照的全部技能条目同事务原子入
     * 库（失败整体回滚）；id/status/操作者由安装用例赋定。
     */
    void insertAll(List<SkillRecord> records);

    /**
     * 状态翻转（#248 启停）：状态与操作者两列同写（操作者＝最近动作者），
     * updated_at 刷新；重复启停幂等（行恒在，由调用方先经 find 定 404）。
     */
    void updateStatus(long id, SkillStatus status, Operator operator);

    /**
     * 卸载删除（#248）：行删除即彻底出库（快照物，无「历史不漂移」约束）。
     *
     * @return 行存在并删除返回 true；查无返回 false（调用方定 404 语义）
     */
    boolean delete(long id);
}
