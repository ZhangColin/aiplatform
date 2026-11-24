package com.semgravio.aiplatform.business.project.endpoints.controller;

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

import com.semgravio.aiplatform.business.project.application.BackofficeAgentConfigAppService;
import com.semgravio.aiplatform.business.project.application.dto.command.AgentConfigUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.command.EdgeModelUpdateCommand;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentConfigResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeAgentConfigTraceResponse;
import com.semgravio.aiplatform.business.project.application.dto.response.BackofficeEdgeModelConfigResponse;
import com.semgravio.aiplatform.business.project.domain.model.Operator;

/**
 * 后台智能体运营配置 REST 面（#251，机机签名——五头 HMAC 强制闸，见
 * {@link com.semgravio.aiplatform.config.WebMvcConfig}）：两座智能体（main/
 * executor）的 systemPrompt／模型配置后台可维护＋变更留痕可查（ADR-0021
 * 身份与配置分治——AgentProfile 枚举仍是身份与缺省正本，装配库值优先、缺省
 * 回落）；边缘调用点三件（naming/compaction/classification，#366）的
 * 模型配置同面维护（/edge/{edgeKey} 三端点——值面仅配置，提示词不属配置面；
 * #638 技能预检边缘点已退场）。
 * 错误码 PRJ_033（智能体不存在）/ PRJ_034（操作者缺）/ PRJ_064（边缘点不存在）。
 * 操作者透传头 {@code X-User-Id}/{@code X-User-Name} 写操作必留痕。
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
            description = "该智能体生效配置：systemPrompt／模型配置生效值（库覆盖值"
                    + "或枚举默认——装配实取值）＋覆盖标记（systemPromptOverridden/"
                    + "modelConfigKeyOverridden，运营要看到「现在跑的是覆盖还是默认」）＋"
                    + "枚举默认预览（defaultSystemPrompt/defaultModelConfigKey——清空覆盖"
                    + "即落此值，恢复默认前可先看落点）＋增强工具开关存储态"
                    + "（webSearchEnabled/fetchUrlEnabled，#252 起生效装配——工具面"
                    + "清单与窄幅开关另见 /agent-tools 两端点）＋最近写者。无覆盖行＝"
                    + "全回落（覆盖标记 false、写者 null）。"
                    + "寻址稳定键两把：main=主智能体 / executor=run 执行体；"
                    + "边缘调用点（naming 等）走 /edge/"
                    + "{edgeKey} 配置端点，两座端点对边缘键 404 PRJ_033。需要机机"
                    + "签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_033"})
    public ApiResponse<BackofficeAgentConfigResponse> config(@PathVariable String agentKey) {
        return ApiResponse.ok(appService.config(agentKey));
    }

    @PutMapping("/{agentKey}")
    @Operation(summary = "智能体配置全量写（覆盖态终态＋变更留痕）",
            description = "PUT 全量语义：请求体即该智能体覆盖态终态——systemPrompt/"
                    + "modelConfigKey 全文替换（null/缺省/纯空白＝清空覆盖，装配回落枚举"
                    + "默认——调整工作协议、换模型不发版）；webSearchEnabled/"
                    + "fetchUrlEnabled 工具开关缺省 true＝开（#252 起生效装配：关即"
                    + "退出槽位装配面、开即回归——工具面窄幅写口另见 PUT /agent-tools/"
                    + "{toolName}）。装配生效语义：动态查库，"
                    + "变更后下一轮命令构建自然"
                    + "取新值（智能体工厂缓存键含 sysPrompt、模型串与工具面规格——"
                    + "新值即新实例，进行中 run 不定格）。变更留痕 append-only：值面"
                    + "有动必落痕（变更前后全量值快照＋操作者，traces 读面可查）；"
                    + "同值重写＝幂等回执不落痕；回滚＝把留痕旧值快照写回（即一次"
                    + "新变更、留新痕，不做版本树）。配置键即时校验（#367）："
                    + "modelConfigKey 非空时必须在模型配置面"
                    + "（/model-configs）有行且启用——缺行 400 PRJ_074、停用 400 "
                    + "PRJ_075（错值写不进去，不再靠留痕回滚兜底；null＝清空回落"
                    + "缺省不校验）。X-User-Id/X-User-Name 透传头"
                    + "必留痕（缺头 400 PRJ_034）。寻址同 GET（未知键 404 PRJ_033）。"
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

    @GetMapping("/edge/{edgeKey}")
    @Operation(summary = "边缘调用点配置读面（生效值＋覆盖标记＋默认预览）",
            description = "边缘调用点（一次性轻调用）的模型配置生效值：库覆盖值或"
                    + "代码缺省配置键（#366 同表收编——值面仅模型配置，无 prompt/"
                    + "工具开关）。寻址稳定键三把：naming=项目取名 / "
                    + "compaction=上下文压缩 / classification=入口分类（#638 技能预检"
                    + "边缘点已退场）。改配置下一轮命令构建即生效（装配动态查库，"
                    + "进行中 run 不定格）。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_064"})
    public ApiResponse<BackofficeEdgeModelConfigResponse> edgeConfig(@PathVariable String edgeKey) {
        return ApiResponse.ok(appService.edgeConfig(edgeKey));
    }

    @PutMapping("/edge/{edgeKey}")
    @Operation(summary = "边缘调用点配置写（窄值面＋变更留痕）",
            description = "PUT 全量语义、值面仅模型配置：请求体 {modelConfigKey} 即该边缘点"
                    + "覆盖态终态——null/缺省/纯空白＝清空覆盖回落代码缺省 flash；"
                    + "换档不发版（装配库值优先，下一轮命令构建即生效）。留痕与"
                    + "三座全量写同机制：值面有动必落痕（前后快照＋操作者），同值"
                    + "幂等重写不落痕，回滚＝旧值快照写回（一次新变更、留新痕）。"
                    + "配置键即时校验与三座同口径（#367）：非空须在模型配置面"
                    + "有行且启用（缺行 400 PRJ_074、停用 400 PRJ_075；null＝清空"
                    + "回落缺省不校验）。"
                    + "X-User-Id/X-User-Name 透传头必留痕（缺头 400 PRJ_034）。"
                    + "需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_064", "PRJ_034"})
    public ApiResponse<BackofficeEdgeModelConfigResponse> updateEdgeModel(
            @PathVariable String edgeKey, @RequestBody EdgeModelUpdateCommand command) {
        return ApiResponse.ok(appService.updateEdgeModel(edgeKey, command, currentOperator()));
    }

    @GetMapping("/edge/{edgeKey}/traces")
    @Operation(summary = "边缘调用点配置留痕读面（历史可查，回滚依据）",
            description = "该边缘点全部配置变更留痕，按时间倒序（最近先）——快照列"
                    + "与三座同形（边缘行配置列之外的快照件恒缺省态：prompt null、"
                    + "开关 true）。回滚＝把某痕旧值快照的 modelConfigKey PUT 回写（即一次"
                    + "新变更、留新痕，不做版本树）。需要机机签名（五头 HMAC），"
                    + "无签名 401")
    @ErrorCodes({"PRJ_064"})
    public ApiResponse<List<BackofficeAgentConfigTraceResponse>> edgeTraces(
            @PathVariable String edgeKey) {
        return ApiResponse.ok(appService.edgeTraces(edgeKey));
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
