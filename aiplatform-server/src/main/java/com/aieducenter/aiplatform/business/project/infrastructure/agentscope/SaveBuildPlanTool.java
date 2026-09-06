package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.aieducenter.aiplatform.business.project.application.BuildPlan;
import com.aieducenter.aiplatform.business.project.application.BuildPlanFacts;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 保存构建计划工具（主智能体资产，ADR 0009）：主智能体产出 PRD 后顺带产出的有序
 * 纵向切片计划——效果 = 把切片清单落事实于 {@link BuildPlanFacts}（平台从工具
 * 调用事实观测「拆几片、什么顺序」，不解析自由文本），作为生成编排的交接物输入。
 * 不写工作区（计划是平台侧编排输入，执行体按片收任务 prompt，不自读文件）。
 *
 * <p>无需用户确认（权限自检恒放行）：切片计划是 PRD 产出的伴生动作，无最终版
 * 一说。仅随项目只读工作区注册（{@link ProfileToolkitSupplier}，#86 主智能体
 * 对话姿态）。readOnly（不动工作区，效果仅平台侧事实登记）。</p>
 */
public class SaveBuildPlanTool extends ToolBase {

    public static final String NAME = "saveBuildPlan";
    private static final String SLICES_KEY = "slices";

    private final String workspaceId;
    private final BuildPlanFacts buildPlanFacts;

    public SaveBuildPlanTool(String workspaceId, BuildPlanFacts buildPlanFacts) {
        super(ToolBase.builder()
                .name(NAME)
                .description("保存构建计划（切片计划）：产出 PRD 后调用——slices 传有序纵向"
                        + "切片清单，每片一句用户语言「用户能 X」（例如「用户能注册登录」「用户能"
                        + "下单支付」）；每片是从用户操作到后端落库端到端走通的一个完整点，按"
                        + "实现的自然顺序排列，宁粗勿碎。平台据此把生成拆成「先起服 + 逐片」的"
                        + "轨道逐段执行，只顺序执行、不解析自由文本。仅首次产出 PRD 时调用，"
                        + "修订 PRD 无需重新产出。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                SLICES_KEY, Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "有序纵向切片清单（每片一句用户语言「用户能 X」）")),
                        "required", List.of(SLICES_KEY)))
                .readOnly(true)
                .concurrencySafe(true));
        this.workspaceId = workspaceId;
        this.buildPlanFacts = buildPlanFacts;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
            io.agentscope.core.permission.PermissionContextState context) {
        // 切片计划是 PRD 产出的伴生动作（访谈协议的预期终点），工具点不放确认
        return Mono.just(PermissionDecision.allow("切片计划是 PRD 产出的伴生动作，无需确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput() != null ? param.getInput() : Map.of();
        Object raw = input.get(SLICES_KEY);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return Mono.just(ToolResultBlock.error(
                    "slices 不能为空：传有序纵向切片清单（每片一句「用户能 X」）"));
        }
        List<String> slices = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                return Mono.just(ToolResultBlock.error(
                        "slices 每片须为非空字符串（一句用户语言「用户能 X」）"));
            }
            slices.add(text.trim());
        }
        buildPlanFacts.record(workspaceId, new BuildPlan(slices));
        return Mono.just(ToolResultBlock.text("构建计划已记录：" + slices.size() + " 个切片。"));
    }
}
