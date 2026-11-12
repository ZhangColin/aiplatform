package com.semgravio.aiplatform.base.workspace.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceLostMark;

/**
 * 在册失联标记仓储（{@code wsp_lost_marks}，#488）：写口唯一在
 * {@code WorkspaceDetachmentAppService}（休眠扫描轮末尾分级处置），读口在观测
 * 用例（{@code WorkspaceObservationAppService} 拼装 lostAt 清单列）。
 */
public interface WorkspaceLostMarkRepository extends BaseRepository<WorkspaceLostMark, Long> {
}
