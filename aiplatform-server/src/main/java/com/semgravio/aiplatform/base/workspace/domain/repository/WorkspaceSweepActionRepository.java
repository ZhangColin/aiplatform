package com.semgravio.aiplatform.base.workspace.domain.repository;

import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceSweepAction;

/**
 * 手动清扫留痕仓储（{@code wsp_sweep_actions}，#489）：写口唯一在
 * {@code WorkspaceResourceAppService}（后台 resources/sweep 每次触发一行），
 * 读口在资源面读模型（大盘呈现最近一次清扫）。
 */
public interface WorkspaceSweepActionRepository
        extends BaseRepository<WorkspaceSweepAction, Long> {

    /** 最近一次手动清扫（大盘「收编结果」面；时刻同值时取 id 大者——留痕序稳定）。 */
    Optional<WorkspaceSweepAction> findTopByOrderBySweptAtDescIdDesc();
}
