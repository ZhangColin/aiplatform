package com.semgravio.aiplatform.business.project.endpoints.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.request.Pagination;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.project.application.BackofficeFlywheelAppService;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeFlywheelStatsResponse;

/**
 * 后台飞轮三个数只读统计端点（#643，阶段六-6；父票 #637，ADR-0058）：机机签名
 * （五头 HMAC）强制闸——按项目的修改轮数、没做对轮数、命中三件，另有有命中／
 * 无命中两组汇总。只读，不写任何表；不做成本、不排名、不设告警；不新增 SSE、
 * 不改用户面与生成内核。
 */
@RestController
@RequestMapping("/api/backoffice/flywheel-stats")
@RequireSignature
@Tag(name = "Backoffice Flywheel", description = "后台飞轮三个数统计：按项目行＋有命中/无命中分组汇总（机机签名，只读）")
public class BackofficeFlywheelController {

    private final BackofficeFlywheelAppService appService;

    public BackofficeFlywheelController(BackofficeFlywheelAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "飞轮三个数统计（按项目分页＋分组汇总）",
            description = "只看趋势、不打分不设目标（#643）。每个项目一行：projectId、"
                    + "projectName（项目名快照，取最新提炼记录，无记录回落当前项目名）、"
                    + "modificationRounds（修改轮数＝第一次生成之后的用户面版本 v2、v3……"
                    + "条数，取提炼处理记录里版本号大于 1 的条数）、missedRounds（没做对"
                    + "轮数＝其中判定「需求写明了但没做对」的轮数，同一轮多条只算一轮）、"
                    + "hitCount（命中次数＝含命中条目的留痕条数）、latestHitAt（最近命中"
                    + "时间，从未命中为 null）、hasHit（是否有命中；检索成功但 0 条与降级"
                    + "都算无命中）。summary 给有命中／无命中两组项目数与两组的平均修改"
                    + "轮数、平均没做对轮数（全量项目口径，不随分页变化；组内零项目平均值"
                    + "为 0）。没有版本或没有提炼记录的项目返回 0，不报错；空数据返回零值。"
                    + "行序服务端定死＝项目 id 倒序；page 1 基（缺省 1）、size 缺省 20"
                    + "（上界 100）。只读，不写任何表。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"BAD_REQUEST"})
    public ApiResponse<BackofficeFlywheelStatsResponse> stats(Pagination pagination) {
        return ApiResponse.ok(appService.stats(pagination));
    }
}
