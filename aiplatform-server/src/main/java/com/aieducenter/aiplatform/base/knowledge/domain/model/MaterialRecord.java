package com.aieducenter.aiplatform.base.knowledge.domain.model;

import java.time.Instant;

import com.aieducenter.aiplatform.base.knowledge.domain.enums.MaterialStatus;

/**
 * 素材登记行读模型（#166 管理读面）：{@code knw_materials} 一行的管理面呈现——
 * 身份（id 合成 TSID＋(kind, sourceRef) 幂等键）、来源项目引用（容缺直读：登记面
 * 冗余字段，不校验项目存在——项目删除级联清素材，引用无悬空面）、状态与最近
 * 管理动作操作者、沉淀时间。
 *
 * <p>沉淀时间＝{@code created_at}（首沉淀，#153 对接口径的端点侧拍板）：重沉淀
 * 幂等替换不改此列（登记 upsert 保首沉淀时间），治理动作（停用/启用）也不改——
 * 管理面沉淀时间语义稳定；{@code updated_at} 混杂重沉淀与治理两类动因，不呈现。</p>
 *
 * @param id           素材标识（TSID，首沉淀生成、重沉淀保留；管理端点 URL 柄）
 * @param kind         素材类别（幂等键之一）
 * @param sourceRef    素材来源标识（幂等键之二；v1 业务口径＝projectId）
 * @param projectId    来源项目 id（级联清理入口；容缺直读）
 * @param projectName  来源项目名（登记面展示冗余）
 * @param title        素材标题（登记面展示冗余）
 * @param status       素材状态（1=启用 2=停用）
 * @param sunkAt       首沉淀时间
 * @param operatorId   最近管理动作操作者 id（未治理过为 null）
 * @param operatorName 最近管理动作操作者名（直读展示）
 */
public record MaterialRecord(
        long id,
        String kind,
        String sourceRef,
        String projectId,
        String projectName,
        String title,
        MaterialStatus status,
        Instant sunkAt,
        String operatorId,
        String operatorName) {
}
