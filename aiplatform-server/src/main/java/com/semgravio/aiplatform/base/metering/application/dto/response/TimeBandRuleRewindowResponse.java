package com.semgravio.aiplatform.base.metering.application.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 改窗回执（#497 原子改窗——对称单价表 {@code UnitPriceEntryRepriceResponse}）：
 * 单调用「关当前版本全部行＋开新版本」的两段结果。被关行保留其原开行操作者
 * （不被改写）；新行操作者取本次请求（无头落空）。中途任一守卫失败两段都不
 * 落（事务回滚）。
 *
 * @param closed 被关版本全部行（含其原操作者留痕与生效区间两端）
 * @param opened 新版本全部行（新窗口项随行落库，操作者取本次请求）
 */
public record TimeBandRuleRewindowResponse(
        @Schema(description = "被关版本全部行（关行时点＝新版本生效起点）")
        List<TimeBandRuleResponse> closed,
        @Schema(description = "新版本全部行（窗口清单随行落库，清单即终态）")
        List<TimeBandRuleResponse> opened
) {
}
