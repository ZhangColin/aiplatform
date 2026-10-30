package com.semgravio.aiplatform.business.project.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.project.application.ProductionHandoffAppService;
import com.semgravio.aiplatform.business.project.application.dto.command.ProductionHandoffActionCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.SetNeedsUserSupplementCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.UpdateProductionHandoffCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeProductionHandoffResponse;
import com.semgravio.aiplatform.business.project.domain.error.ProjectMessage;
import com.semgravio.aiplatform.business.project.domain.model.Operator;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 后台生产承接 REST 面（#338 交付承接，机机签名——五头 HMAC 强制闸，admin
 * 页面归跨仓 semgravio-admin#100）：承接详情（含留痕时间线）＋信息更新
 * （排期/联系人/进展/下一步）＋推进（待安排生产 → 生产中）＋「需用户补充」
 * 置位/清零——每次动作依据必填、操作人随行 append-only 留痕。承接创建无人工
 * 口（开工判定系统触发、只建一次）。错误码前缀 PRJ_（承接 PRJ_055、状态守卫
 * PRJ_056、依据 PRJ_057、超长 PRJ_058、操作者 PRJ_059、项目 PRJ_001）。
 */
@RestController
@RequestMapping("/api/backoffice/projects/{projectId}/production-handoff")
@RequireSignature
@Tag(name = "Backoffice Production", description = "后台生产承接：详情 / 信息更新 / 推进 / 需用户补充置位（机机签名）")
public class BackofficeProductionController {

    private final ProductionHandoffAppService appService;

    public BackofficeProductionController(ProductionHandoffAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "生产承接详情（承接全量面）",
            description = "按项目寻址（承接挂项目唯一）：承接字段（交付联系人/下一步说明/进展更新/"
                    + "预计开始时间——可空段如实 null，呈现层「仅真实值显示」）＋两态（1=待安排"
                    + "生产 2=生产中）＋动作留痕时间线（新 → 旧，append-only 全量——动作/两侧"
                    + "状态/依据/操作者/时间；创建行＝开工判定系统操作者）。无承接记录 404 "
                    + "PRJ_055（开工判定未触发）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_055"})
    public ApiResponse<BackofficeProductionHandoffResponse> detail(@PathVariable String projectId) {
        return ApiResponse.ok(appService.detailForBackoffice(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @PostMapping("/update")
    @Operation(summary = "信息更新（整组替换，依据必填留痕）",
            description = "交付联系人/下一步说明/进展更新（人工措辞、不上工程术语）/预计开始时间"
                    + "整组替换——未录入段传 null 即清空（预计开始时间清空＝回待安排呈现，「仅"
                    + "真实值显示」）。状态不动（留痕行 from=to）。依据必填 400 PRJ_057；字段"
                    + "超长 400 PRJ_058；操作者必携 400 PRJ_059（X-User-Id/X-User-Name 透传头）；"
                    + "无承接 404 PRJ_055。需要机机签名")
    @ErrorCodes({"PRJ_055", "PRJ_057", "PRJ_058", "PRJ_059"})
    public ApiResponse<BackofficeProductionHandoffResponse> update(@PathVariable String projectId,
            @RequestBody UpdateProductionHandoffCommand command) {
        return ApiResponse.ok(appService.update(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND),
                command.contact(), command.nextStep(), command.progressNote(),
                command.estimatedStartAt(), currentOperator(), command.basis()));
    }

    @PostMapping("/advance")
    @Operation(summary = "推进（待安排生产 → 生产中，依据必填留痕）",
            description = "两态唯一迁移边（生产中是稳态，再推进 409 PRJ_056）。依据必填 400 "
                    + "PRJ_057；操作者必携 400 PRJ_059；无承接 404 PRJ_055。需要机机签名")
    @ErrorCodes({"PRJ_055", "PRJ_056", "PRJ_057", "PRJ_059"})
    public ApiResponse<BackofficeProductionHandoffResponse> advance(@PathVariable String projectId,
            @RequestBody ProductionHandoffActionCommand command) {
        return ApiResponse.ok(appService.advance(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND),
                command.basis(), currentOperator()));
    }

    @PostMapping("/needs-user-supplement")
    @Operation(summary = "「需用户补充」置位/清零（前台六态盖帽标志）",
            description = "生产承接期间需用户补资料/确认事项时置位（required=true——前台六态盖帽"
                    + "「需用户补充」，处理入口在对话区沟通）；处理完清零（required=false）。"
                    + "置位语义归后台人工判定，平台只落标志。项目不存在 404 PRJ_001。"
                    + "需要机机签名")
    @ErrorCodes({"PRJ_001"})
    public ApiResponse<Void> setNeedsUserSupplement(@PathVariable String projectId,
            @RequestBody SetNeedsUserSupplementCommand command) {
        appService.setNeedsUserSupplement(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), command.required());
        return ApiResponse.ok();
    }

    /**
     * 当前操作者（口径同 {@code BackofficeContractController#currentOperator}）：
     * {@code X-User-Id}/{@code X-User-Name} 透传头经 RequestContext 读出落痕
     * （admin 侧管理员标识，签名面明示信任）；缺头/无上下文为 {@code null}——
     * 承接动作以操作者为审计正身，{@code null} 在聚合守卫被 PRJ_059 拒。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
