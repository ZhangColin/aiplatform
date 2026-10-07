package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.DesignProcessAppService;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 设计稿 REST 面（#293 全系统画布）：稿卡的画布整理动作——悬卡删除（真删工作区
 * 文件，候选可删、定稿不可删）。设计稿是项目子资源，独立控制器分列（照
 * {@link ProjectDesignItemController} 先例——ProjectController 已满）；稿的到达/
 * 清单呈现不走本面（收尾卡稿清单＋文件树，SSE 零扩展）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/design-drafts")
@Validated
@Tag(name = "Design Drafts", description = "设计稿：悬卡删除（画布整理——候选可删、定稿不可删）")
public class ProjectDesignDraftController {

    private final DesignProcessAppService designProcessAppService;

    public ProjectDesignDraftController(DesignProcessAppService designProcessAppService) {
        this.designProcessAppService = designProcessAppService;
    }

    @DeleteMapping
    @Operation(summary = "删除设计稿（#293 悬卡删除）",
            description = "path＝稿的工作区锚定形路径（收尾卡 drafts/画布稿卡同形）。"
                    + "把不要的候选稿从画布清掉＝真删工作区文件（删除即不可定稿——"
                    + "定稿对象以容器事实为准）；已定稿与版本不受影响（定稿稿不可删）。"
                    + "幂等：稿已不在也成功（删除的终态就是不在）。守卫序：项目存在 →"
                    + " 未归档 → 未冻结（ORD_006）→ design 锚定且可浏览（400 PRJ_020）"
                    + "→ 非定稿稿（409 PRJ_050）。项目不存在 404 PRJ_001；"
                    + "删除失败 WSP_002")
    @ErrorCodes({"PRJ_001", "PRJ_013", "PRJ_020", "PRJ_050", "ORD_006", "WSP_002"})
    public ApiResponse<Void> delete(@PathVariable String projectId, @RequestParam String path) {
        designProcessAppService.deleteDesignDraft(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), path);
        return ApiResponse.ok();
    }
}
