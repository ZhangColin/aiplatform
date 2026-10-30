package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.project.application.BackofficeModelChannelAppService;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelTierCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelTierUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelTierResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelTierTraceResponse;
import com.semgravio.aiplatform.business.project.domain.model.Operator;

/**
 * 后台模型档位维护 REST 面（#367，spec #364-T3，ADR-0031；机机签名——五头 HMAC
 * 强制闸，同 agent-configs 面）：档位 CRUD＋变更留痕。语义键/实际模型名/所属
 * 通道/显示名/启用态；被智能体运营配置与路由强档引用的即语义键——改档写口
 * （agent-configs 面）对本面 enabled=true 行集即时校验放行。错误码
 * PRJ_066/068/070/071/073。操作者透传头写操作必留痕。
 */
@RestController
@RequestMapping("/api/backoffice/model-tiers")
@RequireSignature
@Tag(name = "Backoffice Model Tier", description = "后台模型档位维护：CRUD＋变更留痕（机机签名）")
public class BackofficeModelTierController {

    private final BackofficeModelChannelAppService appService;

    public BackofficeModelTierController(BackofficeModelChannelAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "档位清单读面（绑定下拉数据源）",
            description = "全部档位行按语义键序：语义键/实际模型名/所属通道/显示名/"
                    + "启用态＋最近写者。绑定改档（agent-configs 面 PUT）的即时校验"
                    + "放行值＝本清单 enabled=true 的语义键集。需要机机签名（五头 "
                    + "HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeModelTierResponse>> tiers() {
        return ApiResponse.ok(appService.tiers());
    }

    @GetMapping("/{tierKey}")
    @Operation(summary = "档位单行读面",
            description = "该档位行。寻址键即绑定引用腿（flash/pro/doubao-flagship "
                    + "这类运营短名）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_066"})
    public ApiResponse<BackofficeModelTierResponse> tier(@PathVariable String tierKey) {
        return ApiResponse.ok(appService.tier(tierKey));
    }

    @PostMapping
    @Operation(summary = "档位创建（建行即可被绑定）",
            description = "新建档位：语义键随体（已存在 409 PRJ_068）；所属通道必须"
                    + "已有行（PRJ_071——先建通道再挂档位）；实际模型名必填"
                    + "（PRJ_070）；显示名可空（＝回落语义键）；enabled 缺省 true。"
                    + "创建即落痕（old 全 null）。模型版本升级（方舟名带日期后缀、"
                    + "季度级更新）＝改档位行指向，全部引用自动跟上。X-User-Id/"
                    + "X-User-Name 透传头必留痕（缺头 400 PRJ_034）。需要机机签名"
                    + "（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_068", "PRJ_070", "PRJ_071", "PRJ_034"})
    public ApiResponse<BackofficeModelTierResponse> create(
            @RequestBody ModelTierCreateCommand command) {
        return ApiResponse.ok(appService.createTier(command, currentOperator()));
    }

    @PutMapping("/{tierKey}")
    @Operation(summary = "档位更新（换通道/升模型版本/改显示名/停启用；留痕）",
            description = "PUT 全量语义：providerKey/modelName/displayName 终态替换"
                    + "（displayName 可空＝清显示名）；enabled 缺省＝保持现值"
                    + "（停用档换模型名/换通道不被意外复启，显式 true/false 才翻转）。"
                    + "停用＝既有绑定装配报错 PRJ_061、新绑定被即时校验拒绝 PRJ_075"
                    + "（临时下线用停用不删行）。改指向下一轮命令构建即生效（装配"
                    + "动态查库）。值面无动＝幂等回执不落痕；回滚＝留痕旧值快照写回"
                    + "（即一次新变更、留新痕）。X-User-Id/X-User-Name 必留痕（缺头"
                    + " 400 PRJ_034）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_066", "PRJ_070", "PRJ_071", "PRJ_034"})
    public ApiResponse<BackofficeModelTierResponse> update(@PathVariable String tierKey,
            @RequestBody ModelTierUpdateCommand command) {
        return ApiResponse.ok(appService.updateTier(tierKey, command, currentOperator()));
    }

    @DeleteMapping("/{tierKey}")
    @Operation(summary = "档位删除（被绑定引用即拒）",
            description = "删除档位行；仍被智能体运营配置绑定（model_id/"
                    + "routing_strong_model_id 任一＝该语义键）即 409 PRJ_073——先"
                    + "改绑或清空绑定再删（悬空引用不留静默坑；临时下线用停用不删"
                    + "行）。删成落痕（new 全 null，留痕跨行存活）。X-User-Id/"
                    + "X-User-Name 必留痕。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_066", "PRJ_073", "PRJ_034"})
    public ApiResponse<Void> delete(@PathVariable String tierKey) {
        appService.deleteTier(tierKey, currentOperator());
        return ApiResponse.ok();
    }

    @GetMapping("/{tierKey}/traces")
    @Operation(summary = "档位变更留痕读面（历史可查，回滚依据）",
            description = "该档位全部变更留痕按时间倒序（最近先）：前后全量值快照＋"
                    + "操作者＋时刻；创建痕 old 全 null、删除痕 new 全 null；"
                    + "append-only 跨行存活——行删除后留痕仍可读（空清单＝从未有行，"
                    + "行读面负责 404 判别）。回滚＝把某痕旧值快照写回（行在＝PUT、"
                    + "行已删＝按旧值重建行，皆一次新变更留新痕，不做版本树）。需要"
                    + "机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeModelTierTraceResponse>> traces(
            @PathVariable String tierKey) {
        return ApiResponse.ok(appService.tierTraces(tierKey));
    }

    /** 当前操作者（#251 同款）：透传头落痕，缺头 PRJ_034 拦截。 */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
