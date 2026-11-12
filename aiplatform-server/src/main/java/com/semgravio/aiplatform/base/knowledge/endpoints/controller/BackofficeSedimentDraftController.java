package com.semgravio.aiplatform.base.knowledge.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.semgravio.aiplatform.base.knowledge.application.SedimentDraftAppService;
import com.semgravio.aiplatform.base.knowledge.application.dto.command.SedimentDraftRejectCommand;
import com.semgravio.aiplatform.base.knowledge.application.dto.command.SedimentDraftRelabelCommand;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeSedimentDraftDetailResponse;
import com.semgravio.aiplatform.base.knowledge.application.dto.response.BackofficeSedimentDraftSummaryResponse;
import com.semgravio.aiplatform.base.knowledge.domain.enums.SedimentDraftStatus;
import com.semgravio.aiplatform.base.knowledge.domain.error.KnowledgeMessage;
import com.semgravio.aiplatform.base.knowledge.domain.model.Operator;
import com.semgravio.aiplatform.support.Tsid;

/**
 * 后台「数据标注」REST 面（#464，机机签名——五头 HMAC 强制闸，见
 * {@code WebMvcConfig}）：采集层沉淀草稿的人审队列＋三键（激活/拒绝/改标签）。
 * 人审是知识入库的主质量闸（未审核草稿不参与任何检索）；三键终态语义照技能
 * 草稿审核先例（ADR-0022）：CAS、已审结拒、拒绝理由必填、操作者必留痕。
 * 错误码前缀 KNW_（草稿不存在 KNW_008、已审结 KNW_009、理由空 KNW_010、
 * 类别非法 KNW_011、目标类别在途满 KNW_012、操作者缺 KNW_006）。
 */
@RestController
@RequestMapping("/api/backoffice/sediment-drafts")
@RequireSignature
@Tag(name = "Backoffice Sediment Drafts", description = "后台数据标注：沉淀草稿标注队列＋激活/拒绝/改标签三键（机机签名）")
public class BackofficeSedimentDraftController {

    private final SedimentDraftAppService draftAppService;

    public BackofficeSedimentDraftController(SedimentDraftAppService draftAppService) {
        this.draftAppService = draftAppService;
    }

    @GetMapping
    @Operation(summary = "标注队列（按项目/类别/状态过滤，分页）",
            description = "「数据标注」人审队列（#464）：采集层自荐的沉淀草稿，三过滤"
                    + "维度可组合、均可缺省（缺省＝全量）：① projectId 来源项目 id "
                    + "精确（数值，查无＝空清单 200；畸形值宽松解析＝缺省不过滤）；② kind "
                    + "类别单选（CASE/MODULE/"
                    + "RULE，大小写不敏感，非法 400 KNW_011 带合法取值表）；③ status "
                    + "状态单选，Integer code（1=在途 2=已激活 3=已拒绝；非法 code 400 "
                    + "带合法取值表）。排序服务端定死＝自荐时间倒序（id 倒序稳定）。行"
                    + "字段：类别/标题/摘要＋血统三件（来源项目/run/槽位）＋扫描判定＋"
                    + "在途序号（每项目每 kind 上限 3 的队列可见性）＋状态＋自荐时刻；"
                    + "正文与扫描 findings 在详情。page 1 基（缺省 1）、size 缺省 20"
                    + "（上界 100）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"BAD_REQUEST", "KNW_011"})
    public ApiResponse<PageResponse<BackofficeSedimentDraftSummaryResponse>> queue(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) SedimentDraftStatus status,
            Pagination pagination) {
        return ApiResponse.ok(draftAppService.queue(Tsid.parseOrNull(projectId), kind, status,
                pagination));
    }

    @GetMapping("/{id}")
    @Operation(summary = "标注详情（正文＋扫描回执＋终态留痕，审核面）",
            description = "草稿全文按数据标注口径判读（#464）：清单行同形字段＋正文"
                    + "全文（激活原样升格不改稿——审核面所见即入库内容）＋载荷＋扫描 "
                    + "findings 逐条留档（patternId/severity/category/file/line/"
                    + "matchText/description，空列表＝无发现）＋终态留痕字段（操作者/"
                    + "时刻/拒绝理由——已激活/已拒绝回填，在途恒 null）。任意状态可查"
                    + "（终态留档）。id 取清单行原值（TSID 十进制串）；草稿不存在"
                    + "（含未寻址/畸形 TSID）404 KNW_008。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"KNW_008"})
    public ApiResponse<BackofficeSedimentDraftDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(draftAppService.draft(Tsid.resolve(id,
                KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "激活草稿（人审采纳，原样升格不改稿）",
            description = "「数据标注」三键之一（#464）：在途翻已激活＋操作者留痕"
                    + "（CAS——并发审结零行命中 409 KNW_009）。激活即出标注队列、释放"
                    + "在途名额，并升格 knw_materials（#465——kind 随草稿、正文段落级"
                    + "分块入库、与来源项目脱钩成孤儿沉淀）。草稿须在途（已审结 409 "
                    + "KNW_009——终态不可再审，重提为新草稿）；草稿不存在 404 KNW_008；"
                    + "缺操作者 400 KNW_006（X-User-Id/X-User-Name 透传头，标注动作必"
                    + "留痕）。回执＝终态详情。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"KNW_006", "KNW_008", "KNW_009"})
    public ApiResponse<BackofficeSedimentDraftDetailResponse> activate(@PathVariable String id) {
        return ApiResponse.ok(draftAppService.activate(
                Tsid.resolve(id, KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND),
                currentOperator()));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "拒绝草稿（终态留档，理由必填）",
            description = "「数据标注」三键之一（#464）：理由必填（空白 400 KNW_010——"
                    + "终态留档拒绝须有据，库级 CHECK 双锁）＋在途翻已拒绝＋操作者留痕"
                    + "（CAS 同激活）。拒绝即终态：不再列标注队列、详情仍可查（留档）；"
                    + "不改稿不复活——重提只能靠未来会话产生新草稿。草稿须在途（已审结 "
                    + "409 KNW_009）；草稿不存在 404 KNW_008；缺操作者 400 KNW_006。"
                    + "回执＝终态详情（留痕形状直读）。需要机机签名（五头 HMAC），无"
                    + "签名 401")
    @ErrorCodes({"KNW_006", "KNW_008", "KNW_009", "KNW_010"})
    public ApiResponse<BackofficeSedimentDraftDetailResponse> reject(@PathVariable String id,
            @RequestBody(required = false) SedimentDraftRejectCommand command) {
        return ApiResponse.ok(draftAppService.reject(
                Tsid.resolve(id, KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND),
                command == null ? null : command.reason(), currentOperator()));
    }

    @PostMapping("/{id}/relabel")
    @Operation(summary = "改标签（在途内调整类别，占目标类别在途名额）",
            description = "「数据标注」三键之一（#464）：在途草稿调整类别（一期合法"
                    + "取值 CASE/MODULE/RULE——非法 400 KNW_011；同类别幂等 no-op）；"
                    + "占目标类别在途序号最低空位（每项目每 kind 上限 3 的结构性约束"
                    + "跨类别生效——目标类别满 409 KNW_012）。不翻终态、不动正文——纯"
                    + "队列分诊修正。草稿须在途（已审结 409 KNW_009）；草稿不存在 404 "
                    + "KNW_008；缺操作者 400 KNW_006（标注动作必留痕）。回执＝详情（新"
                    + "类别直读）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"KNW_006", "KNW_008", "KNW_009", "KNW_011", "KNW_012"})
    public ApiResponse<BackofficeSedimentDraftDetailResponse> relabel(@PathVariable String id,
            @RequestBody(required = false) SedimentDraftRelabelCommand command) {
        return ApiResponse.ok(draftAppService.relabel(
                Tsid.resolve(id, KnowledgeMessage.KNOWLEDGE_SEDIMENT_DRAFT_NOT_FOUND),
                command == null ? null : command.kind(), currentOperator()));
    }

    /**
     * 当前操作者（#464）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性）。三键必留痕——缺头不为落空，域面守卫 KNW_006 拦截（口径同素材治理）。
     * Id 两形转换在此一次完成（上下文 Long → 外域标识字符串）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
