package com.semgravio.aiplatform.business.project.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 首建派发门读面（#542 访谈②，ADR-0048/0049）：岔路口三选卡与 PRD 确认按钮的
 * 呈现条件单读点（服务端单源，前端不自算）——自动派发两入口退役后，首个构建
 * run 的点火归用户：试用态自然收口出岔路口卡（「立即生成」经 POST /generate
 * 点火）、付费态 PRD 文档面确认按钮（POST /prd/confirm 确认即派发）；快道
 * （试用态＝用户明确收敛令；付费态＝零必答短路/「直接开始做」视同确认）收口
 * 观测直派，两读面都缺席。
 */
@Schema(description = "首建派发门读面：岔路口卡与 PRD 确认按钮的呈现条件（服务端"
        + "单源）。快道收口直派生成——两读面都缺席（false）")
public record BuildGateResponse(

        @Schema(description = "岔路口三选卡呈现（试用态访谈自然收口待选择——持久可回；"
                + "「立即生成」经 POST /generate 点火首个构建）", example = "true")
        boolean forkCardPending,

        @Schema(description = "PRD 文档面确认按钮呈现（付费态首建待确认——确认即派发，"
                + "POST /prd/confirm）", example = "false")
        boolean prdConfirmable,

        @Schema(description = "「继续讨论」引导流到账自动翻转信号（#544——卡在场∧源已"
                + "选定∧源池余额为正：他处到账〔不限收银台单缝〕后卡回读即显，前端空 "
                + "source 重判即翻转、无需重走选择）", example = "false")
        boolean forkSettleDue) {
}
