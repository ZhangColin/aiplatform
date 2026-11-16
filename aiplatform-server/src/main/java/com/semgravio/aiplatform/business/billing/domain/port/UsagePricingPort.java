package com.semgravio.aiplatform.business.billing.domain.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

import com.semgravio.aiplatform.business.billing.domain.enums.PieceKind;
import com.semgravio.aiplatform.business.billing.domain.model.PiecePrice;
import com.semgravio.aiplatform.business.billing.domain.model.PricedLine;

/**
 * 用量计价端口（billing → metering 的读面）：段用量归并与固定单价报价。
 * 单价表是 base.metering 私有表面（不经端口暴露）——本端口只出<b>已配价的
 * 换算结果</b>（用量×时点生效单价），配价区间/时段带匹配全留在计量面（同一套
 * SQL 机器）。迁出独立计量服务时按端口换 REST 适配器，调用方不动。
 */
@Port(PortType.CLIENT)
public interface UsagePricingPort {

    /**
     * 段用量的已配价线集：按 (provider, model, 档位, 时点生效单价, 币种) 归并
     * （subject=projectId 字符串、runIds=段内各次智能体运行的用量事件寻址键）。
     * {@code billingBoundary} 非 null 时只计其后事件（付费状态翻转时点＝计费边界
     * ——翻转前事件＝平台获客成本不进用户账，机制面兜底不靠编排自律）。
     * 无事件或全未配价返回空清单（未配价分量不进用户账——平台成本观测的
     * unpriced 面可见，不伪装 0）。
     */
    List<PricedLine> pricedLinesOf(String subject, Collection<String> runIds, Instant billingBoundary);

    /**
     * 固定单价项的单价报价（按次直记的计价面）：计件档（按张/按件）单价行在
     * {@code at} 时点的现值；无生效行返回 empty（调用方跳过直记并留观测——
     * 平台吸收，不伪装 0 价）。
     */
    Optional<PiecePrice> piecePriceOf(String provider, String model, PieceKind pieceKind, Instant at);
}
