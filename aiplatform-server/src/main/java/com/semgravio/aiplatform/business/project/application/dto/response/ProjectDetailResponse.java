package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.semgravio.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.semgravio.aiplatform.business.project.domain.enums.GenerationState;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectStatus;
import com.semgravio.aiplatform.business.project.domain.enums.ProjectType;

/**
 * 项目详情响应：列表字段全量。
 *
 * @param id                  项目标识（TSID 十进制字符串）
 * @param name                项目名
 * @param type                项目类型（code）
 * @param typeName            项目类型名
 * @param endpointType        终点类型（code：1=设计 2=系统 3=系统＋设计，#285——
 *                            下单前可变、下单即冻结；PRD 清单章形态的选择键）
 * @param endpointTypeName    终点类型名
 * @param designScope         设计范围（系统＋设计的页面锚定范围；null = 无页面锚定）
 * @param workspaceId         dev 工作区标识
 * @param status              派生项目状态（code）：IN_PROGRESS / ARCHIVED（归档优先）
 * @param statusName          派生状态名
 * @param archived            是否已归档（单向终点）
 * @param createdAt           创建时间
 * @param updatedAt           更新时间（审计列）
 * @param prdProducedAt       PRD 产出时点（PRD 文档面滑出判据；NULL = 闲聊期——对话占
 *                            满全宽、右栏未滑出）
 * @param generatedAt         首次生成时点（run 成功收口单向置位；NULL = 未生成过——
 *                            含首建未起跑、失败/停止后待重跑）
 * @param generationState     生成态投影（#222；#604 四态归一：generated_at＋在途标记
 *                            派生——NEVER_GENERATED / GENERATING / GENERATED 三档，
 *                            无「生成中断」档〔失败不落投影，只经 run-failed 事件在
 *                            会话内呈现〕；与 SSE 会话态无关，刷新/回访后档位仍正确）
 * @param generationStateName 生成态名（#186 枚举出口配 *Name，消费端零映射）
 * @param activeOrder         未终结订单摘要（#28：无 = null——锁定式矩阵的推导输入，
 *                            订单存在即冻结迭代；跨 BC 软引用）
 * @param latestOrder         最近一张订单摘要（任意状态，#30：归档终态项目页的
 *                            「完整记录」取单面——支付归档后 activeOrder 归空、
 *                            订单卡改挂本嵌入；从未下单 = null）
 * @param designItems         设计轨道件清单（#290 计划区只读透出，ord 升序——
 *                            设计物清单逐件；PRD 版本锚一致才有效，锚不一致（PRD
 *                            已演进、旧清单过期）或无件行 = null——不拿旧清单对进度）
 */
public record ProjectDetailResponse(
        String id,
        String name,
        ProjectType type,
        String typeName,
        ProjectEndpointType endpointType,
        String endpointTypeName,
        DesignScopeResponse designScope,
        String workspaceId,
        ProjectStatus status,
        String statusName,
        Boolean archived,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime prdProducedAt,
        LocalDateTime generatedAt,
        GenerationState generationState,
        String generationStateName,
        OrderBriefResponse activeOrder,
        OrderBriefResponse latestOrder,
        List<DesignItemResponse> designItems
) {
}
