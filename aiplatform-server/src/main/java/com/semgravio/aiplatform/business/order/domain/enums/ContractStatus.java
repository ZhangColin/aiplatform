package com.semgravio.aiplatform.business.order.domain.enums;

import java.util.List;

import com.cartisan.core.domain.BaseEnum;
import com.cartisan.data.jpa.converter.BaseEnumConverter;

import jakarta.persistence.Converter;

/**
 * 合同状态（#336 六态照交接包原样立，spec #320 实施序④）：准备中 → 待核对与
 * 签署 → 签署进行中 → 签署完成 单向推进；签署失败或过期与已撤回或作废为旁落
 * 终态。全部后台人工推进（每次迁移 append-only 留痕，操作人/时间/依据必填）。
 *
 * <p>「签署完成」＝双方签署位均已落签（推进守卫单点 {@link #isDraft} 之外的
 * {@code Contract#advance} 守卫）；终态（4/5/6）永不物理删除——无删除口即口径，
 * 库侧部分唯一索引（uk_ord_contracts_active）同谓词放行重建。「草稿态」＝
 * 准备中/待核对与签署：内容同号重录＋重新冻结的唯一窗口；签署进行中起变动走
 * 撤回/作废重建（新号新合同、旧合同终态保留）。</p>
 *
 * <p>REST 以 Integer code 传递（BaseEnum 约定）。{@link #TERMINAL} 是「同订单
 * 至多一份活跃合同」的判定口径单点（仓储预检与库侧部分唯一索引共用）。</p>
 */
public enum ContractStatus implements BaseEnum<ContractStatus> {

    PREPARING(1, "准备中"),

    AWAITING_SIGNATURE(2, "待核对与签署"),

    SIGNING(3, "签署进行中"),

    SIGNED(4, "签署完成"),

    FAILED_OR_EXPIRED(5, "签署失败或过期"),

    WITHDRAWN_OR_VOIDED(6, "已撤回或作废");

    /** 终态清单（签署完成/签署失败或过期/已撤回或作废）：终态合同永不物理删除。 */
    public static final List<ContractStatus> TERMINAL = List.of(SIGNED, FAILED_OR_EXPIRED, WITHDRAWN_OR_VOIDED);

    /** 草稿态清单（准备中/待核对与签署）：内容同号重录＋重新冻结的唯一窗口。 */
    public static final List<ContractStatus> DRAFT = List.of(PREPARING, AWAITING_SIGNATURE);

    private final Integer code;
    private final String name;

    ContractStatus(Integer code, String name) {
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

    /** 是否终态（见 {@link #TERMINAL}）。 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 是否草稿态（见 {@link #DRAFT}）——重录窗口判定口径单点。 */
    public boolean isDraft() {
        return DRAFT.contains(this);
    }

    /**
     * 线性下一态（推进边正本）：准备中 → 待核对与签署 → 签署进行中 → 签署完成；
     * 旁落终态（5/6）与已到头的签署完成无下一态（返回 {@code null}＝无推进边，
     * 守卫译 ORD_021）。
     */
    public ContractStatus nextOnAdvance() {
        return switch (this) {
            case PREPARING -> AWAITING_SIGNATURE;
            case AWAITING_SIGNATURE -> SIGNING;
            case SIGNING -> SIGNED;
            case SIGNED, FAILED_OR_EXPIRED, WITHDRAWN_OR_VOIDED -> null;
        };
    }

    /**
     * JPA Converter（框架自动应用）。
     */
    @Converter(autoApply = true)
    public static class JpaConverter extends BaseEnumConverter<ContractStatus> {
        public JpaConverter() {
            super(ContractStatus.class);
        }
    }
}
