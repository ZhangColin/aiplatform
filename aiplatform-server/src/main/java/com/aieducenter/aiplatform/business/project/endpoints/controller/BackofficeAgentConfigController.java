package com.aieducenter.aiplatform.business.project.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.core.context.RequestContext;
import com.cartisan.openapi.annotation.RequireSignature;
import com.cartisan.web.doc.ErrorCodes;
import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.project.application.BackofficeAgentConfigAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.AgentConfigUpdateCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeAgentConfigResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeAgentConfigTraceResponse;
import com.aieducenter.aiplatform.business.project.domain.model.Operator;

/**
 * 后台智能体运营配置 REST 面（#251，机机签名——五头 HMAC 强制闸，见
 * {@link com.aieducenter.aiplatform.config.WebMvcConfig}）：两座智能体（main/
 * executor）的 systemPrompt／模型档位后台可维护＋变更留痕可查（ADR-0021 身份与
 * 配置分治——AgentProfile 枚举仍是身份与缺省正本，装配库值优先、缺省回落）。
 * classify/naming 等一次性判定提示词不进本面。错误码 PRJ_033（智能体不存在）/
 * PRJ_034（操作者缺）。操作者透传头 {@code X-User-Id}/{@code X-User-Name} 写
 * 操作必留痕。
 */
@RestController
@RequestMapping("/api/backoffice/agent-configs")
@RequireSignature
@Tag(name = "Backoffice Agent Config", description = "后台智能体运营配置：读 / 全量写 / 变更留痕（机机签名）")
public class BackofficeAgentConfigController {

    private final BackofficeAgentConfigAppService appService;

    public BackofficeAgentConfigController(BackofficeAgentConfigAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/{agentKey}")
    @Operation(summary = "智能体配置读面（生效值＋覆盖标记＋默认预览）",
            description = "该智能体生效配置：systemPrompt／模型档位生效值（库覆盖值"
                    + "或枚举默认——装配实取值）＋覆盖标记（systemPromptOverridden/"
                    + "modelIdOverridden，运营要看到「现在跑的是覆盖还是默认」）＋"
                    + "枚举默认预览（defaultSystemPrompt/defaultModelId——清空覆盖"
                    + "即落此值，恢复默认前可先看落点）＋增强工具开关存储态"
                    + "（webSearchEnabled/fetchUrlEnabled，装配生效属后续票——存储面"
                    + "先行）＋最近写者。无覆盖行＝全回落（覆盖标记 false、写者 null）。"
                    + "寻址稳定键两把：main=主智能体 / executor=run 执行体；classify/"
                    + "naming 等一次性判定不属配置面——未知键 404 PRJ_033。需要机机"
                    + "签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_033"})
    public ApiResponse<BackofficeAgentConfigResponse> config(@PathVariable String agentKey) {
        return ApiResponse.ok(appService.config(agentKey));
    }

    @PutMapping("/{agentKey}")
    @Operation(summary = "智能体配置全量写（覆盖态终态＋变更留痕）",
            description = "PUT 全量语义：请求体即该智能体覆盖态终态——systemPrompt/"
                    + "modelId 全文替换（null/缺省/纯空白＝清空覆盖，装配回落枚举"
                    + "默认——调整工作协议、换模型不发版）；webSearchEnabled/"
                    + "fetchUrlEnabled 工具开关缺省 true＝开（存储面先行，装配生效属"
                    + "后续票）。装配生效语义：动态查库，变更后下一轮命令构建自然"
                    + "取新值（智能体工厂缓存键含 sysPrompt 与模型串——新值即新实例，"
                    + "进行中 run 不定格）。变更留痕 append-only：值面有动必落痕"
                    + "（变更前后全量值快照＋操作者，traces 读面可查）；同值重写＝"
                    + "幂等回执不落痕；回滚＝把留痕旧值快照写回（即一次新变更、留"
                    + "新痕，不做版本树）。档位名不做白名单校验（错值致新会话失败"
                    + "经留痕写回可回滚）。X-User-Id/X-User-Name 透传头必留痕"
                    + "（缺头 400 PRJ_034）。寻址同 GET（未知键 404 PRJ_033）。"
                    + "回执与 GET 同形。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_033", "PRJ_034"})
    public ApiResponse<BackofficeAgentConfigResponse> update(@PathVariable String agentKey,
            @RequestBody AgentConfigUpdateCommand command) {
        return ApiResponse.ok(appService.update(agentKey, command, currentOperator()));
    }

    @GetMapping("/{agentKey}/traces")
    @Operation(summary = "配置变更留痕读面（历史可查，回滚依据）",
            description = "该智能体全部配置变更留痕，按时间倒序（最近先）。每痕＝"
                    + "一次实际变更（同值幂等重写不落痕）：变更前后全量值快照"
                    + "（old*/new* 四件对——旧值快照即回滚写回的依据）＋操作者＋"
                    + "时刻。append-only：留痕不随任何动作删除，历史事实不随配置"
                    + "行消失。回滚＝把某痕旧值快照 PUT 回写（即一次新变更、留新痕）。"
                    + "寻址同 GET（未知键 404 PRJ_033）。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"PRJ_033"})
    public ApiResponse<List<BackofficeAgentConfigTraceResponse>> traces(@PathVariable String agentKey) {
        return ApiResponse.ok(appService.traces(agentKey));
    }

    /**
     * 当前操作者（#251）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（admin 侧管理员标识，签名面明示信任、不校验真实
     * 性——技能库/知识治理同款）。配置写操作必留痕——缺头不为落空，域面守卫
     * PRJ_034 拦截。Id 两形转换在此一次完成（上下文 Long → 外域标识字符串）。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
