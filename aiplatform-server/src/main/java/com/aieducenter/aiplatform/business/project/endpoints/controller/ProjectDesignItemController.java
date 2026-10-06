package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.DesignProcessAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.FinalizeDesignItemCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.DesignFinalizedResponse;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.support.Tsid;

import jakarta.validation.Valid;

/**
 * 设计物 REST 面（#291 定稿机制，ADR-0024/0025）：设计物的显式动作——定稿
 * （候选中锁定一稿：稿进版本流、触发后续分岔）。设计物是项目子资源，独立
 * 控制器分列（照 ProjectMaterialController 先例——ProjectController 已满）；
 * 改稿不经本面（作用域消息直达设计会话，见 POST /messages 的 designItem）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/design-items/{ord}/finalize")
@Validated
@Tag(name = "Design Items", description = "设计物：定稿显式动作（稿进版本流＋后续分岔触发）")
public class ProjectDesignItemController {

    private final DesignProcessAppService designProcessAppService;

    public ProjectDesignItemController(DesignProcessAppService designProcessAppService) {
        this.designProcessAppService = designProcessAppService;
    }

    @PostMapping
    @Operation(summary = "定稿设计物（#291 显式动作收口）",
            description = "候选中锁定一稿（path＝定稿稿的工作区锚定形路径，来自收尾卡"
                    + " drafts/画布稿卡）。定稿＝显式动作收口：件状态转已定稿、"
                    + "git commit 成版（Run-Id 对偶锚定定稿收尾卡——对话流入卡、"
                    + "「查看当时」即见定稿稿），并触发后续分岔：系统在途（已生成＋"
                    + "设计类终点）自动起更新 run 按稿对齐；系统＋设计全部定稿自动起"
                    + "首个构建（稿入起跑上下文）；设计主线全部定稿开放下单。"
                    + "响应 runId＝定稿锚、versionHash＝成版 commit（成版失败为 null，"
                    + "定稿事实不受影响）。守卫序：项目存在 → 未归档 → 未冻结"
                    + "（ORD_006）→ 设计/更新轨空闲（409 PRJ_046——在途 run 读写"
                    + "工作区，成版全量提交会卷入在途改动）→ 件存在（404 PRJ_047）"
                    + "→ 件已产出稿（409 PRJ_048）→ 稿在工作区（404 PRJ_049——候选"
                    + "可被悬卡删除，定稿对象以容器事实为准；路径不可浏览 400 PRJ_020）。"
                    + "幂等覆写：再定稿即刷新（改稿产新代后重新锁定）。项目不存在"
                    + " 404 PRJ_001；存在性检查环境故障 WSP_002")
    @ErrorCodes({"PRJ_001", "PRJ_013", "PRJ_020", "PRJ_046", "PRJ_047", "PRJ_048",
            "PRJ_049", "ORD_006", "WSP_002"})
    public ApiResponse<DesignFinalizedResponse> finalize(
            @PathVariable String projectId, @PathVariable int ord,
            @Valid @RequestBody FinalizeDesignItemCommand command) {
        DesignProcessAppService.DesignFinalization finalization =
                designProcessAppService.finalizeDesignItem(
                        Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), ord,
                        command.path());
        return ApiResponse.ok(new DesignFinalizedResponse(
                finalization.runId(), finalization.versionHash()));
    }
}
