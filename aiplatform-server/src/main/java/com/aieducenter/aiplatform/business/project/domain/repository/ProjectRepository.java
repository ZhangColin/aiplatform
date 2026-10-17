package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;

/**
 * 项目仓储。
 */
public interface ProjectRepository extends BaseRepository<Project, Long> {

    /** 工作区名下的项目（Phase A 一项目一 dev 环境；savePrd 落盘回调按工作区寻址项目，#49）。 */
    Optional<Project> findByWorkspaceId(Long workspaceId);

    /** 归属账户名下的项目（#325 用户面列表 owner 过滤；排序归调用方）。 */
    List<Project> findByOwnerAccountId(Long ownerAccountId, Sort sort);

    /** 归属访客名下的项目（#326 访客维度列表过滤；排序归调用方）。 */
    List<Project> findByOwnerGuestId(Long ownerGuestId, Sort sort);

    /** 无主项目（owner 双列皆空：无上下文的等值匹配端——无主对无主；排序归调用方）。 */
    List<Project> findByOwnerAccountIdIsNullAndOwnerGuestIdIsNull(Sort sort);
}
