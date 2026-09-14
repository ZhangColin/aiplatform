package com.aieducenter.aiplatform.base.workspace.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.base.workspace.domain.aggregate.WorkspaceAction;

/**
 * 后台沙箱动作仓储（{@code wsp_workspace_actions}，append-only：只插入不更新）。
 */
public interface WorkspaceActionRepository extends BaseRepository<WorkspaceAction, Long> {
}
