package com.semgravio.aiplatform.base.workspace.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceReclaimAction;

/**
 * 一键回收动作留痕仓储（{@code wsp_reclaim_actions}，#490 append-only——只插入
 * 不写，读取面对账审计）。
 */
public interface WorkspaceReclaimActionRepository extends BaseRepository<WorkspaceReclaimAction, Long> {
}
