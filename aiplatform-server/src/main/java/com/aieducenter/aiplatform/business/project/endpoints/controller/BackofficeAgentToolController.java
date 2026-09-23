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
import com.aieducenter.aiplatform.business.project.application.BackofficeAgentToolAppService;
import com.aieducenter.aiplatform.business.project.application.dto.command.AgentToolToggleCommand;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeAgentToolResponse;
import com.aieducenter.aiplatform.business.project.application.dto.response.BackofficeAgentToolSlotResponse;
import com.aieducenter.aiplatform.business.project.domain.model.Operator;

/**
 * 后台工具面 REST 面（#252，机机签名——五头 HMAC 强制闸，见
 * {@link com.aieducenter.aiplatform.config.WebMvcConfig}）：智能体工具面的可观测
 * （三槽位清单）＋增强工具窄幅开关（ADR-0021——骨架结构性锁死，编排权不下放
 * 配置）。错误码 PRJ_035（工具不存在）/PRJ_036（骨架/harness 内建不可开关）/
 * PRJ_037（开关目标态缺）；操作者透传头写开关必留痕（PRJ_034）。
 */
@RestController
@RequestMapping("/api/backoffice/agent-tools")
@RequireSignature
@Tag(name = "Backoffice Agent Tools", description = "后台智能体工具面：清单可观测＋增强工具窄幅开关（机机签名）")
public class BackofficeAgentToolController {

    private final BackofficeAgentToolAppService toolAppService;
    private final BackofficeAgentConfigAppService configAppService;

    public BackofficeAgentToolController(BackofficeAgentToolAppService toolAppService,
            BackofficeAgentConfigAppService configAppService) {
        this.toolAppService = toolAppService;
        this.configAppService = configAppService;
    }

    @GetMapping
    @Operation(summary = "工具面清单（按职能槽位列当前挂载工具）",
            description = "排障/审计面：按职能槽位（main=主智能体 / executor=run 执行体 / "
                    + "subagent=子智能体，与技能槽位同键）列当前挂载工具。三呈现源："
                    + "平台资产（骨架=编排链路＋项目事实只读件；增强=联网搜索/网页抓取，"
                    + "enabled 按运营配置生效态——false＝在册但退出装配面）、harness 内建"
                    + "编码工具（executor 槽，框架注册自省的呈现口径，不可开关；main 只读"
                    + "面结构性无）、子智能体声明工具面（self-test 哨兵 allowlist）。骨架与"
                    + "harness 内建结构性锁死（ADR-0021 编排权不下放配置），开关面只收"
                    + "增强两件。需要机机签名（五头 HMAC），无签名 401")
    @ErrorCodes({"UNAUTHORIZED"})
    public ApiResponse<List<BackofficeAgentToolSlotResponse>> inventory() {
        return ApiResponse.ok(toolAppService.inventory());
    }

    @PutMapping("/{toolName}")
    @Operation(summary = "增强工具开关（窄幅：关即退出装配面、开即回归）",
            description = "按工具注册名窄幅开关，仅增强工具（web_search=联网搜索 / "
                    + "fetch_url=网页抓取）接受：enabled=false 该工具退出对应槽位装配面"
                    + "（模型不可见），true 回归——动态生效：下一轮命令构建即新装配"
                    + "（工厂缓存键含工具面规格，进行中 run 不定格）。变更走配置留痕"
                    + "（与智能体配置同机制：值面有动才落痕、幂等回执零写入、回滚＝"
                    + "旧值写回）。骨架工具（问答/PRD 落库/计划/收口信号等编排链路件）"
                    + "与 harness 内建编码工具结构性锁死——接口层明确拒绝（403 PRJ_036，"
                    + "编排权不下放配置）；未知工具名 404 PRJ_035（清单读面即全集）。"
                    + "enabled 必填（无缺省翻转语义，缺即 400 PRJ_037）。X-User-Id/"
                    + "X-User-Name 透传头必留痕（缺头 400 PRJ_034）。需要机机签名"
                    + "（五头 HMAC），无签名 401")
    @ErrorCodes({"PRJ_035", "PRJ_036", "PRJ_037", "PRJ_034"})
    public ApiResponse<BackofficeAgentToolResponse> toggle(@PathVariable String toolName,
            @RequestBody(required = false) AgentToolToggleCommand command) {
        BackofficeAgentConfigAppService.ToolToggle toggled = configAppService.toggleTool(
                toolName, command == null ? null : command.enabled(), currentOperator());
        return ApiResponse.ok(BackofficeAgentToolResponse.of(toggled.tool().toolName(),
                toggled.tool().kind(), toggled.enabled(), toggled.tool().description()));
    }

    /**
     * 当前操作者（#252）：{@code X-User-Id}/{@code X-User-Name} 透传头经
     * RequestContext 读出落痕（与智能体配置写口同款——签名面明示信任）。开关写
     * 操作必留痕，缺头域面守卫 PRJ_034 拦截。
     */
    private static Operator currentOperator() {
        Long userId = RequestContext.getUserId();
        return new Operator(userId == null ? null : Long.toString(userId),
                RequestContext.getUserName());
    }
}
