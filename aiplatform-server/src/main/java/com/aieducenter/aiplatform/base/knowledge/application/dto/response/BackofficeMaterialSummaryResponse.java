package com.aieducenter.aiplatform.base.knowledge.application.dto.response;

import java.time.Instant;

import com.aieducenter.aiplatform.base.knowledge.domain.model.MaterialRecord;

/**
 * 后台知识素材响应（#166，清单行/停用⇄启用回执/删除回执共形）：素材元数据一行。
 * 管理单元＝素材＝项目 × 素材类型（登记表一行），非块。
 *
 * <p>来源项目引用容缺直读（登记面冗余字段，不校验项目存在）；operator 两列＝
 * 该素材最近管理动作（停用或启用）的操作者，未治理过为 null——删除无行可留、
 * 不留痕。sunkAt＝首沉淀时间（重沉淀与治理动作不改此列，管理面沉淀时间正口径）。
 * status Integer code（1=启用 2=停用，#34 房规）。</p>
 *
 * @param id           素材标识（TSID 十进制字符串，URL 柄）
 * @param kind         素材类别（v1 业务口径 PRD）
 * @param projectId    来源项目 id（容缺直读）
 * @param projectName  来源项目名（登记面冗余）
 * @param title        素材标题
 * @param status       素材状态 code（1=启用 2=停用）
 * @param statusName   素材状态名（直读展示）
 * @param sunkAt       首沉淀时间
 * @param operatorId   最近管理动作操作者 id（未治理过为 null）
 * @param operatorName 最近管理动作操作者名（直读展示）
 */
public record BackofficeMaterialSummaryResponse(
        String id,
        String kind,
        String projectId,
        String projectName,
        String title,
        Integer status,
        String statusName,
        Instant sunkAt,
        String operatorId,
        String operatorName) {

    /** 登记行 → 后台响应。 */
    public static BackofficeMaterialSummaryResponse of(MaterialRecord record) {
        return new BackofficeMaterialSummaryResponse(
                Long.toString(record.id()),
                record.kind(),
                record.projectId(),
                record.projectName(),
                record.title(),
                record.status().getCode(),
                record.status().getName(),
                record.sunkAt(),
                record.operatorId(),
                record.operatorName());
    }
}
