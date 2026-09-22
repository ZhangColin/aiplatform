package com.aieducenter.aiplatform.base.skills.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.base.skills.application.BackofficeSkillAppService;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDetailResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillSummaryResponse;

/**
 * 后台技能库管理 REST 面（#246-T1/#247，机机签名——五头 HMAC 强制闸，见
 * {@link com.aieducenter.aiplatform.config.WebMvcConfig}）：技能管理域第一块——
 * 清单 / 详情（审核面）。内置（classpath 合成）与库中安装技能同权呈现；技能柄
 * 为 opaque 串两形制（内置 {@code builtin:<技能名>}／安装 TSID 十进制串）。错误码
 * 前缀 SKL_（技能不存在 SKL_001）。写口（安装/启停/卸载）属后续票。
 */
@RestController
@RequestMapping("/api/backoffice/skills")
@RequireSignature
@Tag(name = "Backoffice Skills", description = "后台技能库管理：清单 / 详情（机机签名）")
public class BackofficeSkillController {

    private final BackofficeSkillAppService appService;

    public BackofficeSkillController(BackofficeSkillAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "技能清单（内置＋安装同权，不分页）",
            description = "平台全部技能：内置（classpath 合成，来源=1）与库中安装"
                    + "（来源=2）同权呈现，空库时仍呈现内置技能。条目字段：名称、"
                    + "description、来源 code（1=内置 2=安装）与来源名、来源包"
                    + "（内置 null）、版本标识（装时 commit，内置 null）、状态"
                    + "（1=启用 2=停用，内置恒 1）。排序服务端定死：内置在前"
                    + "（名称序）、安装在后（来源包、名称序）。技能库是有界目录"
                    + "（装什么是运营决策），不分页不过滤。id 为 opaque 串两形制"
                    + "（builtin:<技能名>／TSID 十进制串），作详情寻址柄。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeSkillSummaryResponse>> skills() {
        return ApiResponse.ok(appService.skills());
    }

    @GetMapping("/{id}")
    @Operation(summary = "技能详情（元数据＋frontmatter＋正文，审核面）",
            description = "读全文判安装审核（注入面＋方法论重叠把关）：元数据（与"
                    + "清单行同形）＋ frontmatter 全量（解析态键值，含 name/"
                    + "description）＋正文全文（frontmatter 剥离后的 SKILL.md "
                    + "body）——所见即运行时注入面。id 取清单行原值（opaque 串"
                    + "两形制）。技能不存在（含未寻址内置名/TSID、畸形柄）"
                    + "404 SKL_001。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"SKL_001"})
    public ApiResponse<BackofficeSkillDetailResponse> detail(@PathVariable String id) {
        return ApiResponse.ok(appService.detail(id));
    }
}
