package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.BaInterviewAppService;
import com.aieducenter.aiplatform.business.project.application.GenerationAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectLifecycleAppService;
import com.aieducenter.aiplatform.business.project.application.ProjectQueryAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.AnswerQuestionCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.CreateProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.PostMessageCommand;
import com.aieducenter.aiplatform.business.project.application.dto.command.RenameProjectCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.GenerationStartResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.InterviewTurnResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.PrdResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectCreatedResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFileContentResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFilesResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectPreviewResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectUsageResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatusFilter;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;

/**
 * 项目 REST 面：一句话建项目（建即自动跑 BA）→ 指令区发言 / 问答卡作答 /
 * 开始做系统（生成）→ 归档 / 改名 / 详情 / 列表 / 用量 / PRD 读 / 文件树只读
 * 浏览 → 源码包下载 → 预览 → 删除真删级联。
 */
@RestController
@RequestMapping("/api/projects")
@Validated
@Tag(name = "Projects", description = "项目：建项目 / 指令区发言 / 问答作答 / 生成 / 列表 / 详情 / 归档 / 改名 / 用量 / PRD / 文件树 / 源码包 / 预览 / 删除")
public class ProjectController {

    private final ProjectLifecycleAppService appService;
    private final ProjectQueryAppService queryAppService;
    private final BaInterviewAppService baInterviewAppService;
    private final GenerationAppService generationAppService;

    public ProjectController(ProjectLifecycleAppService appService,
                             ProjectQueryAppService queryAppService,
                             BaInterviewAppService baInterviewAppService,
                             GenerationAppService generationAppService) {
        this.appService = appService;
        this.queryAppService = queryAppService;
        this.baInterviewAppService = baInterviewAppService;
        this.generationAppService = generationAppService;
    }

    @PostMapping
    @Operation(summary = "建项目（一句话创建：建即自动跑 BA 需求梳理）",
            description = "创建精简：只传 requirement（可空 = 缺省开场提示）。项目名由 LLM 异步生成——"
                    + "响应即返（名称 = 占位「未命名项目」），取名后台完成后详情/列表自然见新名（禁截取派生，"
                    + "失败保占位经改名端点可改）；类型单模板服务端缺省。"
                    + "单容器沙箱就绪（应用与 pg/redis 同容器，数据落工作区卷）。"
                    + "响应携带自动 BA 运行 runId（挂 /api/agent-events?runId= 的锚）。"
                    + "SSE：workspace-created → agent 流事件")
    public ApiResponse<ProjectCreatedResponse> create(@Valid @RequestBody CreateProjectCommand command) {
        return ApiResponse.ok(appService.create(command));
    }

    @GetMapping
    @Operation(summary = "项目列表（状态过滤）",
            description = "创建时间倒序。status 过滤（Integer code）：1=ACTIVE（进行中）/"
                    + "3=ARCHIVED（已归档）；缺省 all。不合法取值 400 PRJ_014")
    public ApiResponse<List<ProjectResponse>> list(
            @RequestParam(required = false) ProjectStatusFilter status) {
        return ApiResponse.ok(queryAppService.list(status));
    }

    /**
     * status 绑定失败的兜底：非法 code/非数值在本层就是 400，映射回 PRJ_014
     * 保持既有错误口径（本 controller 唯一可绑定枚举参数是 status，兜底不越界）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatusMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ProjectMessage.PROJECT_FILTER_UNKNOWN));
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "指令区发言（BA 访谈后续轮）",
            description = "content 即用户在指令区输入的这句话——BA 续同一 ba-{projectId} 会话消化"
                    + "（催促收敛、PRD 修订意见同从此进；首次生成后对系统的意见也从这里进——"
                    + "BA 判定后经 startFixRun 派修正 run，判定内化无需标注类型）。"
                    + "异步提交即返回，runId = 本轮 BA 运行"
                    + "标识（挂 /api/agent-events?runId= 的锚），回复与下一问经 SSE 到达。"
                    + "空白 400；已归档 409 PRJ_013（指令区关闭）；订单处理中 409 ORD_006"
                    + "（下单即冻结迭代，取消订单即解冻）；项目不存在 404 PRJ_001")
    public ApiResponse<InterviewTurnResponse> postMessage(@PathVariable String id,
            @Valid @RequestBody PostMessageCommand command) {
        return ApiResponse.ok(new InterviewTurnResponse(
                baInterviewAppService.runInterviewTurn(parseId(id), command.content()).runId()));
    }

    @PostMapping("/{id}/questions/{qid}/answer")
    @Operation(summary = "问答卡作答（ask_user 挂起续跑）",
            description = "qid = 挂起帧 engineRef（续跑批复的锚）。请求体回传挂起轮 runId 与"
                    + "待确认工具清单（wait-raised 帧 data.toolCalls 原样）+ 用户答复文本"
                    + "（单选 label / 多选拼接 / 自由输入，可与已勾选合并）。续跑续在同一 run "
                    + "上收口，过程帧经 SSE；恢复私货（会话/角色卡/工作区）从项目侧事实重建。"
                    + "空白答复 400；已归档 409 PRJ_013；订单处理中 409 ORD_006；"
                    + "项目不存在 404 PRJ_001")
    public ApiResponse<Void> answerQuestion(@PathVariable String id, @PathVariable String qid,
            @Valid @RequestBody AnswerQuestionCommand command) {
        baInterviewAppService.answerQuestion(parseId(id), command.runId(), qid,
                command.toolCalls().stream().map(AnswerQuestionCommand.ToolCall::toMap).toList(),
                command.answer());
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/generate")
    @Operation(summary = "开始做系统（触发首次生成）",
            description = "纯动作无门——PRD 已产出即可发起（待定项未清也可）。平台先把工作区"
                    + "布局资产就位（AGENTS.md 平台约定幂等覆写），随后下发编码智能体"
                    + "（coder-{projectId} 会话，AgentScope 单栈，读 docs/PRD.md 在沙箱实现系统"
                    + "并起 8081 端口服务）。异步提交即返回，runId = 首试运行标识"
                    + "（挂 /api/agent-events?runId= 的锚），过程帧经 SSE"
                    + "（role-assigned role=CODER）。失败自动重试有限次"
                    + "（app.generation.max-attempts，默认 3 次含首试）：重试帧 task-retrying"
                    + "（话术「遇到问题，正在重试」），超限转终态失败、由用户重新发起兜底。"
                    + "run 成功收口落 generated_at（首次生成时点，单向置位）。"
                    + "已归档 409 PRJ_013；已生成或生成在途 409 PRJ_017；"
                    + "PRD 从未产出 409 PRJ_018（前端入口本就以 PRD 产出为呈现条件，"
                    + "本守卫拦直连调用）；项目不存在 404 PRJ_001")
    public ApiResponse<GenerationStartResponse> generate(@PathVariable String id) {
        return ApiResponse.ok(new GenerationStartResponse(
                generationAppService.startGeneration(parseId(id)).runId()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情",
            description = "status = 派生项目状态（Integer code：1=进行中 3=已归档，归档优先）")
    public ApiResponse<ProjectDetailResponse> get(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.detail(parseId(id)));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "归档（单向终点）",
            description = "落 archived_at：归档是真实动作，重复归档 409 PRJ_013。归档不清工作区")
    public ApiResponse<ProjectDetailResponse> archive(@PathVariable String id) {
        return ApiResponse.ok(appService.archive(parseId(id)));
    }

    @PostMapping("/{id}/rename")
    @Operation(summary = "改名（需求端右栏「项目信息」inline 改名）",
            description = "名称后改的显式动作（占位名/生成名/已具名均可改，含已归档项目）。"
                    + "响应与详情端点同构——前端改名成功后 invalidate projects 域刷新列表/顶栏。"
                    + "空白拒绝 400 PRJ_005（与建项目同口径），长度上限 100 超限 400；"
                    + "单账号场景不设越权面、不发射 SSE（REST 响应即触达）")
    public ApiResponse<ProjectDetailResponse> rename(@PathVariable String id,
                                                     @Valid @RequestBody RenameProjectCommand command) {
        return ApiResponse.ok(appService.rename(parseId(id), command.name()));
    }

    @GetMapping("/{id}/usage")
    @Operation(summary = "项目用量（总量 + 平台成本 + 分模型 + 分智能体）",
            description = "经计量查询端口按 subject=projectId 聚合。"
                    + "cost 为平台成本口径（token × 事件时点生效单价的机械乘法，币种分桶不折算，"
                    + "无加价/售价）；unpriced 标注有用量但未配单价的档位（其分量不含于 cost，不伪装 0）；"
                    + "byAgentKind 按 dims.agentKind 聚合")
    public ApiResponse<ProjectUsageResponse> usage(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.usage(parseId(id)));
    }

    @GetMapping("/{id}/prd")
    @Operation(summary = "PRD 读（当前版 markdown，直读工作区）",
            description = "PRD = 项目 dev 工作区的 docs/PRD.md（事实源，BA 的 savePrd 写出，"
                    + "v1 无版本链只最新版）——本端点直读工作区文件返回 {projectId, content, updatedAt}，"
                    + "updatedAt = 文件 mtime（ISO-8601，秒精度）。未产出（工作区无该文件）"
                    + "404 PRJ_015，与项目不存在的 PRJ_001 区分，前端据此呈现「还没产出」。"
                    + "写出/更新时通知通道（/api/events?projectId=）发 document-updated"
                    + "（payload {projectId, documentType:\"PRD\"}），消费姿势 = invalidate 文档域后重拉本端点")
    public ApiResponse<PrdResponse> prd(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.prd(parseId(id)));
    }

    @GetMapping("/{id}/files")
    @Operation(summary = "项目文件树（交付文件只读浏览）",
            description = "交付文件视图 = 项目 dev 工作区剔除非交付物（data/、.platform/、"
                    + "node_modules/ 与 .env——与源码包同口径）后的文件清单：[{path, size}]，"
                    + "path 为工作区相对路径、按路径稳定排序，只列文件（目录由前端按路径段合成）。"
                    + "直读工作区实时状态——生成/修正 run 完成后即反映最新文件长出。"
                    + "项目不存在 404 PRJ_001")
    public ApiResponse<ProjectFilesResponse> files(@PathVariable String id) {
        return ApiResponse.ok(queryAppService.files(parseId(id)));
    }

    @GetMapping("/{id}/files/content")
    @Operation(summary = "文本文件内容（文件模式点看）",
            description = "path = 工作区相对路径（文件树条目原样回传）。只收文本且限大小："
                    + "非交付物/机密/逃逸路径 400 PRJ_020（判定层拒绝，工作区不被触达）；"
                    + "文件不存在 404 PRJ_021；超过在线查看上限（1 MiB，容器侧拦截不读取）"
                    + "400 PRJ_022；非文本（正文含 NUL）400 PRJ_023。项目不存在 404 PRJ_001")
    public ApiResponse<ProjectFileContentResponse> fileContent(@PathVariable String id,
            @RequestParam String path) {
        return ApiResponse.ok(queryAppService.fileContent(parseId(id), path));
    }

    @GetMapping("/{id}/source-package")
    @Operation(summary = "源码包下载（常开）",
            description = "交付物 = 源码包 + 仓内文档：打包项目 dev 工作区为 tar.gz"
                    + "（排除 .env 机密与 node_modules）。响应为二进制文件流"
                    + "（application/gzip，本端点不走 ApiResponse JSON 信封）")
    public ResponseEntity<ByteArrayResource> sourcePackage(@PathVariable String id) {
        Long projectId = parseId(id);
        byte[] bytes = appService.sourcePackage(projectId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/gzip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(projectId + "-source.tar.gz").build());
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(bytes));
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "预览（工作区端口暴露）",
            description = "把工作区容器端口发布到主机返回可访问 URL（localhost）；端口真实暴露后"
                    + "SSE preview-ready。产物可访问即预期效果（未起服务时连接拒绝属真实状态）")
    public ApiResponse<ProjectPreviewResponse> preview(@PathVariable String id) {
        return ApiResponse.ok(appService.preview(parseId(id)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目（真删级联）",
            description = "容器/卷级联清理 + wsp_*/prj_* 库记录删除；SSE workspace-destroyed")
    public ApiResponse<Void> delete(@PathVariable String id) {
        appService.delete(parseId(id));
        return ApiResponse.ok();
    }

    /** 寻址解析收口（{@link ProjectIds}）。 */
    private Long parseId(String id) {
        return ProjectIds.parse(id);
    }
}
