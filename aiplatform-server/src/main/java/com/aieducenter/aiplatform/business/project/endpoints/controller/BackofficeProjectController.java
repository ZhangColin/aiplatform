package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.business.project.application.BackofficeProjectAppService;
import com.aieducenter.aiplatform.business.project.application.ConversationHistoryAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectVersionAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeProjectSummaryResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ConversationEntryResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFileContentResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFilesPackage;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFilesResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.VersionResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 后台项目 REST 面（#159/#162 项目域只读，机机签名——五头 HMAC 强制闸，见
 * {@link com.aieducenter.aiplatform.config.WebMvcConfig}）。清单＝状态三档单选＋
 * 创建时间区间＋账号（externalId 入参服务端换算）＋项目 id 精确（用户报障贴
 * 链接场景），排序 id 倒序定死不开放；详情带订单引用（activeOrder/latestOrder
 * 照用户面先例，与订单域互链）。深读三组（#162）＋文件区（#163）口径照用户面
 * per-project 端点：对话史（全量同序）、PRD（未产出 404 照搬）、版本列表＋详情
 * （正本＝容器内 git log、详情锚定收尾卡＋rollbackFrom）、文件树＋文件内容
 * （拒机密/逃逸、1MiB 上限、非文本拒——补未下单项目的代码排障缺口，文件区挂
 * 项目不挂订单）——同源委托既有应用服务（不复用 BFF 会话端点、不复制读逻辑，
 * 口径由构造不漂移）。归档项目全状态照读（清单缺省含），已删项目真删无墓碑——
 * 任何后台读面自然不可见。错误码前缀 PRJ_（项目不存在 PRJ_001、PRD 未产出
 * PRJ_015、版本不存在 PRJ_028、文件区守卫 PRJ_020~023），环境故障 WSP_002 照
 * 订单源码包先例跨前缀透传。只写操作不进本域（v1 只读）。
 */
@RestController
@RequestMapping("/api/backoffice/projects")
@RequireSignature
@Tag(name = "Backoffice Projects", description = "后台项目：清单检索 / 详情带订单引用 / 对话史 / PRD / 版本列表与详情 / 文件区只读（机机签名，只读）")
public class BackofficeProjectController {

    private final BackofficeProjectAppService appService;
    private final ConversationHistoryAppService conversationHistoryAppService;
    private final ProjectQueryAppService projectQueryAppService;
    private final ProjectVersionAppService versionAppService;

    public BackofficeProjectController(BackofficeProjectAppService appService,
            ConversationHistoryAppService conversationHistoryAppService,
            ProjectQueryAppService projectQueryAppService,
            ProjectVersionAppService versionAppService) {
        this.appService = appService;
        this.conversationHistoryAppService = conversationHistoryAppService;
        this.projectQueryAppService = projectQueryAppService;
        this.versionAppService = versionAppService;
    }

    @GetMapping
    @Operation(summary = "项目清单（四维检索，分页）",
            description = "监管工作清单：新项目在前（TSID 倒序）。四维可组合、均可缺省（缺省＝全量）："
                    + "① status 状态三档单选，Integer code（1=进行中 3=已归档；缺省＝全部，"
                    + "归档项目缺省含——照用户面状态过滤先例，与订单清单状态多选有意不同）；"
                    + "② createdFrom/createdTo 创建时间区间（ISO-8601，含两端，"
                    + "如 2026-09-01T00:00:00）；③ externalId 归属账号（对外正身，"
                    + "服务端换算，换算不到＝该用户无建档→空清单 200）；④ projectId "
                    + "项目 id 精确（TSID 十进制，用户报障贴链接场景；查无/非数值→"
                    + "空清单 200）。行带 ownerExternalId/ownerDisplayName（归属账号"
                    + "缺档/无主为 null；externalId＝账号档案读口的寻址键）。"
                    + "不做项目名模糊。page 1 基（缺省 1）、size 缺省 20（上界 100），"
                    + "排序服务端定死不开放。已删项目不可见（真删无墓碑）。"
                    + "过滤参数绑定失败走框架统一信封：非法状态 code 400（带合法取值表）、"
                    + "非数值分页 400（带字段明细）、时间类型不匹配 404。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"BAD_REQUEST", "NOT_FOUND"})
    public ApiResponse<PageResponse<BackofficeProjectSummaryResponse>> projects(
            @RequestParam(required = false) ProjectStatusFilter status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
            @RequestParam(required = false) String externalId,
            @RequestParam(required = false) String projectId,
            Pagination pagination) {
        return ApiResponse.ok(appService.projects(status, createdFrom, createdTo,
                externalId, projectId, pagination));
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情（后台面，带订单引用＋成本指针）",
            description = "清单字段全量＋归属账号 externalId＋显示名（缺档/无主为 null）"
                    + "＋订单引用（与订单域"
                    + "互链）：activeOrder＝未终结订单摘要（有值即冻结迭代，1=待报价 "
                    + "2=已报价），latestOrder＝最近一张任意状态订单（支付归档后 "
                    + "activeOrder 转空、本字段承接完整记录取单面；从未下单两者皆空）"
                    + "——照用户面先例。costSummary＝成本汇总指针（项目全量口径："
                    + "总成本按币种分桶直读不折算＋unpriced 有无标记——true 时成本"
                    + "不完整；无用量＝空 cost＋false 明确空态；明细下钻走成本域"
                    + "端点 /api/backoffice/costs/projects/{id}，同数据源）。"
                    + "归档项目照读（工作区保留）；已删项目不可见"
                    + "（真删无墓碑）。需要机机签名；项目不存在 404 PRJ_001")
    @ErrorCodes({"PRJ_001"})
    public ApiResponse<BackofficeProjectDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @GetMapping("/{id}/conversation")
    @Operation(summary = "对话史（后台面，全量同序）",
            description = "口径照用户面对话史读口（同源委托同一应用服务——同源同序由构造保证）："
                    + "用户发言 / 智能体回复 / 问答卡 / 问答作答 / 收尾卡 / 平台轻引导，"
                    + "按写入序（id 升序 = 对话序）全量返回；过程明细（解说段 / 动作卡流水）"
                    + "不在其中（收尾卡已是凝聚物）。kind Integer code（1=user 2=agent "
                    + "3=question 4=answer 5=closing 6=guide）+ kindName 中文名随行"
                    + "（#186：枚举出口配 *Name，后台直读零映射）；question = question-raised "
                    + "事件载荷原样（answered=false 即挂起待答）；closing = run-finish 收口"
                    + "扩载同载荷（版本详情锚定的权威事实）。归档项目照读（对话区只读终态）"
                    + "——排障时了解用户与系统的交互过程。需要机机签名；"
                    + "项目不存在 404 PRJ_001")
    @ErrorCodes({"PRJ_001"})
    public ApiResponse<List<ConversationEntryResponse>> conversation(@PathVariable String id) {
        return ApiResponse.ok(conversationHistoryAppService.read(Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @GetMapping("/{id}/prd")
    @Operation(summary = "PRD 读（后台面，工作区直读）",
            description = "口径照用户面 PRD 读口：直读项目 dev 工作区的 docs/PRD.md（事实源，"
                    + "v1 无版本链只最新版），返回 markdown 正文 + updatedAt（文件 mtime，"
                    + "ISO-8601 秒精度）——了解交付物内容。未产出（工作区无该文件）"
                    + "404 PRJ_015，与项目不存在的 PRJ_001 区分。归档项目照读"
                    + "（工作区保留）。需要机机签名；环境故障（docker exec 自身失败）"
                    + "500 WSP_002")
    @ErrorCodes({"PRJ_001", "PRJ_015", "WSP_002"})
    public ApiResponse<PrdResponse> prd(@PathVariable String id) {
        return ApiResponse.ok(projectQueryAppService.prd(Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "版本列表（后台面，新→旧）",
            description = "口径照用户面版本读口：git log 即版本序列（正本＝容器内 git log 直读，"
                    + "无库表）——每轮编码 run 收口自动成版（commit 主题 = 收口摘要、"
                    + "Run-Id trailer 锚定收尾卡）；回滚版本 runId 空、rollbackFrom 锚定"
                    + "源版本。排序新→旧定死；零版本（尚无收口）= 空列表非错误。"
                    + "「上周五还好好的」按版本锚点回看的入口。归档项目照读。"
                    + "需要机机签名；项目不存在 404 PRJ_001；环境故障 500 WSP_002")
    @ErrorCodes({"PRJ_001", "WSP_002"})
    public ApiResponse<List<VersionResponse>> versions(@PathVariable String id) {
        return ApiResponse.ok(versionAppService.list(Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @GetMapping("/{id}/versions/{ref}")
    @Operation(summary = "版本详情（后台面，锚定收尾卡）",
            description = "口径照用户面版本详情：版本元数据（hash / 摘要 / 锚定 run / 成版时刻）＋"
                    + "收尾卡载荷（Run-Id 联接对话史 closing 条目，#88 同载荷复用；收尾卡"
                    + "缺位时 closing 为 null）＋rollbackFrom（回滚版本锚定源版本、runId 空；"
                    + "run 版本反之）。ref = commit hash（hex 40 位）——非 hash 形态"
                    + "404 PRJ_028 且不触工作区（shell 注入防线）。归档项目照读。"
                    + "需要机机签名；项目不存在 404 PRJ_001；环境故障 500 WSP_002")
    @ErrorCodes({"PRJ_001", "PRJ_028", "WSP_002"})
    public ApiResponse<VersionDetailResponse> versionDetail(@PathVariable String id,
            @PathVariable String ref) {
        return ApiResponse.ok(versionAppService.detail(Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND), ref));
    }

    @GetMapping("/{id}/files")
    @Operation(summary = "文件树（后台面，交付文件只读浏览）",
            description = "口径照用户面文件树读口（同源委托同一应用服务——守卫由构造不漂移）："
                    + "交付文件视图 = 项目 dev 工作区剔除非交付物（data/、.platform/、"
                    + "node_modules/ 与 .env——与源码包同口径）后的文件清单 "
                    + "[{path, size}]，path 为工作区相对路径、按路径稳定排序，只列文件"
                    + "（目录由调用方按路径段合成）。直读工作区实时状态。文件区挂项目"
                    + "不挂订单——未下单项目可浏览（源码包只挂订单的排障缺口在此补上，"
                    + "代码级排障不依赖成交）。归档项目照读（工作区保留）。"
                    + "需要机机签名；项目不存在 404 PRJ_001；环境故障 500 WSP_002")
    @ErrorCodes({"PRJ_001", "WSP_002"})
    public ApiResponse<ProjectFilesResponse> files(@PathVariable String id) {
        return ApiResponse.ok(projectQueryAppService.files(Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND)));
    }

    @GetMapping("/{id}/files/content")
    @Operation(summary = "文本文件内容（后台面，点看）",
            description = "口径照用户面文件内容读口：path = 工作区相对路径（文件树条目原样回传），"
                    + "只收文本且限大小——非交付物/机密（根级 .env）/逃逸路径 400 PRJ_020"
                    + "（判定层拒绝，工作区不被触达）；文件不存在 404 PRJ_021；超过在线"
                    + "查看上限（1 MiB，容器侧拦截不读取）400 PRJ_022；非文本（正文含 "
                    + "NUL）400 PRJ_023。未下单项目照读（排障不依赖成交）。"
                    + "需要机机签名；项目不存在 404 PRJ_001；环境故障 500 WSP_002")
    @ErrorCodes({"PRJ_001", "PRJ_020", "PRJ_021", "PRJ_022", "PRJ_023", "WSP_002"})
    public ApiResponse<ProjectFileContentResponse> fileContent(@PathVariable String id,
            @RequestParam String path) {
        return ApiResponse.ok(projectQueryAppService.fileContent(Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND), path));
    }

    @GetMapping("/{id}/files/package")
    @Operation(summary = "项目文件包（tar.gz 二进制流，#174）",
            description = "取走项目工作区内容：已封存项目直取封存包（整卷口径——含数据库"
                    + "与机密，卷已删、包是唯一事实，文件名 {id}-archive.tar.gz）；"
                    + "未封存项目即时导出源码包（交付口径：排 node_modules/.env 等，"
                    + "与订单源码包同一导出实现——订单流程不动，文件名 {id}-source.tar.gz）。"
                    + "沙箱休眠中会先同步唤醒重建再打包（分钟内）；归档项目照取"
                    + "（工作区保留）。项目不存在 404 PRJ_001；封存态无包记录/包不可读"
                    + "404 WSP_016（1016）；环境故障 500 WSP_002。需要机机签名")
    @ErrorCodes({"PRJ_001", "WSP_016", "WSP_002"})
    public ResponseEntity<ByteArrayResource> filesPackage(@PathVariable String id) {
        Long projectId = Tsid.resolve(id, ProjectMessage.PROJECT_NOT_FOUND);
        ProjectFilesPackage pkg = projectQueryAppService.filesPackage(projectId);
        String filename = projectId + (pkg.fromSealArchive() ? "-archive.tar.gz" : "-source.tar.gz");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/gzip"));
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(pkg.content()));
    }
}
