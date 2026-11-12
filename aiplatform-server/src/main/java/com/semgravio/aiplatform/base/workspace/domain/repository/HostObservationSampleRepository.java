package com.semgravio.aiplatform.base.workspace.domain.repository;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.HostObservationSample;

/**
 * 主机资源观测采样仓储（{@code wsp_host_observation}，#489）：全机一行（寻址恒
 * {@link HostObservationSample#SINGLETON_ID}）。写口唯一在
 * {@code WorkspaceObservationSamplingAppService}（#487 采样轮末尾主机帧），读口在
 * 资源面读模型（{@code WorkspaceResourceAppService} 纯读库零 docker）。
 */
public interface HostObservationSampleRepository
        extends BaseRepository<HostObservationSample, Long> {
}
