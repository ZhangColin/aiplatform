package com.aieducenter.aiplatform.base.knowledge.endpoints.controller;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.core.context.RequestContext;
import com.cartisan.core.exception.ApplicationException;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.knowledge.application.BackofficeKnowledgeAppService;
import com.aieducenter.aiplatform.base.knowledge.application.dto.response.BackofficeMaterialDetailResponse;
import com.aieducenter.aiplatform.base.knowledge.application.dto.response.BackofficeMaterialSummaryResponse;
import com.aieducenter.aiplatform.base.knowledge.domain.enums.MaterialStatus;
import com.aieducenter.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.aieducenter.aiplatform.base.knowledge.domain.model.Operator;

/**
 * 后台知识素材管理 REST 面（#166 知识库管理，机机签名）：管理单元＝素材＝
 * 项目 × 素材类型（v1 即一个项目的 PRD，非块）——清单 / 详情 / 停用⇄启用 /
 * 删除。内容面零写（不编辑、不手动新增，沉淀唯一触发点不动），治理手段＝
 * 停用/删除。cartisan-openapi 五头 HMAC，类级 {@code @RequireSignature} 强制闸；
 * 该前缀经 WebMvcConfig 排除会话拦截。错误码前缀 KNW_（端点面本票启用：素材
 * 不存在 KNW_005、操作者缺 KNW_006、过滤参数 KNW_007）。
 */
@RestController
@RequestMapping("/api/backoffice/materials")
@RequireSignature
@Tag(name = "Backoffice Materials", description = "后台知识素材管理：清单 / 详情 / 停用⇄启用 / 删除（机机签名）")
public class BackofficeMaterialController {

    private final BackofficeKnowledgeAppService appService;

    public BackofficeMaterialController(BackofficeKnowledgeAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "知识素材清单（三过滤维度，分页）",
            description = "治理工作清单：新沉淀在前。三维度可组合、均可缺省（缺省＝全量）："
                    + "① status 状态单选，Integer code（1=启用 2=停用；缺省＝全部——与"
                    + "订单清单状态多选有意不同，照项目面状态单选先例）；② sunkFrom/"
                    + "sunkTo 沉淀时间区间（首沉淀时间，闭区间含两端，ISO-8601 Instant，"
                    + "如 2026-09-01T00:00:00Z）；③ projectId 来源项目 id 精确（登记面"
                    + "字符串，查无＝空清单 200）。不做内容模糊与账号维度。排序服务端"
                    + "定死＝沉淀时间倒序（id 倒序稳定）。行带最近管理动作操作者"
                    + "（未治理过为 null）。page 1 基（缺省 1）、size 缺省 20（上界 100）。"
                    + "过滤参数绑定失败（非法状态 code/时间/分页值）400 KNW_007。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"KNW_007"})
    public ApiResponse<PageResponse<BackofficeMaterialSummaryResponse>> materials(
            @RequestParam(required = false) MaterialStatus status,
            @RequestParam(required = false) Instant sunkFrom,
            @RequestParam(required = false) Instant sunkTo,
            @RequestParam(required = false) String projectId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(appService.materials(status, sunkFrom, sunkTo, projectId, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "知识素材详情（元数据＋PRD 全文）",
            description = "读内容判治理：元数据（素材类型/来源项目引用/沉淀时间（首沉淀，"
                    + "重沉淀与治理动作不改）/状态/最近管理动作操作者）＋素材全文＝块按"
                    + " seq 以空行拼接（段落级重组：超长单段硬切的切点呈现为段落断，"
                    + "内容无损）。来源项目引用容缺直读登记面（不校验项目存在，缺档"
                    + "不炸）。素材不存在（含畸形 id）404 KNW_005。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"KNW_005"})
    public ApiResponse<BackofficeMaterialDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(parseMaterial(id)));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用素材（可逆开关）",
            description = "素材全部块退出生成命中（检索状态过滤 #153 机制面恒开），重沉淀"
                    + "不复活（登记状态跨幂等替换存活）；拿不准的内容先摘除、误伤可经"
                    + " enable 恢复。X-User-Id/X-User-Name 透传头自动落痕素材级"
                    + "（最近管理动作操作者）——缺头 400 KNW_006：知识治理动作必留痕，"
                    + "与单价表缺头落空有意不同（单价表有种子脚本无头通道，知识治理"
                    + "无此通道）。重复停用幂等（操作者留最近一次）。素材不存在"
                    + "（含畸形 id）404 KNW_005。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"KNW_005", "KNW_006"})
    public ApiResponse<BackofficeMaterialSummaryResponse> disable(@PathVariable String id) {
        return ApiResponse.ok(appService.disable(parseMaterial(id), currentOperator()));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "启用素材（恢复命中）",
            description = "停用的可逆侧：素材全部块恢复参与生成命中。X-User-Id/X-User-Name"
                    + " 透传头自动落痕素材级（缺头 400 KNW_006，口径同 disable）。"
                    + "重复启用幂等。素材不存在（含畸形 id）404 KNW_005。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"KNW_005", "KNW_006"})
    public ApiResponse<BackofficeMaterialSummaryResponse> enable(@PathVariable String id) {
        return ApiResponse.ok(appService.enable(parseMaterial(id), currentOperator()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除素材（治理移除，不可逆）",
            description = "治理移除素材登记行与全部块、不动来源项目（管理删除与项目删除"
                    + "级联正交）；清单/详情/检索均不可见。无行可留、不留痕（全局审计"
                    + "流水不建——admin 侧自有操作日志）。回执＝删除前终态（确认移除"
                    + "了什么）。素材不存在（含畸形 id、重复删除）404 KNW_005。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"KNW_005"})
    public ApiResponse<BackofficeMaterialSummaryResponse> delete(@PathVariable String id) {
        return ApiResponse.ok(appService.delete(parseMaterial(id)));
    }

    /**
     * 当前操作者（#166）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性）。与单价表不同处：缺头不为落空——域面守卫 KNW_006 拦截（治理动作必
     * 留痕）。Id 两形转换在此一次完成（上下文 Long → 外域标识字符串）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }

    /**
     * 寻址解析：路径段（TSID 十进制字符串）→ long。非数值/非正数即不存在的
     * 标识，语义上同 404（与 ProjectIds/parseEntry 口径一致）。
     */
    private static long parseMaterial(String materialId) {
        try {
            long parsed = Long.parseLong(materialId);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 非数值 → 落到下方统一 404
        }
        throw new ApplicationException(KnowledgeMessage.KNOWLEDGE_MATERIAL_NOT_FOUND);
    }

    /**
     * 清单参数绑定失败的兜底：非法状态 code/时间/分页值在本层就是 400，映射回
     * KNW_007 保持错误码前缀口径（同 BackofficeOrderController ORD_010 形制）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleFilterMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(KnowledgeMessage.KNOWLEDGE_MATERIAL_FILTER_INVALID));
    }
}
