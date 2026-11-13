package com.semgravio.aiplatform.base.metering.domain.model;

import com.semgravio.aiplatform.base.metering.domain.enums.Band;
import com.semgravio.aiplatform.base.metering.domain.enums.TokenKind;

/**
 * 未配价档位的用量汇总（#161 unpriced 全局警示；带维 #499）：窗口内有用量且事件
 * 时点无生效单价行的 (provider, model, 档位, 带) 按档位汇总——{@code tokens} 只
 * 累计无价分量（同档位部分事件有价部分无价时只计无价部分，与 cost 口径互补不
 * 重叠）；量的单位随档位走（token 档位＝token 数、按张档位＝张数，#288）。
 *
 * <p><b>用量驱动</b>：窗口内无用量的档位不出现（静态配价缺口清单不做——无用量
 * ＝无实际损失）；已配价档位（事件时点有生效价）不出现。{@code band}＝事件时点
 * 归带（峰/谷——时段规则表读时归段，ADR-0044）：峰行有谷行无时谷段分量按
 * band=谷单列进缺价面（不伪装 0）；{@code null}＝不区分时段（通道无生效规则行/
 * 按张档不受带影响）。</p>
 */
public record UnpricedTierUsage(String provider, String model, TokenKind tokenKind,
                                Band band, long tokens) {
}
