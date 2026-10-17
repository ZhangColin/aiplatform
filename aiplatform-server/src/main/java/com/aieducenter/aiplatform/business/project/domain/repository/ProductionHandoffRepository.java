package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.ProductionHandoff;

/**
 * 生产承接仓储（#338）。
 */
public interface ProductionHandoffRepository extends BaseRepository<ProductionHandoff, Long> {

    /**
     * 项目名下的生产承接（「挂项目、只建一次」——project_id 唯一索引保证至多
     * 一行，本查询即 0/1 行）：开工判定建单的事务内判定面与六态推导读面共用。
     */
    Optional<ProductionHandoff> findByProjectId(Long projectId);

    /**
     * 带留痕行的单笔查询（按项目寻址）：后台详情（留痕时间线拼装）在仓储事务
     * 之外，JOIN FETCH 一次取齐，避免懒加载在会话外炸开（同
     * {@code ContractRepository#findWithTrail} 形制）。
     */
    @Query("SELECT h FROM ProductionHandoff h LEFT JOIN FETCH h.transitions "
            + "WHERE h.projectId = :projectId")
    Optional<ProductionHandoff> findWithTrailByProjectId(@Param("projectId") Long projectId);
}
