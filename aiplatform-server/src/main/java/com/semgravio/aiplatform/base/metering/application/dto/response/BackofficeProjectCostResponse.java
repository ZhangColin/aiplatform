package com.semgravio.aiplatform.base.metering.application.dto.response;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

import com.semgravio.aiplatform.base.metering.domain.model.TokenUsage;

/**
 * 项目成本清单行（#164 成本运营）：窗口内该项目的总量 + 平台成本（币种分桶
 * 直读不折算，键 = ISO 4217 币种码）+ 全未配价标注＋成本观测切片桶（#536：行内
 * 获客成本/用户费用两桶——按切片锚〔billing 侧＝付费状态翻转时点〕对事件时间戳
 * 分桶；两桶互补不重叠、并集＝行 total/cost；未翻转项目全量获客成本桶）。
 *
 * <p>用量驱动：窗口内无用量的项目不出现在清单；{@code allUnpriced} = 有用量但
 * 无任何已配价分量——清单排后标注（成本标量缺失不伪装 0），明细走单项目下钻
 * 端点。{@code projectId} = 计量 subject 原值（写侧口径 projectId 十进制串，
 * 底座不解释存在性；项目名等档案信息归 admin 侧按 id 自行互查）。</p>
 */
public record BackofficeProjectCostResponse(
        String projectId,
        TokenUsage total,
        @Schema(description = "平台成本（币种分桶直读不折算：键 = ISO 4217 币种码、值 = 金额；"
                + "全未配价时为空对象，成本标量缺失不伪装 0——allUnpriced 同行为 true）",
                example = "{\"USD\": 12.34}")
        Map<String, BigDecimal> cost,
        boolean allUnpriced,
        @Schema(description = "成本观测切片桶（#536：1=获客成本 2=用户费用——按付费状态翻转"
                + "时间戳对事件时间戳分桶，翻转前＝获客成本/翻转后＝用户费用；未翻转项目"
                + "全量获客成本桶〔该桶 cost 与行 cost 同源相等〕；窗口内无翻转后用量时"
                + "用户费用桶缺席〔用量驱动，不伪装 0〕）")
        List<BackofficeCostOverviewResponse.BucketCost> byBucket
) {

    public BackofficeProjectCostResponse {
        // 保序拷贝：cost 键序 = 聚合 SQL 的币种码序（API 输出确定性）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        byBucket = byBucket == null ? List.of() : List.copyOf(byBucket);
    }
}
