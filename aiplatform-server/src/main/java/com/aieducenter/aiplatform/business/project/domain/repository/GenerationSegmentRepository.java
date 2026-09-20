package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.GenerationSegment;

/**
 * 生成轨道片行仓储（#220 生成轨道表）。读面 = 项目当前片集按 ord 升序
 * （0 = 阶段 0，1..N = 切片）；写面 = 计划落库/重产的整组替换（删除编排事务，
 * 同对话史惯例）与片状态落位。
 */
public interface GenerationSegmentRepository extends BaseRepository<GenerationSegment, Long> {

    /** 项目当前轨道片集（ord 升序；空 = 无计划——表即「有无计划」的事实源）。 */
    List<GenerationSegment> findByProjectIdOrderByOrdAsc(Long projectId);

    /** 有无片行（#222 四态投影的「生成中断」判据——有轨道事实而未生成不在途）。 */
    boolean existsByProjectId(Long projectId);

    /** 单片寻址（状态落位的目标行）。 */
    Optional<GenerationSegment> findByProjectIdAndOrd(Long projectId, int ord);

    /** 计划重产的整组替换清理（也供项目删除级联——软引用显式清，同对话史惯例）。 */
    void deleteByProjectId(Long projectId);
}
