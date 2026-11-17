package com.semgravio.aiplatform.business.project.application.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 「继续讨论」引导流判定回执（#544 访谈③，翻转门①）：前端分岔编排的单源——
 * flipped＝已翻转回访谈（卡随 GET /build-gate 读面熄灭、五问额度解除、协议分轨
 * 回原）；rechargeRequired＝按所选池判定的引导充值支（源已一次选定，开模拟收银台、
 * 到账后经本端点重判即翻转——deductionSource/sourcePoolBalance＝收银台目标池
 * 推导素材）。
 */
@Schema(description = "「继续讨论」引导流判定回执：flipped＝已翻转回访谈；"
        + "rechargeRequired＝源池余额非正、引导充值（到账后重判即翻转，无需重走选择）")
public record ContinueDiscussionResponse(

        @Schema(description = "是否已翻转（本次或他路竞态既往——幂等成功语义）", example = "true")
        boolean flipped,

        @Schema(description = "源池余额非正、引导充值（false 且 flipped=false 不成立）", example = "false")
        boolean rechargeRequired,

        @Schema(description = "已选定扣减源码位（1=账户余额 2=项目余额）", example = "2")
        Integer deductionSource,

        @Schema(description = "源池余额快照（分；项目池从未入账为 null＝必空判定）", example = "5000")
        Long sourcePoolBalance) {
}
