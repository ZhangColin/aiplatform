package com.semgravio.aiplatform.business.project.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.project.domain.aggregate.GenerationPlanDeferral;

/**
 * 计划推迟项仓储（ADR-0050 切片计划结构化推迟段）。读面 = 项目当前推迟集按
 * ord 升序（锚一致性由读方与片行同口径判定）；写面 = 计划落库/重产的整组替换
 * （删除编排事务，同轨道片行惯例）。
 */
public interface GenerationPlanDeferralRepository
        extends BaseRepository<GenerationPlanDeferral, Long> {

    /** 项目当前推迟集（ord 升序；空 = 无推迟项——存量计划未设/本期无显式推迟项）。 */
    List<GenerationPlanDeferral> findByProjectIdOrderByOrdAsc(Long projectId);

    /** 计划重产的整组替换清理（也供项目删除级联——软引用显式清，同轨道片行惯例）。 */
    void deleteByProjectId(Long projectId);
}
