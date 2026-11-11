package com.semgravio.aiplatform.base.knowledge.domain.repository;

import java.util.List;

import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;

/**
 * 沉淀草稿存取（#460 数据飞轮采集层，{@code knw_sediment_drafts} 单表，照
 * {@link com.semgravio.aiplatform.base.skills.domain.repository.SkillDraftStore}
 * 先例：JdbcTemplate 原生 SQL、接口在 domain/repository、实现在 infrastructure）。
 * 草稿是平台资产暂存态（「数据标注」人审队列），无用户会话语境。在途上限并发兜底
 * ＝在途部分唯一索引（应用层先查后插回执原因，索引拦并发窗）。
 *
 * <p>#460 只含自荐写入链与项目删除清理——「数据标注」审结读面（队列/详情/激活/
 * 拒绝/改标签）归 #464，本口届时扩展。</p>
 */
public interface SedimentDraftStore {

    /**
     * 在途序号占用清单（在途上限判定的查腿）：该项目该类别在途行的 inflight_slot
     * 列表——占用数＝在途数；应用层据此取最低空位（1-3）。
     */
    List<Integer> findPendingSlots(long projectId, String kindKey);

    /**
     * 草稿落库（自荐写入）：id/status/扫描回执/在途序号由应用服务赋定；createdAt
     * 库列 DEFAULT（插入忽略）。撞在途部分唯一索引（并发窗）抛
     * {@code DuplicateKeyException}，由应用层归一为上限回执。
     */
    void insert(SedimentDraftRecord record);

    /**
     * 按 id 直读（详情寻址，任意状态——终态留档可查；#464 审结面与清理缝共用）。
     *
     * @return 查无返回 null，由调用方定 404 语义
     */
    SedimentDraftRecord find(long id);

    /**
     * 项目删除清理口（#460，对齐技能草稿清理先例——软引用显式清非库级级联）：
     * 只清该项目的<b>在途</b>行（未审内容无可追责面，血统不留悬空——采集层不留
     * 僵尸行）；终态留档不随删（审结事实独立于项目存续）。单语句原子；失败如实
     * 上抛（store 存取口房规，调用方定编排降级与否）。
     */
    void deletePendingByProject(long projectId);
}
