package com.semgravio.aiplatform.business.billing.domain.model;

import com.cartisan.core.domain.BaseEnum;

/**
 * 计价档族（ADR-0047 计价与账本）：token 档（五档互斥分解——用户扣减＝用量×
 * 成本单价×<b>全局折算率</b>）与计件档（按张/按件——固定单价项族，用户扣减＝
 * 件数×单价<b>直记不乘折算率</b>）。计量面档别词汇（TokenKind）归 base.metering，
 * 本枚举是 billing 对外的两族归类（折算率是否适用单点在此判）；不落库（快照内
 * 以 name 留痕），BaseEnum 仅为房规口径（Integer code 供契约统一）。
 */
public enum PricingTier implements BaseEnum<PricingTier> {

    TOKEN(1, "token 档"),
    PIECE(2, "计件档");

    private final Integer code;
    private final String name;

    PricingTier(Integer code, String name) {
        this.code = code;
        this.name = name;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getName() {
        return name;
    }
}
