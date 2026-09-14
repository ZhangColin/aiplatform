package com.aieducenter.aiplatform.business.project.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;
import com.cartisan.web.response.PageResponse;

import com.aieducenter.aiplatform.base.workspace.domain.enums.ContainerState;
import com.aieducenter.aiplatform.base.workspace.domain.enums.DesiredState;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.BackofficeWorkspaceAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceDetailResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeWorkspaceSummaryResponse;

/**
 * 后台沙箱观测面 REST（#173，/api/backoffice/workspaces 机机签名，只读）：运营
 * 第一次能看见每台沙箱睡没睡、封没封、占多少存储——期望态与 docker 实态两列
 * 如实分示（「期望运行/实态已亡」漂移行一眼可见），为深度存储回收的后续决策
 * 攒真实数据（v1 只观测不清算，ADR-0016）。沙箱事实归 base.workspace 观测用例
 * （跨 BC 走应用层），所属项目引用在本域拼装（软引用容缺）。cartisan-openapi
 * 五头 HMAC，类级 {@code @RequireSignature} 强制闸；该前缀经 WebMvcConfig 排除
 * 会话拦截。错误码前缀 WSP_（工作区不存在 WSP_001、过滤参数 WSP_014——观测面
 * 的资源是工作区，前缀随资源不随宿主包）。写操作（休眠/封存/唤醒触发）不进
 * 本面——v1 只读。
 */
@RestController
@RequestMapping("/api/backoffice/workspaces")
@RequireSignature
@Tag(name = "Backoffice Workspaces", description = "后台沙箱观测：清单 / 详情（期望态 vs 实态 + 卷大小，机机签名，只读）")
public class BackofficeWorkspaceController {

    private final BackofficeWorkspaceAppService appService;

    public BackofficeWorkspaceController(BackofficeWorkspaceAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "沙箱清单（期望态/实态过滤，分页）",
            description = "存储观测工作清单：新沙箱在前（TSID 倒序）。两过滤维度均可缺省"
                    + "（缺省＝全量）、可组合：① desired 期望态单选，Integer code"
                    + "（1=运行 2=休眠 3=封存，DB 意图侧）；② actual 容器实态单选，"
                    + "Integer code（1=运行中 2=已停止 3=无容器 4=未知——docker 现场探查，"
                    + "实态不落库，过滤在探查后内存完成，total 如实＝筛后计数；"
                    + "「期望运行而实态无容器」即漂移行，actual=3 即捞漂移清单）。"
                    + "每行＝项目引用（无所属项目为 null）＋期望态/实态两列＋last-touch"
                    + "＋卷大小（旁路容器 du 全卷、含可重建缓存，字节——封存容缺 null、"
                    + "探查失败亦 null）＋封存信息（时刻/包大小）。page 1 基（缺省 1）、"
                    + "size 缺省 20（上界 100）；排序服务端定死不开放。实态与卷大小逐行"
                    + "现场探查（docker 子进程），页越大越慢——观测页不必贪大。"
                    + "过滤参数绑定失败（非法 code/分页值）400 WSP_014。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"WSP_014"})
    public ApiResponse<PageResponse<BackofficeWorkspaceSummaryResponse>> workspaces(
            @RequestParam(required = false) DesiredState desired,
            @RequestParam(required = false) ContainerState actual,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(appService.workspaces(desired, actual, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "沙箱详情（全量字段＋所属项目引用）",
            description = "清单行超集：另带置备失败原因（FAILED 态排障）、封存包寻址键、"
                    + "审计时间列与中间件资源清单（连接串原文，容器内回环形态）。"
                    + "期望态/实态/卷大小同清单口径（现场探查、封存容缺）。"
                    + "所属项目引用软引用容缺（null 呈现）——工作区先于项目存在。"
                    + "需要机机签名（五头 HMAC）；工作区不存在（含畸形 id）404 WSP_001")
    @ErrorCodes({"WSP_001"})
    public ApiResponse<BackofficeWorkspaceDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(id));
    }

    /**
     * 清单参数绑定失败的兜底：非法期望态/实态 code、分页值在本层就是 400，映射回
     * WSP_014 保持错误码前缀口径（同 BackofficeOrderController ORD_010 形制）。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleFilterMismatch() {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(WorkspaceMessage.WORKSPACE_FILTER_INVALID));
    }
}
