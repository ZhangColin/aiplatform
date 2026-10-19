package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * 调用事实观测「拆几片、什么顺序、谁依赖谁」，不解析自由文本），作为生成编排的
 * 交接物输入。不写工作区（计划是平台侧编排输入，执行体按片收任务 prompt，不自读
 * 文件）。
 *
 * <p>无需用户确认（权限自检恒放行）：切片计划是 PRD 产出的伴生动作，无最终版
 * 一说。仅随项目只读工作区注册（{@link ProfileToolkitSupplier}，#86 主智能体
 * 对话姿态）。readOnly（不动工作区，效果仅平台侧事实登记）。</p>
 *
 * <p><b>切片并行（#351）</b>：切片清单可携带依赖——{@code dependsOn} 与
 * {@code slices} 等长，每项是 1-based 片序列表（依赖前面哪几片；空 = 无依赖、
 * 平台可并行）。省略 {@code dependsOn} 整字段 = 全串行（每片依赖前一片），旧
 * 口径不变。</p>
 */
public class SaveBuildPlanTool extends ToolBase {

    public static final String NAME = "saveBuildPlan";
    private static final String SLICES_KEY = "slices";
    private static final String DEPENDS_ON_KEY = "dependsOn";

    private final String workspaceId;
    private final BuildPlanFacts buildPlanFacts;

    public SaveBuildPlanTool(String workspaceId, BuildPlanFacts buildPlanFacts) {
        super(ToolBase.builder()
                .name(NAME)
                .description("保存构建计划（切片计划）：产出 PRD 后调用——slices 传有序纵向"
                        + "切片清单，每片一句用户语言「用户能 X」（例如「用户能注册登录」「用户能"
                        + "下单支付」）；每片是从用户操作到后端落库端到端走通的一个完整点，按"
                        + "实现的自然顺序排列，宁粗勿碎。dependsOn 可选：与 slices 等长，"
                        + "dependsOn[i] 是第 i 片依赖的片序列表（1-based，1 = 第一片）——"
                        + "某片必须等另一片先做完（共享同一页面/数据结构/登录态等）才写依赖，"
                        + "无依赖的独立切片给空数组 []；省略整个 dependsOn 字段则按全串行"
                        + "（每片依赖前一片）处理。平台据此把生成拆成「先起服 + 首片 + 并行"
                        + "波」的轨道执行，只顺序/并行执行、不解析自由文本。仅首次产出 PRD 时"
                        + "调用，修订 PRD 无需重新产出。")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                SLICES_KEY, Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "有序纵向切片清单（每片一句用户语言「用户能 X」）"),
                                DEPENDS_ON_KEY, Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "array",
                                                "items", Map.of("type", "integer")),
                                        "description", "可选，与 slices 等长：每片依赖的前片序号"
                                                + "（1-based）；空数组 = 无依赖")),
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
        List<String> slices = parseSlices(input.get(SLICES_KEY));
        if (slices == null) {
            return Mono.just(ToolResultBlock.error(
                    "slices 不能为空：传有序纵向切片清单（每片一句「用户能 X」）"));
        }
        if (input.containsKey(DEPENDS_ON_KEY)) {
            List<Set<Integer>> dependsOn = parseDependsOn(input.get(DEPENDS_ON_KEY), slices.size());
            if (dependsOn == null) {
                return Mono.just(ToolResultBlock.error(
                        "dependsOn 须与 slices 等长：每项是依赖的前片序号列表（1-based），"
                                + "无依赖给空数组"));
            }
            buildPlanFacts.record(workspaceId, new BuildPlan(slices, dependsOn));
        } else {
            // 省略 dependsOn = 全串行（每片依赖前一片），旧口径不变
            buildPlanFacts.record(workspaceId, new BuildPlan(slices));
        }
        return Mono.just(ToolResultBlock.text("构建计划已记录：" + slices.size() + " 个切片。"));
    }

    /** slices 解析：非空字符串列表；缺失/空清单/非字符串返回 null（模型可见错误）。 */
    private static List<String> parseSlices(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> slices = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                return null;
            }
            slices.add(text.trim());
        }
        return slices;
    }

    /**
     * dependsOn 解析：在场须与 slices 等长、每项是整数列表（1-based 片序，去重）。
     * 形态非法（非列表/长度不符/非整数项）返回 null（模型可见错误——依赖声明不合法
     * 宁可重产，不静默降级串行）。缺省由调用方走全串行构造（不经过本方法）。
     */
    private static List<Set<Integer>> parseDependsOn(Object raw, int sliceCount) {
        if (!(raw instanceof List<?> list) || list.size() != sliceCount) {
            return null;
        }
        List<Set<Integer>> dependsOn = new ArrayList<>(sliceCount);
        for (Object entry : list) {
            if (!(entry instanceof List<?> deps)) {
                return null;
            }
            Set<Integer> ords = new LinkedHashSet<>();
            for (Object dep : deps) {
                if (!(dep instanceof Number number)) {
                    return null;
                }
                ords.add(number.intValue());
            }
            dependsOn.add(Set.copyOf(ords));
        }
        return dependsOn;
    }
}
