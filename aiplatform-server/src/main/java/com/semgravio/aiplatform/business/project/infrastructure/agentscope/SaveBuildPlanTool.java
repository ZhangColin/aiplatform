package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.semgravio.aiplatform.business.project.application.BuildPlan;
import com.semgravio.aiplatform.business.project.application.BuildPlanFacts;
import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;

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
 *
 * <p><b>结构化输出扩展（#423）</b>：同批必产两件——①{@code coreJourneys} 核心
 * 旅程清单（几条「角色＋入口＋关键动作串」，整轨终验〔#406〕的载荷契约）；
 * ②{@code sliceSizes} 每片规模标签（小/中/大封闭集，模型路由〔#414〕信号）。
 * 两件随计划同生命周期落库随 PRD 版本演进；结构校验在位——旅程条目结构
 * （role/entry/keyActions 三要素齐）与标签封闭集，非法输出拒绝（模型可见错误、
 * 不落计划事实）。计划补产兜底轮同要两件（补产 prompt 同口径要求）。</p>
 */
public class SaveBuildPlanTool extends ToolBase {

    public static final String NAME = "saveBuildPlan";
    private static final String SLICES_KEY = "slices";
    private static final String DEPENDS_ON_KEY = "dependsOn";
    private static final String CORE_JOURNEYS_KEY = "coreJourneys";
    private static final String SLICE_SIZES_KEY = "sliceSizes";
    private static final String ROLE_KEY = "role";
    private static final String ENTRY_KEY = "entry";
    private static final String KEY_ACTIONS_KEY = "keyActions";

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
                        + "（每片依赖前一片）处理。coreJourneys 必传：核心旅程清单——几条"
                        + "真实用户使用系统的端到端旅程，每条三要素：role（角色：谁在用）、"
                        + "entry（入口：从哪个页面进来）、keyActions（关键动作串：一路按顺序"
                        + "做什么，用「→」连接）；挑用户验收时最该走得通的几条（通常 2-5 条），"
                        + "覆盖不同角色与主流程。sliceSizes 必传：与 slices 等长的每片规模"
                        + "标签，取值只能是「小」「中」「大」之一——按该片的实现面积估（页面数、"
                        + "接口数、逻辑复杂度）：小＝单点小改、中＝常规功能片、大＝多页面多"
                        + "接口的重功能片。平台据此把生成拆成「先起服 + 首片 + 并行波」的"
                        + "轨道执行，只顺序/并行执行、不解析自由文本。仅首次产出 PRD 时"
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
                                                + "（1-based）；空数组 = 无依赖"),
                                CORE_JOURNEYS_KEY, Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        ROLE_KEY, Map.of("type", "string",
                                                                "description", "角色：谁在用（如「买家」）"),
                                                        ENTRY_KEY, Map.of("type", "string",
                                                                "description", "入口：从哪个页面进来（如「首页」）"),
                                                        KEY_ACTIONS_KEY, Map.of("type", "string",
                                                                "description", "关键动作串：一路按顺序做什么，"
                                                                        + "用「→」连接（如「注册登录→浏览商品→下单支付」）")),
                                                "required", List.of(ROLE_KEY, ENTRY_KEY, KEY_ACTIONS_KEY)),
                                        "description", "核心旅程清单：几条真实用户端到端旅程"
                                                + "（角色＋入口＋关键动作串），挑验收最该走得通的几条"),
                                SLICE_SIZES_KEY, Map.of(
                                        "type", "array",
                                        "items", Map.of(
                                                "type", "string",
                                                "enum", closedSetTokens()),
                                        "description", "与 slices 等长：每片规模标签，取值只能是"
                                                + "「小」「中」「大」（按实现面积估）")),
                        "required", List.of(SLICES_KEY, CORE_JOURNEYS_KEY, SLICE_SIZES_KEY)))
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
        List<Set<Integer>> dependsOn = null;
        if (input.containsKey(DEPENDS_ON_KEY)) {
            dependsOn = parseDependsOn(input.get(DEPENDS_ON_KEY), slices.size());
            if (dependsOn == null) {
                return Mono.just(ToolResultBlock.error(
                        "dependsOn 须与 slices 等长：每项是依赖的前片序号列表（1-based），"
                                + "无依赖给空数组"));
            }
        }
        List<BuildPlan.CoreJourney> journeys =
                parseCoreJourneys(input.get(CORE_JOURNEYS_KEY));
        if (journeys == null) {
            return Mono.just(ToolResultBlock.error(
                    "coreJourneys 不能为空且每条须三要素齐全：role（角色：谁在用）、"
                            + "entry（入口：从哪个页面进来）、keyActions（关键动作串：一路按"
                            + "顺序做什么）——传几条真实用户端到端旅程"));
        }
        List<SliceSizeLabel> sizes = parseSliceSizes(input.get(SLICE_SIZES_KEY),
                slices.size());
        if (sizes == null) {
            return Mono.just(ToolResultBlock.error(
                    "sliceSizes 须与 slices 等长，且每项只能是「小」「中」「大」之一"
                            + "（按每片实现面积估）"));
        }
        buildPlanFacts.record(workspaceId, new BuildPlan(slices, dependsOn, journeys, sizes));
        return Mono.just(ToolResultBlock.text("构建计划已记录：" + slices.size() + " 个切片、"
                + journeys.size() + " 条核心旅程。"));
    }

    /** slices 解析：非空字符串列表；缺失/空清单/非字符串返回 null（模型可见错误）。 */
    private static List<String> parseSlices(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> slices = new ArrayList<>(list.size());
        for (Object item : list) {
            String text = textOf(item); // 同旅程要素口径：非空字符串净化
            if (text == null) {
                return null;
            }
            slices.add(text);
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

    /**
     * coreJourneys 解析（#423）：非空列表，每条是三要素齐的对象——role/entry/
     * keyActions 均为非空字符串（前后空白净化）。形态非法（缺失/空清单/条目非
     * 对象/要素缺失或空白）返回 null（模型可见错误——终验载荷契约不降级接收）。
     */
    private static List<BuildPlan.CoreJourney> parseCoreJourneys(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<BuildPlan.CoreJourney> journeys = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                return null;
            }
            String role = textOf(entry.get(ROLE_KEY));
            String entryPoint = textOf(entry.get(ENTRY_KEY));
            String keyActions = textOf(entry.get(KEY_ACTIONS_KEY));
            if (role == null || entryPoint == null || keyActions == null) {
                return null;
            }
            journeys.add(new BuildPlan.CoreJourney(role, entryPoint, keyActions));
        }
        return journeys;
    }

    /**
     * sliceSizes 解析（#423）：与 slices 等长的封闭集标签列表——每项 ∈ {小,中,大}
     * （{@link SliceSizeLabel#fromToken}）。形态非法（缺失/长度不符/非集内值）
     * 返回 null（模型可见错误——封闭集即契约，非集内值不入路由信号）。
     */
    private static List<SliceSizeLabel> parseSliceSizes(Object raw, int sliceCount) {
        if (!(raw instanceof List<?> list) || list.size() != sliceCount) {
            return null;
        }
        List<SliceSizeLabel> sizes = new ArrayList<>(sliceCount);
        for (Object item : list) {
            if (!(item instanceof String token)) {
                return null;
            }
            SliceSizeLabel size = SliceSizeLabel.fromToken(token);
            if (size == null) {
                return null;
            }
            sizes.add(size);
        }
        return sizes;
    }

    /** 旅程要素取值：非空字符串净化（trim）；其余形态返回 null。 */
    private static String textOf(Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    /** 规模标签封闭集 token 面（schema enum 单源——与 {@link SliceSizeLabel#fromToken} 同集）。 */
    private static List<String> closedSetTokens() {
        return java.util.Arrays.stream(SliceSizeLabel.values())
                .map(SliceSizeLabel::getName)
                .toList();
    }
}
