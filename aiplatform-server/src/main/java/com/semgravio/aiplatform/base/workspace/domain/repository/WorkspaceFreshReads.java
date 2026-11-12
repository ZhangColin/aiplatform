package com.semgravio.aiplatform.base.workspace.domain.repository;

import java.util.Optional;

import com.semgravio.aiplatform.base.workspace.domain.aggregate.Workspace;

/**
 * 工作区独立读缝（#476，形制照 metering 的 UsageEventAggregations：domain 端口 +
 * infrastructure 适配器直注，不走 Spring Data 派生）：每次调用都是一次<b>独立的
 * 已提交读</b>——绕开调用方持久化上下文（一级缓存）与在途事务快照，专供「等待
 * 外部翻转」的轮询面（{@code WorkspaceReadinessWaiter}）消费。常规装载仍走
 * {@link WorkspaceRepository#findById}（享受一级缓存，不付独立读成本）。
 * 全史与机理见 CONTEXT.md「独立读」词条（正本）。
 */
public interface WorkspaceFreshReads {

    /**
     * 独立读最新已提交状态（detached 聚合，脱离调用方持久化上下文）；不存在返回空。
     * 供轮询面消费，勿用于常规装载。
     */
    Optional<Workspace> findFreshById(Long id);
}
