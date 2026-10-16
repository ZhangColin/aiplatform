package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.PrdVersion;

/**
 * PRD 确认版本仓储（#330）：读面 = 项目版本清单按版本号倒序（最新在前——
 * 版本记录消费面默认呈现最新）与单版寻址；写面 = 确认成版插入（行不可变，
 * 无更新路径）。项目删除真删级联的显式清理由编排负责（软引用惯例）。
 */
public interface PrdVersionRepository extends BaseRepository<PrdVersion, Long> {

    /** 项目已确认版本清单（version_no 倒序——最新在前；空 = 从未确认）。 */
    List<PrdVersion> findByProjectIdOrderByVersionNoDesc(Long projectId);

    /** 单版寻址（详情/成版判定「最新版」取件）。 */
    Optional<PrdVersion> findByProjectIdAndVersionNo(Long projectId, int versionNo);

    /** 项目删除真删级联清理（软引用显式清，同对话史/轨道表惯例）。 */
    void deleteByProjectId(Long projectId);
}
