package com.semgravio.aiplatform.base.knowledge.domain.repository;

import java.util.List;

import com.semgravio.aiplatform.base.knowledge.domain.model.ExperienceDistillationRecord;
import com.semgravio.aiplatform.base.knowledge.domain.model.ProjectDistillationStats;

/**
 * 事后提炼处理记录存取（#641，{@code knw_experience_distillations} 单表，JdbcTemplate
 * 原生 SQL、接口在 domain/repository、实现在 infrastructure）。
 *
 * <p>写入用 upsert（唯一键 project_id + version_no）：重复触发同一版本幂等，失败行
 * 在同一版本重试时原地刷新为新结果；不抛 DuplicateKeyException（Postgres 唯一冲突
 * 会让事务失效，upsert 一次语句收口）。游标＝最近一条非失败记录的版本号。</p>
 */
public interface ExperienceDistillationStore {

    /**
     * 游标：该项目最近一条<b>非失败</b>记录的版本号。
     *
     * @return 无记录返回 null（首次处理）
     */
    Integer latestNonFailedVersionNo(long projectId);

    /**
     * 按项目 + 版本号直读（重复触发幂等判定与失败重试入口）。
     *
     * @return 查无返回 null
     */
    ExperienceDistillationRecord find(long projectId, int versionNo);

    /**
     * 落库/覆盖：同一项目同一版本插入或原地更新（含状态、计数、批次、错误摘要）。
     */
    void upsert(ExperienceDistillationRecord record);

    /**
     * 飞轮提炼统计（#643 只读聚合）：按项目汇总修改轮数（版本号 &gt; 1 的记录条数）
     * 与没做对轮数（修改轮中 missed_count &gt; 0 的记录条数），项目名取最新一条
     * 记录的快照。无记录的项目不出现在结果里（读面按 0 呈现）。
     */
    List<ProjectDistillationStats> projectStats();
}
