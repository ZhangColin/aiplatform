package com.aieducenter.aiplatform.business.project.endpoints.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.ProjectMaterialAppService;
import com.aieducenter.aiplatform.business.project.application.dto.response.MaterialUploadedResponse;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 物料 REST 面（#286，ADR-0027 图片管道底座）：用户经发送框回形针上传的图片
 * 物料——multipart 单文件落工作区物料目录（存储正本＝工作区，平台零文件库）。
 * 物料是项目子资源，独立控制器分列（照 ProjectVersionController 先例——
 * ProjectController 已满）。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/materials")
@Validated
@Tag(name = "Materials", description = "物料：发送框回形针上传图片物料（multipart 落工作区物料目录）")
public class ProjectMaterialController {

    private final ProjectMaterialAppService materialAppService;

    public ProjectMaterialController(ProjectMaterialAppService materialAppService) {
        this.materialAppService = materialAppService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传图片物料（发送框回形针，#286）",
            description = "multipart 单文件（part 名 file）落工作区物料目录 materials/："
                    + "只收 png/jpg/webp/gif/svg（按扩展名判定，格式不符 400 PRJ_044）、"
                    + "单文件 ≤10MB（超限 400 PRJ_043 如实报错不静默压缩——multipart"
                    + " 容器层超限同归此码）。落点命名 = TSID 前缀 + 净化后原始名"
                    + "（不撞名不覆盖），响应 path 即随话发送的附件载荷引用"
                    + "（attachmentType=image 的工作区路径引用、不带字节），name 为"
                    + "净化后原始名（chip 呈现用）。文件区即时可见（materials/ 在交付面），"
                    + "点看走 files/raw inline 大图（#283）。已归档 409 PRJ_013"
                    + "（归档是单向终点，不再收新物料）；落盘失败/回执畸形 WSP_002；"
                    + "项目不存在 404 PRJ_001")
    @ErrorCodes({"PRJ_001", "PRJ_013", "PRJ_043", "PRJ_044", "WSP_002"})
    public ApiResponse<MaterialUploadedResponse> upload(@PathVariable String projectId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(materialAppService.upload(
                Tsid.resolve(projectId, ProjectMessage.PROJECT_NOT_FOUND), file));
    }
}
