package com.aieducenter.aiplatform.base.knowledge.application.dto.response;

import java.time.Instant;

import com.aieducenter.aiplatform.base.knowledge.domain.model.MaterialRecord;

/**
 * 后台知识素材详情（#166）：元数据（与清单行同形）＋素材全文。全文＝块按 seq
 * 以空行拼接（段落级重组：分块按空行切段落合并成块，超长单段硬切的切点呈现为
 * 段落断——内容无损、排版尽力）；运营读全文才能判断停不停用，故详情必带内容。
 *
 * @param id           素材标识（TSID 十进制字符串）
 * @param kind         素材类别（v1 业务口径 PRD）
 * @param projectId    来源项目 id（容缺直读，不校验存在）
 * @param projectName  来源项目名（登记面冗余）
 * @param title        素材标题
 * @param status       素材状态 code（1=启用 2=停用）
 * @param statusName   素材状态名（直读展示）
 * @param sunkAt       首沉淀时间
 * @param operatorId   最近管理动作操作者 id（未治理过为 null）
 * @param operatorName 最近管理动作操作者名（直读展示）
 * @param content      素材全文（块按 seq 以空行拼接）
 */
public record BackofficeMaterialDetailResponse(
        String id,
        String kind,
        String projectId,
        String projectName,
        String title,
        Integer status,
        String statusName,
        Instant sunkAt,
        String operatorId,
        String operatorName,
        String content) {

    /** 登记行＋全文 → 详情响应。 */
    public static BackofficeMaterialDetailResponse of(MaterialRecord record, String content) {
        return new BackofficeMaterialDetailResponse(
                Long.toString(record.id()),
                record.kind(),
                record.projectId(),
                record.projectName(),
                record.title(),
                record.status().getCode(),
                record.status().getName(),
                record.sunkAt(),
                record.operatorId(),
                record.operatorName(),
                content);
    }
}
