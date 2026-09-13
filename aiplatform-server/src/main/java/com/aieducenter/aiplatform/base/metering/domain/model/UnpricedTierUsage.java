package com.aieducenter.aiplatform.base.metering.domain.model;

import com.aieducenter.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 未配价档位的用量汇总（#161 unpriced 全局警示）：窗口内有 token 用量且事件时点
 * 无生效单价行的 (provider, model, 档位) 按档位汇总——{@code tokens} 只累计无价
 * 分量（同档位部分事件有价部分无价时只计无价部分，与 cost 口径互补不重叠）。
 *
 * <p><b>用量驱动</b>：窗口内无用量的档位不出现（静态配价缺口清单不做——无用量
 * ＝无实际损失）；已配价档位（事件时点有生效价）不出现。</p>
 */
public record UnpricedTierUsage(String provider, String model, TokenKind tokenKind, long tokens) {
}
