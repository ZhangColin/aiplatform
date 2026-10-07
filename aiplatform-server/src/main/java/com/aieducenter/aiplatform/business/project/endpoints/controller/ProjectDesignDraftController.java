package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
import com.aieducenter.aiplatform.business.project.application.ProjectRenderAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.ProjectFileDownloadResponse;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.DesignPackages;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 设计稿 REST 面（#293 全系统画布；#294 下载图位图化）：稿卡的画布动作——悬卡
 * 删除（真删工作区文件，候选可删、定稿不可删）＋下载图 PNG（界面类稿位图化——
 * 支付门内）。设计稿是项目子资源，独立控制器分列（照
 * {@link ProjectDesignItemController} 先例——ProjectController 已满）；稿的到达/
 * 清单呈现不走本面（收尾卡稿清单＋文件树，SSE 零扩展）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/design-drafts")
@Validated
@Tag(name = "Design Drafts", description = "设计稿：悬卡删除＋下载图 PNG 位图化（支付门）")
public class ProjectDesignDraftController {

    private final DesignProcessAppService designProcessAppService;
    private final ProjectRenderAppService renderAppService;

    public ProjectDesignDraftController(DesignProcessAppService designProcessAppService,
            ProjectRenderAppService renderAppService) {
        this.designProcessAppService = designProcessAppService;
        this.renderAppService = renderAppService;
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

    @GetMapping("/png")
    @Operation(summary = "下载设计稿图（#294 界面类 PNG 位图化，支付门）",
            description = "path＝稿的工作区锚定形路径（收尾卡 drafts/画布稿卡同形）。"
                    + "界面类 HTML 稿按画布帧同一画幅（1280×800）chromium 渲成 PNG"
                    + "——所见即所下（下载的图就是画布上看到的那帧）；产物落 exports/ "
                    + "（可再生衍生），重复下载幂等覆写。支付门只盖下载面"
                    + "（ADR-0027：平台随便看、带走才付费）：项目曾有已支付/已归档"
                    + "订单即开放，未付费 402 ORD_015 如实告知门语义。非 design 锚定"
                    + " HTML 400 PRJ_020（平面类图片稿直接走通用单文件下载）；源稿不"
                    + "存在 404 PRJ_021；渲染失败 500 PRJ_039。响应为二进制流"
                    + "（image/png，本端点不走 ApiResponse JSON 信封，先例＝单文件下载）。"
                    + "项目不存在 404 PRJ_001")
    @ErrorCodes({"PRJ_001", "PRJ_020", "PRJ_021", "PRJ_039", "ORD_015", "WSP_002"})
    public ResponseEntity<ByteArrayResource> downloadPng(@PathVariable String projectId,
            @RequestParam String path) {
        ProjectFileDownloadResponse png = renderAppService.downloadableDraftPng(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), path);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(png.contentType()));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileNameOf(path, png.contentType())).build());
        headers.set("X-Content-Type-Options", "nosniff");
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(png.content()));
    }

    /**
     * 下载落盘文件名：路径末段按内容类型换扩展（HTML 稿 → {@code {词干}-1280x800.png}
     * ，所见即所下的画幅事实进文件名）；消毒同 {@code ProjectController#fileNameOf}
     * 口径（控制字符与引号剔除、非 ASCII 原样）。
     */
    private static String fileNameOf(String path, String contentType) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = "image/png".equals(contentType)
                ? "-" + DesignPackages.FRAME_PNG_WIDTH + "x"
                        + DesignPackages.FRAME_PNG_HEIGHT + ".png"
                : dot > 0 ? name.substring(dot) : "";
        return (stem + extension).replaceAll("[\\p{Cntrl}\"]", "");
    }
}
