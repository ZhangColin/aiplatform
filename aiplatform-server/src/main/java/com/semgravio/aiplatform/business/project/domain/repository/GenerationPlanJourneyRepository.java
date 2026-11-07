package com.semgravio.aiplatform.business.project.domain.repository;

import java.util.List;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.project.domain.aggregate.GenerationPlanJourney;

/**
 * 计划核心旅程仓储（#423 切片计划结构化输出扩展）。读面 = 项目当前旅程集按
 * ord 升序（锚一致性由读方与片行同口径判定）；写面 = 计划落库/重产的整组替换
 * （删除编排事务，同轨道片行惯例）。
 */
public interface GenerationPlanJourneyRepository extends BaseRepository<GenerationPlanJourney, Long> {

    /** 项目当前旅程集（ord 升序；空 = 无旅程——存量计划未设）。 */
    List<GenerationPlanJourney> findByProjectIdOrderByOrdAsc(Long projectId);

    /** 计划重产的整组替换清理（也供项目删除级联——软引用显式清，同轨道片行惯例）。 */
    void deleteByProjectId(Long projectId);
}
