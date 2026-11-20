package com.semgravio.aiplatform.business.project.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.semgravio.aiplatform.business.project.application.BackofficeModelChannelAppService;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelTierCandidateCreateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.ModelTierCandidateUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelTierCandidateResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeModelTierCandidateTraceResponse;
import com.semgravio.aiplatform.business.project.domain.model.Operator;

/**
 * 后台候选队列维护 REST 面（#559/T5，spec #552-T5，ADR-0053 ③ 后台写面；
 * 机机签名——五头 HMAC 强制闸，同 model-tiers 面）：档位子资源——候选的增删/
 * 排序/启停＋编排留痕。候选身份＝通道×实际模型名（队列内唯一）；priority 升序
 * ＝队列序（主备＝前两名）；档位行现列恒＝队列头（镜像不变式）。变更下一轮
 * 命令构建即生效（resolve 动态查库，进行中 run 不定格）。错误码
 * PRJ_066/071/088~092。操作者透传头写操作必留痕。
 */
@RestController
@RequestMapping("/api/backoffice/model-tiers/{tierKey}/candidates")
@RequireSignature
@Tag(name = "Backoffice Model Tier Candidate", description = "后台候选队列维护：增删/排序/启停＋留痕（机机签名）")
public class BackofficeModelTierCandidateController {

    private final BackofficeModelChannelAppService appService;

    public BackofficeModelTierCandidateController(BackofficeModelChannelAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    @Operation(summary = "候选队列读面（priority 升序＝队列序）",
            description = "该档位全部候选行按队列序（含停用行——启停过滤是装配"
                    + "语义）：队列序/通道/实际模型名/行级启停＋最近写者。主备＝"
                    + "队列前两名。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_066"})
    public ApiResponse<List<BackofficeModelTierCandidateResponse>> candidates(
            @PathVariable String tierKey) {
        return ApiResponse.ok(appService.tierCandidates(tierKey));
    }

    @PostMapping
    @Operation(summary = "候选增（追加/插位；编排留痕）",
            description = "新候选入队：priority 缺省＝追加队尾、显式 1..队尾+1＝"
                    + "插位（既有候选顺移，越界 PRJ_090）；候选身份＝通道×实际"
                    + "模型名，队列内唯一（重复 409 PRJ_091——同故障物理单元无"
                    + "备胎价值）；通道须已有行（PRJ_071）。档位无候选行时档位行"
                    + "现列即隐式首选候选（先进队列再插新行，不被顶掉）。回执＝"
                    + "整队列（编排结果一次可见）。变更下一轮命令构建即生效"
                    + "（resolve 动态查库）。X-User-Id/X-User-Name 必留痕（缺头"
                    + "400 PRJ_034）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_066", "PRJ_088", "PRJ_071", "PRJ_090", "PRJ_091", "PRJ_034"})
    public ApiResponse<List<BackofficeModelTierCandidateResponse>> add(
            @PathVariable String tierKey, @RequestBody ModelTierCandidateCreateCommand command) {
        return ApiResponse.ok(appService.addTierCandidate(tierKey, command, currentOperator()));
    }

    @PutMapping("/{priority}")
    @Operation(summary = "候选改（换指向/启停/移动排序；寻址＝队列序；留痕）",
            description = "寻址＝队列序（无行 404 PRJ_089）：providerKey/modelName "
                    + "各自缺省＝保持现值（换指向即换候选身份——留痕＝旧身份删除痕"
                    + "＋新身份新增痕，撞既有身份 409 PRJ_091、通道不存在 400 "
                    + "PRJ_071）；enabled 缺省＝保持、显式 true/false 才翻转（行级"
                    + "启停——false＝读面剔除不参队）；priority＝移动目标队列序"
                    + "（缺省＝原地；区间候选顺移补位——排序即此；越界 PRJ_090）。"
                    + "动到队列头（priority 1）＝档位行现列镜像跟随。值面无动＝幂等"
                    + "回执不落痕；回滚＝留痕旧值快照写回（一次新编排留新痕）。"
                    + "X-User-Id/X-User-Name 必留痕（缺头 400 PRJ_034）。需要机机"
                    + "签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_066", "PRJ_089", "PRJ_071", "PRJ_090", "PRJ_091", "PRJ_034"})
    public ApiResponse<List<BackofficeModelTierCandidateResponse>> update(
            @PathVariable String tierKey, @PathVariable int priority,
            @RequestBody ModelTierCandidateUpdateCommand command) {
        return ApiResponse.ok(appService.updateTierCandidate(tierKey, priority, command,
                currentOperator()));
    }

    @DeleteMapping("/{priority}")
    @Operation(summary = "候选删（队列压实；编排留痕）",
            description = "删该队列序的候选（无行 404 PRJ_089）：后续候选顺移补位"
                    + "（priority 密集无空洞——补位候选各留同身份移动痕）。最后一个"
                    + "候选不可删（409 PRJ_092——档位供给定义非空，整队下线走档位"
                    + "停用不删空）。删成留痕 new 全 null；回滚＝按痕旧值原位重建"
                    + "（一次新编排留新痕）。X-User-Id/X-User-Name 必留痕（缺头"
                    + "400 PRJ_034）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_066", "PRJ_089", "PRJ_092", "PRJ_034"})
    public ApiResponse<List<BackofficeModelTierCandidateResponse>> delete(
            @PathVariable String tierKey, @PathVariable int priority) {
        return ApiResponse.ok(appService.deleteTierCandidate(tierKey, priority, currentOperator()));
    }

    @GetMapping("/traces")
    @Operation(summary = "候选编排留痕读面（历史可查，回滚依据）",
            description = "该档位候选全部编排留痕按时间倒序（最近先）：身份轴前后"
                    + "快照（新增痕 old 全 null、删除痕 new 全 null、移动/启停/换"
                    + "指向＝同身份 priority/enabled 前后可见）＋操作者＋时刻；"
                    + "append-only 跨行存活。回滚＝按痕旧值写回（重排/重建候选＝"
                    + "一次新编排留新痕）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeModelTierCandidateTraceResponse>> traces(
            @PathVariable String tierKey) {
        return ApiResponse.ok(appService.tierCandidateTraces(tierKey));
    }

    /** 当前操作者（#251 同款）：透传头落痕，缺头 PRJ_034 拦截。 */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
