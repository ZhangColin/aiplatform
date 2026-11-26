package com.semgravio.aiplatform.base.knowledge.domain.model;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.base.knowledge.domain.enums.ExperienceDistillationStatus;

/**
 * 事后提炼处理记录（#641，{@code knw_experience_distillations} 单行）：每个用户面
 * 版本一条——项目（含名称快照）、版本号、提交哈希、状态（已提炼／无修改意见／
 * 失败）、窗口内修改意见条数、判定「需求写明了但没做对」条数、落待审候选条数、
 * 提炼批次标识与错误摘要。同一项目同一版本唯一（重复触发幂等）；游标＝最近一条
 * 非失败记录的版本号（失败不前进）。
 *
 * @param id             TSID（记录寻址柄）
 * @param projectId      项目（软引用无 FK）
 * @param projectName    项目名快照（项目改名/删除后统计读面仍可读）
 * @param versionNo      用户面版本号（v1/v2/…）
 * @param commitHash     来源提交哈希（版本读取失败的历史失败行可空）
 * @param status         处理状态
 * @param opinionCount   本次窗口内修改意见条数
 * @param missedCount    判定「需求写明了但没做对」的条数
 * @param candidateCount 实际落待审的候选条数
 * @param batchId        提炼批次标识（候选行同值互溯）
 * @param errorSummary   失败摘要（失败态携带）
 * @param createdAt      创建时刻
 */
public record ExperienceDistillationRecord(
        long id,
        long projectId,
        String projectName,
        int versionNo,
        String commitHash,
        ExperienceDistillationStatus status,
        int opinionCount,
        int missedCount,
        int candidateCount,
        String batchId,
        String errorSummary,
        LocalDateTime createdAt) {
}
