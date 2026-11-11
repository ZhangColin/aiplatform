package com.semgravio.aiplatform.base.knowledge.domain.repository;

import java.util.List;

import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftQueueResult;
import com.semgravio.aiplatform.base.knowledge.domain.model.SedimentDraftRecord;

/**
 * 沉淀草稿存取（#460 数据飞轮采集层，{@code knw_sediment_drafts} 单表，照
 * {@link com.semgravio.aiplatform.base.skills.domain.repository.SkillDraftStore}
 * 先例：JdbcTemplate 原生 SQL、接口在 domain/repository、实现在 infrastructure）。
 * 草稿是平台资产暂存态（「数据标注」人审队列），无用户会话语境。在途上限并发兜底
 * ＝在途部分唯一索引（应用层先查后插回执原因，索引拦并发窗）。
 *
 * <p>#460 自荐写入链与项目删除清理＋#464 审结读面（队列/详情）与三键 CAS 翻态
 * （激活/拒绝/改标签）——升格 knw_materials 由应用服务经 KnowledgeAppService
 * 完成（#465），本口不触素材表。</p>
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
     * 标注队列（#464「数据标注」面读口）：三过滤维度可组合、均可缺省（缺省＝
     * 全量）；排序定死＝自荐时间倒序（id 倒序稳定）；SQL 侧 LIMIT/OFFSET 分页
     * （offset 由框架 Pagination 钳制换算）。
     */
    SedimentDraftQueueResult searchQueue(Long projectId, String kindKey,
            SedimentDraftStatus status, int offset, int limit);

    /**
     * 改标签的占用查腿（#464）：目标类别在途序号占用清单，排除自身行（自改自的
     * 序号不算占用）——应用层据此取目标类别最低空位并判在途上限。
     */
    List<Integer> findPendingSlotsExcluding(long excludeId, long projectId, String kindKey);

    /**
     * 激活 CAS（#464）：在途行翻已激活＋操作者两列＋审结时刻。零行命中＝并发审结
     * 抢先（行已终态），由调用方定 409 语义。
     */
    boolean markActivated(long id, Operator operator);

    /**
     * 拒绝 CAS（#464）：在途行翻已拒绝＋理由＋操作者两列＋审结时刻（理由必填由
     * 应用层预检＋库级 CHECK 双锁）。零行命中同 {@link #markActivated}。
     */
    boolean markRejected(long id, String reason, Operator operator);

    /**
     * 改标签 CAS（#464）：在途行翻目标类别＋目标类别最低空位在途序号（占用目标
     * 类别在途名额；部分唯一索引拦并发窗，DuplicateKeyException 由调用方归一）。
     * 零行命中同 {@link #markActivated}。
     */
    boolean updateKind(long id, String kindKey, int inflightSlot);

    /**
     * 项目删除清理口（#460，对齐技能草稿清理先例——软引用显式清非库级级联）：
     * 只清该项目的<b>在途</b>行（未审内容无可追责面，血统不留悬空——采集层不留
     * 僵尸行）；终态留档不随删（审结事实独立于项目存续）。单语句原子；失败如实
     * 上抛（store 存取口房规，调用方定编排降级与否）。
     */
    void deletePendingByProject(long projectId);
}
