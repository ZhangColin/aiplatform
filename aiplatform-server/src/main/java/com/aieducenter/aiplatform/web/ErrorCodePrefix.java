package com.aieducenter.aiplatform.web;

import com.cartisan.core.exception.CodeMessage;

/**
 * 错误码前缀注册表（ADR-0001：错误码格式 {@code {CONTEXT}_{NNN}}）。
 *
 * <p>一个 BC 一个前缀，新 BC 建立即在此注册（无 REST 错误面的 BC 不设前缀）。通用 HTTP 错误复用 {@link com.cartisan.core.exception.BaseCodeMessage}，
 * 不进本表。各 BC 错误枚举（{@code XxxMessage implements CodeMessage}）的 code
 * 必须以本表登记的前缀开头。</p>
 *
 * <p>数字域码（#169）：信封 {@code code} 装数字业务码＝域码×1000＋序号（如
 * {@code WSP_012}→1012、{@code PRJ_015}→4015），HTTP 状态仍由 CodeMessage 自带——
 * 两者独立。域码取值即契约，一经对外（前端比对）即冻结，不得重排复用。</p>
 */
public enum ErrorCodePrefix {

    /** base.workspace：环境 / 工作区（wsp_） */
    WSP("WSP_", 1, "base.workspace"),

    /** base.knowledge：知识库（knw_） */
    KNW("KNW_", 2, "base.knowledge"),

    /** base.metering：计量（met_） */
    METER("METER_", 3, "base.metering"),

    /** business.project：项目（prj_） */
    PRJ("PRJ_", 4, "business.project"),

    /** business.order：订单（ord_，#28 用户面 REST 错误面起走线） */
    ORD("ORD_", 5, "business.order"),

    /** business.identity：账号认证（idn_） */
    IDN("IDN_", 6, "business.identity"),

    /** base.skills：技能库（skl_） */
    SKL("SKL_", 7, "base.skills");

    private final String prefix;
    private final int domainCode;
    private final String boundedContext;

    ErrorCodePrefix(String prefix, int domainCode, String boundedContext) {
        this.prefix = prefix;
        this.domainCode = domainCode;
        this.boundedContext = boundedContext;
    }

    public String prefix() {
        return prefix;
    }

    /** 数字域码（信封数字业务码的高位段，见类注释）。 */
    public int domainCode() {
        return domainCode;
    }

    /**
     * 所属限界上下文（base./business. 下的 BC 包名，非全限定）。
     */
    public String boundedContext() {
        return boundedContext;
    }

    /**
     * CodeMessage → 信封数字业务码：命中注册表前缀取「域码×1000＋序号」；未登记
     * 前缀或序号非数字（不守 {@code {CONTEXT}_{NNN}} 约定）回落 httpStatus——新 BC
     * 未登记时不至于无码可用，但应在注册表补齐。
     */
    public static int numericOf(CodeMessage codeMessage) {
        String code = codeMessage.code();
        for (ErrorCodePrefix item : values()) {
            if (code.startsWith(item.prefix)) {
                try {
                    return item.domainCode * 1000 + Integer.parseInt(code.substring(item.prefix.length()));
                } catch (NumberFormatException ignored) {
                    break;
                }
            }
        }
        return codeMessage.httpStatus();
    }
}
