package com.semgravio.aiplatform.business.identity.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 访客域配置（前缀 app.guest，#326）：保留期缺省走配置——「过保留期不认、数据不删」
 * 的期限面与续期写节流窗口。
 *
 * <p>有效性窗口实际落在 [retention-period, retention-period + renew-threshold]
 * （节流写封顶续期频率）；renew-threshold 应不超过 retention-period 的一半量级，
 * 否则节流失去意义（与保留期等宽＝每次读都写）。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.guest")
public class GuestProperties {

    /** 保留期（自最近活跃起算，默认 30 天）：逾此凭证不认（行保留不删）。 */
    private Duration retentionPeriod = Duration.ofDays(30);

    /** 续期写节流窗口（距最近活跃达此值才写，默认 15 天）。 */
    private Duration renewThreshold = Duration.ofDays(15);

    public Duration getRetentionPeriod() {
        return retentionPeriod;
    }

    public void setRetentionPeriod(Duration retentionPeriod) {
        this.retentionPeriod = retentionPeriod;
    }

    public Duration getRenewThreshold() {
        return renewThreshold;
    }

    public void setRenewThreshold(Duration renewThreshold) {
        this.renewThreshold = renewThreshold;
    }
}
