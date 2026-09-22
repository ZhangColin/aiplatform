package com.aieducenter.aiplatform.base.skills.domain.repository;

import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.skills.domain.enums.SkillSlot;
import com.aieducenter.aiplatform.base.skills.domain.enums.SkillStatus;
import com.aieducenter.aiplatform.base.skills.domain.model.Operator;
import com.aieducenter.aiplatform.base.skills.domain.model.ParsedSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillUpdateTrace;

/**
 * 技能库存取（条目表 {@code skl_skills}＋指派表 {@code skl_slot_assignments}＋
 * 包表 {@code skl_packages}＋更新留痕表 {@code skl_update_traces}）：技能是
 * 平台资产非用户数据，无用户会话语境，JdbcTemplate 原生 SQL 足矣（照
 * {@code KnowledgeStore} 先例：接口在 domain/repository，实现在 infrastructure，
 * 不挂 Spring Data 仓储）。T1 读侧；写口（#248 安装/启停/卸载）与指派（#249
 * 槽位读写）在此扩展——事务由应用层 {@code @Transactional} 界定（安装批量插入
 * 原子，失败整体回滚）。
 */
public interface SkillStore {

    /**
     * 全量清单（#247 管理读面）：技能库是有界目录（装什么是运营决策），不分页
     * 不过滤；排序来源包、名称（跨包同名列区分呈现，同包技能聚簇审阅）。
     * 行含「有新版」标记（LEFT JOIN 包表，null＝未检查过）。
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

    // ========== 槽位指派（#249，skl_slot_assignments） ==========

    /**
     * 槽位指派读面（管理面）：该槽位全部已指派条目，<b>含停用行</b>——运营要
     * 看到「指派了但已停用」的实态（启停是可逆开关，指派关系随行保留）；排序
     * 同清单（来源包、名称）。
     */
    List<SkillRecord> findAssigned(SkillSlot slot);

    /**
     * 槽位装配合成视图（#249 动态查库，ADR-0021 生效语义）：该槽位<b>已指派且
     * 启用</b>的条目——装配缝每轮重查本口，指派/启停变更下一轮自然反映（非装配
     * 时固化快照）；停用即退出装配候选。
     */
    List<SkillRecord> findEnabledAssigned(SkillSlot slot);

    /**
     * 槽位指派整包替换（#249 PUT 全量语义）：该槽位行集 delete＋insert 同事务
     * 原子完成（空清单＝清空该槽位），操作者两列＝最近动作者；id 集的解析与
     * 存在性校验由调用方先行（应用层守卫）。
     */
    void replaceAssignments(SkillSlot slot, List<Long> skillIds, Operator operator);

    /**
     * 指派在身检查（#248 卸载守卫的指派面）：该条目在任一槽位有指派行即 true
     * （拒绝卸载 SKL_008——先解绑再卸）。
     */
    boolean existsAssignmentForSkill(long skillId);

    // ========== 更新检查与显式更新（#250，skl_packages／skl_update_traces） ==========

    /**
     * 全部已装来源包的现行版本（#250 更新检查遍历面）：来源包 → 装时/最近更新
     * 版本（同包行版本本应均一，取 MAX 兜底混态）。空库返回空 Map——扫描轮即
     * 无事可查。
     */
    Map<String, String> findInstalledVersions();

    /**
     * 装时排除名单读回（#250 显式更新重拉口径）：更新须与装时同排除语义，否则
     * 装时排除的目录会随更新还魂。包行不在（T4 前存量安装）返回空清单——注意
     * 存量装时若真用过排除段则无从读回（当时未持久化），空名单重拉会让该等
     * 目录还魂：运营对存量包首更后照清单核对（条目多出即重卸重装收口）。
     */
    List<String> findExcludeDirs(String sourcePackage);

    /**
     * 安装落包行（#250 与条目同事务）：排除名单持久化＋检查态初始化（remote_
     * head＝装时版本——安装即一次事实上的检查，标记复位 false）。upsert 语义：
     * 全卸后的孤儿包行被重装时整体重置。
     */
    void installPackage(String sourcePackage, List<String> excludeDirs, String headVersion);

    /**
     * 检查结果落包行（#250 扫描轮与显式更新共用写口）：remote_head＋update_
     * available＋checked_at 同写。upsert 语义：T4 前存量安装无包行时按需补建
     * （排除名单空）；已存在的排除名单不被动（冲突分支只更新检查三列）。
     * 单语句原子，无需外层事务。
     */
    void recordCheckResult(String sourcePackage, String remoteHead, boolean updateAvailable);

    /**
     * 包行清理（#250 卸载顺带）：该来源包条目已全部卸净时删除包行（检查态随
     * 之清——无行可查即无状态可留）；仍有条目在库则不动。
     */
    void deletePackageIfNoSkills(String sourcePackage);

    /**
     * 来源包条目集（#250 显式更新寻址面）：该包全部条目按名称序；空集＝未安装
     * （调用方定 404 SKL_012）。
     */
    List<SkillRecord> findBySourcePackage(String sourcePackage);

    /**
     * 同名行原地翻新（#250 显式更新）：description/frontmatter/content/version
     * 换新快照值＋操作者两列＝更新者；id 与 status 不动（TSID 柄稳定——指派关系
     * 与启停状态跨更新保留）。
     */
    void refreshFromSnapshot(long id, ParsedSkill skill, String version, Operator operator);

    /**
     * 显式更新留痕追加（#250，append-only）：from→to 版本＋操作者一行。
     */
    void insertTrace(SkillUpdateTrace trace);

    /**
     * 更新留痕读面（#250 历史版本可查）：该来源包全部留痕按时间倒序（最近先）。
     */
    List<SkillUpdateTrace> findTraces(String sourcePackage);
}
