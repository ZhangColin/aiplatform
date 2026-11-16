package com.semgravio.aiplatform.business.billing.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 固定单价项的计件档（ADR-0047 固定单价项族）：按张（图片生成——#288 IMAGE
 * 档先行）、按件（联网搜索等按次——#531 UNIT 档）。固定单价项用户扣减＝件数 ×
 * 单价直记（不乘全局折算率）；单价行在计量面计件档（met_price_entries 的
 * IMAGE/UNIT 档），运营换行同机制。不落库（计价查询面的档别参数），code 与
 * base.metering.TokenKind 的计件两档码位对齐（6=按张 7=按件——适配面单源），
 * BaseEnum 仅为房规口径。
 */
public enum PieceKind implements BaseEnum<PieceKind> {

    IMAGE(6, "按张"),
    UNIT(7, "按件");

    private final Integer code;
    private final String name;

    PieceKind(Integer code, String name) {
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
