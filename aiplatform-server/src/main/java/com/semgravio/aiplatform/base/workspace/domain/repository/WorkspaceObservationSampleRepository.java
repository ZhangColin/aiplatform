package com.semgravio.aiplatform.base.workspace.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.WorkspaceObservationSample;

/**
 * 占用观测采样仓储（{@code wsp_observation_samples}，#487）：写口唯一在
 * {@code WorkspaceObservationSamplingAppService}（扫描轮旁路采样），读口在
 * 观测用例（{@code WorkspaceObservationAppService} 纯读库零 docker）。
 */
public interface WorkspaceObservationSampleRepository
        extends BaseRepository<WorkspaceObservationSample, Long> {
}
