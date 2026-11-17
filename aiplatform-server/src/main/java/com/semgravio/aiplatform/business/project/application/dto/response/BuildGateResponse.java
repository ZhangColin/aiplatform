package com.semgravio.aiplatform.business.project.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 首建派发门读面（#542 访谈②，ADR-0048/0049）：岔路口三选卡与 PRD 确认按钮的
 * 呈现条件单读点（服务端单源，前端不自算）——自动派发两入口退役后，首个构建
 * run 的点火归用户：试用态自然收口出岔路口卡（「立即生成」经 POST /generate
 * 点火）、付费态 PRD 文档面确认按钮（POST /prd/confirm 确认即派发）；快道
 * （收敛令/零必答短路/「直接开始做」）收口观测直派，两读面都缺席。
 */
@Schema(description = "首建派发门读面：岔路口卡与 PRD 确认按钮的呈现条件（服务端"
        + "单源）。快道收口直派生成——两读面都缺席（false）")
public record BuildGateResponse(

        @Schema(description = "岔路口三选卡呈现（试用态访谈自然收口待选择——持久可回；"
                + "「立即生成」经 POST /generate 点火首个构建）", example = "true")
        boolean forkCardPending,

        @Schema(description = "PRD 文档面确认按钮呈现（付费态首建待确认——确认即派发，"
                + "POST /prd/confirm）", example = "false")
        boolean prdConfirmable) {
}
