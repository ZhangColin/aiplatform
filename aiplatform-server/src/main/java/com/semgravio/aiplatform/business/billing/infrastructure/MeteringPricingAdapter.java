package com.semgravio.aiplatform.business.billing.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.semgravio.aiplatform.base.metering.application.MeteringAppService;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;
import com.semgravio.aiplatform.base.metering.domain.model.PricedUsageLine;
import com.semgravio.aiplatform.business.billing.domain.enums.PieceKind;
import com.semgravio.aiplatform.business.billing.domain.model.PiecePrice;
import com.semgravio.aiplatform.business.billing.domain.model.PricedLine;
import com.semgravio.aiplatform.business.billing.domain.model.PricingTier;
import com.semgravio.aiplatform.business.billing.domain.port.UsagePricingPort;

/**
 * 用量计价端口进程内适配器（#531 计费①）：转发 base.metering 应用层——单价表
 * 与区间/带匹配机器留在计量面（同一套换算），本适配器只做词汇映射
 * （TokenKind 档别 → billing 计价档族/档名）。计量服务迁出时按端口换 REST
 * 适配器，billing 调用方不动。
 */
@Component
@Adapter(PortType.CLIENT)
public class MeteringPricingAdapter implements UsagePricingPort {

    private final MeteringAppService metering;

    public MeteringPricingAdapter(MeteringAppService metering) {
        this.metering = metering;
    }

    @Override
    public List<PricedLine> pricedLinesOf(String subject, Collection<String> runIds,
            Instant billingBoundary) {
        return metering.pricedUsageLinesByRuns(subject, runIds, billingBoundary).stream()
                .map(MeteringPricingAdapter::toLine)
                .toList();
    }

    @Override
    public Optional<PiecePrice> piecePriceOf(String provider, String model,
            PieceKind pieceKind, Instant at) {
        return metering.piecePriceOf(provider, model, toTokenKind(pieceKind), at)
                .map(line -> new PiecePrice(line.unitPrice(), line.currency()));
    }

    private static PricedLine toLine(PricedUsageLine line) {
        return new PricedLine(line.provider(), line.model(), tierOf(line.kind()),
                line.kind().name(), line.quantity(), line.unitPrice(), line.currency());
    }

    /** token 五档＝TOKEN 族（乘折算率）；计件档＝PIECE 族（直记不乘）——族判定单点在 {@link TokenKind#isCountBased()}。 */
    private static PricingTier tierOf(TokenKind kind) {
        return kind.isCountBased() ? PricingTier.PIECE : PricingTier.TOKEN;
    }

    /** 计件档码位对齐映射（PieceKind.code ≡ TokenKind 计件两档 code，单源不重写词表）。 */
    private static TokenKind toTokenKind(PieceKind pieceKind) {
        return BaseEnum.requireByCode(TokenKind.class, pieceKind.getCode());
    }
}
