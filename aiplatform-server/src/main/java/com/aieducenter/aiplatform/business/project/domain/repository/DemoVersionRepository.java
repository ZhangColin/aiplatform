package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;
import java.util.Optional;

import com.cartisan.data.jpa.repository.BaseRepository;

import com.aieducenter.aiplatform.business.project.domain.aggregate.DemoVersion;

/**
 * Demo 构建版本仓储（#333）：读面 = 项目版本清单按版本号倒序（最新在前——
 * 版本记录消费面默认呈现最新）与单版寻址；写面 = 收口成版插入（行不可变，
 * 无更新路径——确认标记归项目聚合指针，不动版本行）。项目删除真删级联的
 * 显式清理由编排负责（软引用惯例）。
 */
public interface DemoVersionRepository extends BaseRepository<DemoVersion, Long> {

    /** 项目 Demo 版本清单（version_no 倒序——最新在前；空 = 从未成版）。 */
    List<DemoVersion> findByProjectIdOrderByVersionNoDesc(Long projectId);

    /** 最新 Demo 版本（成版判定的单行取件——不拉全表）。 */
    Optional<DemoVersion> findFirstByProjectIdOrderByVersionNoDesc(Long projectId);

    /** 单版寻址（确认标记动作的目标版本取件）。 */
    Optional<DemoVersion> findByProjectIdAndVersionNo(Long projectId, int versionNo);

    /** 项目删除真删级联清理（软引用显式清，同对话史/轨道表惯例）。 */
    void deleteByProjectId(Long projectId);
}
