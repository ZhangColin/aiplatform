package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignItem;

/**
 * 设计轨道件行仓储（#289 设计轨道表）。读面 = 项目当前件集按 ord 升序
 * （1..N＝清单条目序）；写面 = 清单落库/重产的整组替换（删除编排事务，
 * 同对话史惯例）与件状态落位。
 */
public interface DesignItemRepository extends BaseRepository<DesignItem, Long> {

    /** 项目当前轨道件集（ord 升序；空 = 无清单——表即「有无清单」的事实源）。 */
    List<DesignItem> findByProjectIdOrderByOrdAsc(Long projectId);

    /** 单件寻址（状态落位的目标行）。 */
    Optional<DesignItem> findByProjectIdAndOrd(Long projectId, int ord);

    /** 清单重产的整组替换清理（也供项目删除级联——软引用显式清，同对话史惯例）。 */
    void deleteByProjectId(Long projectId);
}
