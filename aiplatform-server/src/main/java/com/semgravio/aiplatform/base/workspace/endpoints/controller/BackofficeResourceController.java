package com.semgravio.aiplatform.base.workspace.endpoints.controller;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceResourceAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceOverviewResponse;
import com.semgravio.aiplatform.base.workspace.application.dto.response.BackofficeResourceSweepResponse;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;

/**
 * 后台资源面 REST（#489，/api/backoffice/resources 机机签名——五头 HMAC 强制闸，
 * 见 {@link com.semgravio.aiplatform.config.WebMvcConfig}；#473/ADR-0045：资源
 * 回收通道的观测与手动处置面）：资源大盘（overview——主机盘/docker 四类用量/
 * 无主计数，纯读库零 docker 调用）＋手动清扫（sweep——复用 #486 无主收编面，
 * 逐项处置报告＋append-only 留痕）。资源事实与收编面归 base.workspace（照
 * {@code BackofficeSedimentDraftController} 先例：base BC 可载后台面——本面无
 * business 侧事实，不设组合层）。错误码前缀 WSP_（环境故障 500 WSP_002——
 * docker CLI 自身失败，清扫未执行完不定格不留痕）。
 */
@RestController
@RequestMapping("/api/backoffice/resources")
@RequireSignature
@Tag(name = "Backoffice Resources", description = "后台资源面：资源大盘 overview＋手动清扫 sweep（机机签名）")
public class BackofficeResourceController {

    private final WorkspaceResourceAppService appService;

    public BackofficeResourceController(WorkspaceResourceAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/overview")
    @Operation(summary = "资源大盘（主机盘/docker 用量/无主计数）",
            description = "#473 资源回收通道的观测面：主机磁盘（平台进程所在文件系统，"
                    + "hostPath＝与 df 对账的路径锚）＋docker 四类用量（镜像/容器/本地卷/"
                    + "build cache 的计数与体量——体量按十进制单位换算与 docker 显示同基）"
                    + "＋无主资源计数（docker 侧 ws-<id>/vol-ws-<id> 对注册表求差，ADR-0045"
                    + " 准绳——「可回收」口径不进本面）＋在册失联计数（只标记待人工裁决，"
                    + "#488）＋最近一次手动清扫（收编结果面）。数据取扫描轮采样落库"
                    + "（#487 采样面轮末主机帧）与清扫留痕，<b>请求路径零 docker 调用、"
                    + "秒级返回</b>；sampledAt＝新鲜度标注（未采样的首个扫描窗内各面如实"
                    + " null——「没有帧」≠「零」；docker 不可达轮 docker 面各列 null——"
                    + "「缺测」≠「零」）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"NOT_FOUND"})
    public ApiResponse<BackofficeResourceOverviewResponse> resourceOverview() {
        return ApiResponse.ok(appService.overview());
    }

    @PostMapping("/sweep")
    @Operation(summary = "手动清扫（无主收编，逐项处置报告）",
            description = "按需对账而不是等扫描轮（#473 故事 8）：手动触发一次无主收编，"
                    + "<b>复用 #486 扫描轮收编面同一实现同一准绳</b>——保留集＝注册表全量"
                    + "编号（在册一律不动，休眠保卷是用户数据 ADR-0016；在册但项目行已删"
                    + "＝在册失联只标记，裁决出口归 reclaim 一键回收）、宽限线＝触发时刻减"
                    + " 10 分钟（新建「资源已建、记录未落」窗口保护）、点名删非 prune"
                    + "（docker system df「可回收」不作判定、volume prune 永不进任何通道——"
                    + "红线）。回执＝逐项处置报告：收编了什么（SWEPT）、跳过什么为什么"
                    + "（SKIPPED：REGISTERED＝在册/GRACE_WINDOW＝宽限窗内/"
                    + "TIMESTAMP_UNREADABLE＝时刻解析不出）、删除未成什么（FAILED——下轮"
                    + "扫描再收）；库不可达（registryReachable=false）一刀未动（准绳无从"
                    + "构成绝不按「全部无主」动手）。手动清扫不受休眠总开关静默（管理员"
                    + "显式动作）。X-User-Id/X-User-Name 透传头落痕（append-only 留痕行＋"
                    + "报告 JSON 全量落档，缺头落空 NULL——照沙箱动作面口径）。docker CLI"
                    + "自身失败 500 WSP_002（未执行完的清扫不留痕不定格）。需要机机签名"
                    + "（五头 HMAC），无签名 401")
    @ErrorCodes({"WSP_002"})
    public ApiResponse<BackofficeResourceSweepResponse> sweep() {
        return ApiResponse.ok(appService.sweep(currentOperator(), LocalDateTime.now()));
    }

    /**
     * 当前操作者（#489）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕清扫行（admin 侧管理员标识，签名面明示信任、不校验
     * 真实性）；缺头/无上下文为 {@code null}——落空口径（照沙箱动作面先例）。
     * Id 两形转换在此一次完成（上下文 Long → 外域标识字符串，存储不混型）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
