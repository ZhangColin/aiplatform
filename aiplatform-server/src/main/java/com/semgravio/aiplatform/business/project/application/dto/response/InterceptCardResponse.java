package com.semgravio.aiplatform.business.project.application.dto.response;

import com.semgravio.aiplatform.business.billing.application.TrialInterceptAppService;

/**
 * 待决试用拦截卡读面（#533 计费②门②，卡可回）：SSE 事件与 GET 读面同形的卡面
 * ——被拦输入引用、源池显式素材（源未定在卡上完成首次选源）。
 *
 * @param interceptId       留存 id（卡锚；字符串化防 JS 精度丢失）
 * @param lane              被拦车道（OPINION=意见 / FALLBACK=兜底）
 * @param prompt            被拦输入原文（卡上引用呈现）
 * @param sourceSelected    扣减源是否已选定（后台已充项目池＝true 免登录直接确认）
 * @param deductionSource   已选定扣减源码位（1=账户余额 2=项目余额；未定 null）
 * @param sourcePoolBalance 源池余额快照（分；池未建 null）
 * @param runId             拦截卡事件锚（挂 /api/events?runId= ）
 */
public record InterceptCardResponse(String interceptId, String lane, String prompt,
        boolean sourceSelected, Integer deductionSource, Long sourcePoolBalance, String runId) {

    /** 计费卡面 → 响应（装配归控制器——应用层记录直映，不二次发明字段）。 */
    public static InterceptCardResponse of(TrialInterceptAppService.InterceptCard card) {
        return new InterceptCardResponse(card.interceptId(), card.lane(), card.prompt(),
                card.sourceSelected(), card.deductionSource(), card.sourcePoolBalance(), card.runId());
    }
}
