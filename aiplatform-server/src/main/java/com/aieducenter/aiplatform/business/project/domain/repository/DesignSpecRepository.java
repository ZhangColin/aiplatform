package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.DesignSpec;

/**
 * 项目设计规范正本仓储（#295，ADR-0028）：读面＝项目单一正本行（无行＝项目
 * 无规范——遵守链 {@code #296} 据此分岔「带规范项目」）；写面＝随定稿刷新的
 * 覆写（建行/整行覆写归聚合方法）与项目删除级联清理。
 */
public interface DesignSpecRepository extends BaseRepository<DesignSpec, Long> {

    /** 项目单一正本行（uk 兜底至多一行；空＝无规范）。 */
    Optional<DesignSpec> findByProjectId(Long projectId);

    /** 项目删除级联清理（软引用显式清，同设计轨道表惯例）。 */
    void deleteByProjectId(Long projectId);
}
