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

import com.semgravio.aiplatform.business.project.application.BackofficeModelCatalogAppService;
import com.semgravio.aiplatform.business.project.application.BackofficeModelChannelAppService;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelProviderCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelProviderUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelProviderResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelProviderTraceResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeRemoteModelCatalogResponse;
import com.semgravio.aiplatform.business.project.domain.model.Operator;

/**
 * 后台模型供应商通道维护 REST 面（#367，spec #364-T3，ADR-0031；机机签名——
 * 五头 HMAC 强制闸，同 agent-configs 面）：通道 CRUD＋变更留痕。apiKey 写收
 * 全量值、读面（回执与留痕快照）一律掩码（尾 4 位）——全链零明文 key 回显、
 * 日志亦掩码。换通道、换 key、换 baseUrl 皆不发版不重启，下一轮命令构建即
 * 生效（模型实例缓存键含通道指纹）。错误码 PRJ_065~069/072。操作者透传头
 * {@code X-User-Id}/{@code X-User-Name} 写操作必留痕。
 */
@RestController
@RequestMapping("/api/backoffice/model-providers")
@RequireSignature
@Tag(name = "Backoffice Model Provider", description = "后台模型供应商通道维护：CRUD＋apiKey 掩码＋变更留痕（机机签名）")
public class BackofficeModelProviderController {

    private final BackofficeModelChannelAppService appService;

    private final BackofficeModelCatalogAppService catalogAppService;

    public BackofficeModelProviderController(BackofficeModelChannelAppService appService,
            BackofficeModelCatalogAppService catalogAppService) {
        this.appService = appService;
        this.catalogAppService = catalogAppService;
    }

    @GetMapping
    @Operation(summary = "通道清单读面（apiKey 一律掩码）",
            description = "全部通道行按键序；apiKey 只出掩码形（尾 4 位，null＝"
                    + "未配置）——接口回显面零明文 key。种子两通道：volcengine 主用"
                    + "（火山方舟）＋deepseek 官方备胎。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeModelProviderResponse>> providers() {
        return ApiResponse.ok(appService.providers());
    }

    @GetMapping("/{providerKey}")
    @Operation(summary = "通道单行读面（掩码形）",
            description = "该通道行（掩码形同清单）。寻址键即协议路由前缀"
                    + "（volcengine/deepseek 等）。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"PRJ_065"})
    public ApiResponse<BackofficeModelProviderResponse> provider(@PathVariable String providerKey) {
        return ApiResponse.ok(appService.provider(providerKey));
    }

    @PostMapping
    @Operation(summary = "通道创建（apiKey 收全量值，创建即留痕）",
            description = "新建接入通道：键名（＝协议路由前缀，OpenAI 兼容统一接、"
                    + "SDK 内置前缀走原生 provider）随体、已存在 409 PRJ_067；"
                    + "baseUrl 必填（PRJ_069）；apiKey 收全量值（null/空白＝暂不"
                    + "配置——装配按 PRJ_063 明确报错）；enabled 缺省 true。回执"
                    + "apiKey 已掩码。创建即落痕（old 全 null）。新通道接入＝建"
                    + "通道行＋档位行两步（档位面 POST /model-tiers），不动结构。"
                    + "X-User-Id/X-User-Name 透传头必留痕（缺头 400 PRJ_034）。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_067", "PRJ_069", "PRJ_034"})
    public ApiResponse<BackofficeModelProviderResponse> create(
            @RequestBody ModelProviderCreateCommand command) {
        return ApiResponse.ok(appService.createProvider(command, currentOperator()));
    }

    @PutMapping("/{providerKey}")
    @Operation(summary = "通道更新（换 key/换地址/停启用；留痕）",
            description = "PUT 全量语义：baseUrl 终态替换；apiKey 与 enabled 缺省＝"
                    + "保持现值（apiKey 读面只出掩码、缺省清空＝读一次即丢 key；"
                    + "enabled 是有意状态、缺省翻转会把停用通道在换 key 时意外复启"
                    + "——两肢皆显式写才动）。换 key 携全量新值（写面唯一明文入口）；"
                    + "清除 key 无写面（下线通道走 enabled=false——停用＝引用它的"
                    + "装配明确报错，不静默换道）。改 key/换地址下一轮命令构建即生效"
                    + "（模型实例缓存键含通道指纹——同模型串换 key 换地址＝新实例，"
                    + "进行中 run 不定格）。值面无动＝幂等回执不落痕；回滚＝留痕旧值"
                    + "快照写回（换 key 回滚＝操作者重输旧 key 全量值——留痕只出"
                    + "掩码，不作密钥找回面）。X-User-Id/X-User-Name 必留痕（缺头"
                    + " 400 PRJ_034）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_065", "PRJ_069", "PRJ_034"})
    public ApiResponse<BackofficeModelProviderResponse> update(@PathVariable String providerKey,
            @RequestBody ModelProviderUpdateCommand command) {
        return ApiResponse.ok(appService.updateProvider(providerKey, command, currentOperator()));
    }

    @DeleteMapping("/{providerKey}")
    @Operation(summary = "通道删除（被档位引用即拒）",
            description = "删除通道行；仍被档位行引用即 409 PRJ_072（先删改挂其下"
                    + "的档位行——悬空引用不留静默坑）。删成落痕（new 全 null，"
                    + "留痕跨行存活）。X-User-Id/X-User-Name 必留痕。需要机机"
                    + "签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_065", "PRJ_072", "PRJ_034"})
    public ApiResponse<Void> delete(@PathVariable String providerKey) {
        appService.deleteProvider(providerKey, currentOperator());
        return ApiResponse.ok();
    }

    @PostMapping("/{providerKey}/remote-models")
    @Operation(summary = "远端模型清单手动拉取（对照库内档位）",
            description = "按通道行真拉远端模型清单（OpenAI 兼容 GET {baseUrl}/models，"
                    + "方舟端点实测同形状）并与库内档位对照：条目 trackedTierKeys 空＝"
                    + "远端有库里无（建议入库）；staleTiers＝库内指向远端已无（提示"
                    + "过时）。每次全量真拉无缓存（手动同步——自动定时同步备案未来"
                    + "增强，ADR-0031）。勾选入库复用档位创建写口（POST /api/backoffice/"
                    + "model-tiers，modelName 填所选条目 modelId、语义键人工命名）。"
                    + "条目 status 如实转述（方舟：无 status＝活跃、Shutdown/Retiring＝"
                    + "下架/退场中）。上游不可用明确报错不静默（502 PRJ_077 带理由）；"
                    + "通道 key 空 400 PRJ_076；停用通道可拉（巡检语义）。纯读动作"
                    + "零留痕。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_065", "PRJ_076", "PRJ_077", "UNAUTHORIZED"})
    public ApiResponse<BackofficeRemoteModelCatalogResponse> pullRemoteModels(
            @PathVariable String providerKey) {
        return ApiResponse.ok(catalogAppService.pull(providerKey));
    }

    @GetMapping("/{providerKey}/traces")
    @Operation(summary = "通道变更留痕读面（历史可查；key 快照掩码）",
            description = "该通道全部变更留痕按时间倒序（最近先）。每痕＝一次实际"
                    + "变更（幂等重写不落痕）：前后全量值快照（apiKey 快照读面一律"
                    + "掩码——尾 4 位足以比对哪次换了哪把 key，不作密钥找回面）＋"
                    + "操作者＋时刻。创建痕 old 全 null、删除痕 new 全 null；"
                    + "append-only 跨行存活——行删除后留痕仍可读（空清单＝从未有行，"
                    + "行读面负责 404 判别）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeModelProviderTraceResponse>> traces(
            @PathVariable String providerKey) {
        return ApiResponse.ok(appService.providerTraces(providerKey));
    }

    /**
     * 当前操作者（#251 同款）：透传头经 RequestContext 读出落痕（admin 侧管理员
     * 标识，签名面明示信任）；写操作必留痕——缺头 PRJ_034 拦截。Id 两形转换在此
     * 一次完成。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
