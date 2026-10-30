package com.semgravio.aiplatform.business.project.domain.repository;

import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.semgravio.aiplatform.business.project.domain.aggregate.ProductionHandoff;

/**
 * 生产承接仓储（#338）。
 */
public interface ProductionHandoffRepository extends BaseRepository<ProductionHandoff, Long> {

    /** 承接唯一索引名（「只建一次」并发兜底腿的判别键，与 V40__production_handoff.sql 对齐）。 */
    String UNIQUE_PROJECT_INDEX = "uk_prj_production_handoffs_project";

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

    /**
     * 完整性违例是否撞承接唯一索引（#338 收敛单点——域内建单「只建一次」的并发
     * 兜底判别，原 KickoffAppService/ProductionHandoffAppService 双份逐字同体）：
     * 独立事务的建单口（本 BC）与跨 BC 判定器的事务边界（订单转已支付＋建承接同
     * 事务，撞索引整体回滚后吸收为幂等空转）都以此判别——非本索引的违例不冒名。
     */
    static boolean violatesUniqueProjectIndex(DataIntegrityViolationException e) {
        return mentions(e.getMessage()) || mentions(e.getMostSpecificCause().getMessage());
    }

    private static boolean mentions(String message) {
        return message != null && message.contains(UNIQUE_PROJECT_INDEX);
    }
}
