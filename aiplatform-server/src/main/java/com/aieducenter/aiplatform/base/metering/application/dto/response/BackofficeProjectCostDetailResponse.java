package com.aieducenter.aiplatform.base.metering.application.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.base.metering.domain.model.TokenUsage;

/**
 * 单项目成本下钻响应（#164 成本运营）：复用 bySubject 聚合口径——总量 + 平台
 * 成本（币种分桶直读不折算）+ 未配价标注 + 分模型 + 分智能体。
 *
 * <p>成本换算同全局总览：token × 事件时点生效单价现算（历史成本不随改价漂移）；
 * 无生效单价的分量不进 cost（不伪装 0），其 (provider, model, 档位) 集合在
 * {@code unpriced} 如实呈现。{@code byAgentKind} 取事件 dims.agentKind 原值
 * （写侧终态口径）+ agentKindName 中文名随行（#186），无维度的事件不参与该
 * 分桶。subject
 * 不透明：无用量（或 id 非法）返回全零 total 与空结构，不是错误。</p>
 *
 * @param projectId   项目标识（计量 subject 原值回显）
 * @param from        窗口起点（含；null = 不限，原样回显）
 * @param to          窗口终点（不含；null = 不限，原样回显）
 * @param total       总量（五档分列）
 * @param cost        平台成本（币种分桶；全未配价/无事件时为空 Map）
 * @param unpriced    未配价标注清单（provider/model/档位码序；与 cost 互补不重叠）
 * @param byModel     分模型聚合（provider + model 为单价表匹配键）
 * @param byAgentKind 分智能体聚合（dims.agentKind 原值 + agentKindName 中文名随行）
 */
public record BackofficeProjectCostDetailResponse(
        String projectId,
        Instant from,
        Instant to,
        TokenUsage total,
        Map<String, BigDecimal> cost,
        List<UnpricedTier> unpriced,
        List<ModelUsage> byModel,
        List<AgentKindUsage> byAgentKind
) {

    public BackofficeProjectCostDetailResponse {
        // 保序拷贝：cost 键序 = 聚合 SQL 的币种码序（API 输出确定性）
        cost = cost == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cost));
        unpriced = unpriced == null ? List.of() : List.copyOf(unpriced);
        byModel = byModel == null ? List.of() : List.copyOf(byModel);
        byAgentKind = byAgentKind == null ? List.of() : List.copyOf(byAgentKind);
    }

    /**
     * 未配价标注项（tokenKind 为 Integer code + tokenKindName 随附，#34 收敛房规；
     * bySubject 口径无 token 计数——档位用量汇总走全局 unpriced 端点）。
     */
    public record UnpricedTier(
            String provider,
            String model,
            Integer tokenKind,
            String tokenKindName
    ) {
    }

    /**
     * 分模型聚合项（provider + model 为单价表匹配键）。
     */
    public record ModelUsage(String provider, String model, TokenUsage tokens) {
    }

    /**
     * 分智能体聚合项（agentKind = dims 透传原值；agentKindName 中文名随行——
     * #186 枚举出口配 *Name，经 {@code AgentKindNames} 端口回解正本
     * AgentProfile，辅助标记为 null）。
     */
    public record AgentKindUsage(String agentKind, String agentKindName, TokenUsage tokens) {
    }
}
