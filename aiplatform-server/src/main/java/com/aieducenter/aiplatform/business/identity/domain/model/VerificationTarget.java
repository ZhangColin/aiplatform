package com.aieducenter.aiplatform.business.identity.domain.model;

import java.util.Optional;
import java.util.regex.Pattern;

import com.aieducenter.aiplatform.business.identity.domain.enums.AccountBindingProvider;

/**
 * 验证码收码目标（#327）：M01 弹窗「手机号/邮箱」单输入的解析模型——按形态定
 * 通道（含 @＝邮箱、纯数字可带前缀加号＝手机号），并归一到绑定标识形态（邮箱
 * 小写——同一人不因大小写裂成两个收码目标；手机号原样）。解析失败返 empty，
 * 错误语义（IDN_005）归调用面。
 *
 * <p>校验口径是「可收码形态」而非「真实可达」：真实可达由服务商投递事实回答，
 * 平台侧不做深度格式校验（投机性收窄的教训同 PRD 标题行宽容——#304）。</p>
 */
public record VerificationTarget(AccountBindingProvider channel, String identifier) {

    /** 邮箱最小形态（local@domain.tld，无空白）；真实可达不在此判 */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** 手机号：可选 + 前缀＋5–20 位数字（国家码/长度不做地区性收窄） */
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{5,20}$");

    /** 与绑定表 identifier 列宽对齐（超宽＝不可收码形态，不留给落库守卫） */
    private static final int IDENTIFIER_MAX_LENGTH = 200;

    /** 单输入 → 通道＋归一标识；不构成可收码形态时 empty。 */
    public static Optional<VerificationTarget> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > IDENTIFIER_MAX_LENGTH) {
            return Optional.empty();
        }
        if (EMAIL.matcher(trimmed).matches()) {
            return Optional.of(new VerificationTarget(AccountBindingProvider.EMAIL,
                    trimmed.toLowerCase()));
        }
        if (PHONE.matcher(trimmed).matches()) {
            return Optional.of(new VerificationTarget(AccountBindingProvider.PHONE, trimmed));
        }
        return Optional.empty();
    }
}
