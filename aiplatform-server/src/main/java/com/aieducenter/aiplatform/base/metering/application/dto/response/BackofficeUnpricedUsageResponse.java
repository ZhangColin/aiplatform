package com.aieducenter.aiplatform.base.metering.application.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * unpriced 全局警示响应（#161 成本运营，用量驱动）：窗口内有 token 用量且事件
 * 时点无生效单价的 (provider, model, 档位) 按档位汇总——{@code tokens} 只累计
 * 无价分量（同档位部分有价部分无价时只计无价部分）。
 *
 * <p><b>静态配价缺口不做</b>：无用量＝无实际损失，已配价档位与无用量档位皆不
 * 出现；窗口内无未配价用量时 {@code items} 为空清单，不是错误。</p>
 *
 * @param from  窗口起点（含；null = 不限，原样回显）
 * @param to    窗口终点（不含；null = 不限，原样回显）
 * @param items 未配价档位清单（provider/model/档位码序）
 */
public record BackofficeUnpricedUsageResponse(
        Instant from,
        Instant to,
        List<UnpricedTier> items
) {

    public BackofficeUnpricedUsageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 未配价档位项（tokenKind 为 Integer code + tokenKindName 随附，#34 收敛房规）。
     */
    public record UnpricedTier(
            String provider,
            String model,
            Integer tokenKind,
            String tokenKindName,
            long tokens
    ) {
    }
}
