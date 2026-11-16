package com.semgravio.aiplatform.base.metering.domain.enums;

import com.cartisan.core.domain.BaseEnum;

/**
 * 成本观测切片桶（#536，ADR-0047「成本观测切片分桶」）：平台成本用量按
 * <b>切片锚</b>（经 {@link com.semgravio.aiplatform.base.metering.domain.port.SubjectSliceAnchors}
 * 供给的 subject 级时点锚——billing 侧语义＝付费状态翻转时点，metering 只按锚
 * 机械分桶、不解释锚的存在性）对事件时间戳分两桶：<b>事件 ts &lt; 锚＝获客成本
 * （翻转前平台担）、ts &gt;= 锚＝用户费用</b>（与计费段结算
 * {@code e.ts >= flippedAt} 边界同口径，翻转时刻本身归用户费用桶）；subject
 * 无锚＝全量落获客成本桶。
 *
 * <p>枚举出口配 *Name（#34 收敛房规：契约化两列 code＋中文名，后台直读零映射）。
 * 词源＝CONTEXT「获客成本」（平台内部语言，正本在成本观测面）。纯读侧契约枚举
 * ——不落库无实体字段（桶维查询侧派生），故无 JPA Converter。</p>
 */
public enum CostBucket implements BaseEnum<CostBucket> {

    ACQUISITION(1, "获客成本"),
    USER_FEE(2, "用户费用");

    private final Integer code;
    private final String name;

    CostBucket(Integer code, String name) {
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

    /** 码 → 枚举（聚合 SQL 桶列回解；未知码如实拒绝——照 DeductionSource ofCode 先例）。 */
    public static CostBucket ofCode(Integer code) {
        for (CostBucket bucket : values()) {
            if (bucket.code.equals(code)) {
                return bucket;
            }
        }
        throw new IllegalArgumentException("未知的成本切片桶码: " + code);
    }
}
