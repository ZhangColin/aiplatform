package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import com.semgravio.aiplatform.business.project.application.BuildPlan;
import com.semgravio.aiplatform.business.project.application.BuildPlanFacts;
import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;

/**
 * {@link SaveBuildPlanTool}（ADR 0009）：切片计划事实登记（有序纵向切片清单，后写
 * 胜出）+ 参数缺失/空片/非字符串回错误结果（模型可见可重试）+ 权限自检恒放行
 * （切片计划是 PRD 产出的伴生动作）。readOnly（不动工作区，效果仅平台侧事实）。
 * #423 结构化扩展：核心旅程清单＋片规模标签同批必产——合法径四件齐落事实、
 * 非法径（缺件/旅程条目残/标签集外/长度不符）拒绝不落事实。
 */
class SaveBuildPlanToolTest {

    private final BuildPlanFacts facts = new BuildPlanFacts();
    private final SaveBuildPlanTool tool = new SaveBuildPlanTool("42", facts);

    @Test
    void given_registration_shape_when_inspected_then_contract_keys_present() {
        assertThat(tool.getName()).isEqualTo("saveBuildPlan");
        assertThat(tool.getParameters()).containsKeys("type", "properties", "required");
        // #423：两件结构化扩展与 slices 同列必传（计划工具输出缝一次扩全）
        assertThat(String.valueOf(tool.getParameters().get("required")))
                .contains("slices").contains("coreJourneys").contains("sliceSizes");
        assertThat(tool.isReadOnly()).isTrue(); // 不动工作区，仅平台侧事实登记
    }

    @Test
    void given_any_call_when_check_permissions_then_always_allow() {
        // 切片计划是 PRD 产出的伴生动作（访谈协议的预期终点）：工具点不放确认
        PermissionDecision decision = tool
                .checkPermissions(Map.of("slices", List.of("用户能注册登录")), null).block();

        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void given_slices_when_called_then_plan_recorded_in_order() {
        ToolResultBlock result = callFull(fullInput(
                List.of("用户能注册登录", "用户能下单支付"), List.of("小", "中")));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isEqualTo(new BuildPlan(
                List.of("用户能注册登录", "用户能下单支付"),
                BuildPlan.sequentialDependencies(List.of("用户能注册登录", "用户能下单支付")),
                PARSED_JOURNEYS, List.of(SliceSizeLabel.SMALL, SliceSizeLabel.MEDIUM)));
        assertThat(resultText(result)).contains("2 个切片");
    }

    @Test
    void given_second_call_when_repeated_then_last_write_wins() {
        // 一轮多次 saveBuildPlan：交接物取终值（后写胜出——同 PrdRevisionFacts 口径）；
        // #423 两件结构化扩展同后写覆盖（演进重产的事实基座：终值即现行）
        callFull(fullInput(List.of("用户能注册登录"), List.of("小")));
        callFull(fullInput(List.of("用户能下单支付", "用户能查看订单"), List.of("中", "大")));

        assertThat(facts.consume("42")).isEqualTo(new BuildPlan(
                List.of("用户能下单支付", "用户能查看订单"),
                BuildPlan.sequentialDependencies(List.of("用户能下单支付", "用户能查看订单")),
                PARSED_JOURNEYS, List.of(SliceSizeLabel.MEDIUM, SliceSizeLabel.LARGE)));
    }

    @Test
    void given_missing_or_empty_slices_when_called_then_error_without_record() {
        ToolResultBlock missing = call(null);
        ToolResultBlock empty = call(List.of());

        assertThat(missing.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(empty.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("漏传/空清单不落计划事实").isNull();
    }

    @Test
    void given_blank_or_non_string_slice_when_called_then_error_without_record() {
        ToolResultBlock blank = callFull(fullInput(
                List.of("用户能注册登录", " "), List.of("小", "中")));
        ToolResultBlock nonString = callFull(fullInput(
                List.of("用户能注册登录", (Object) 42), List.of("小", "中")));

        assertThat(blank.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(nonString.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("空片/非字符串不落计划事实").isNull();
    }

    @Test
    void given_blank_padded_slice_when_called_then_trimmed_and_recorded() {
        // 切片描述前后空白作净化（不因空格误判空片）
        ToolResultBlock result = callFull(fullInput(
                List.of("  用户能注册登录  "), List.of(" 小 ")));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isEqualTo(new BuildPlan(
                List.of("用户能注册登录"),
                BuildPlan.sequentialDependencies(List.of("用户能注册登录")),
                PARSED_JOURNEYS, List.of(SliceSizeLabel.SMALL)));
    }

    // ---------- 结构化扩展（#423：核心旅程清单＋片规模标签） ----------

    /** 等形旅程清单（合法基线——三要素齐的两条旅程）。 */
    private static final List<Map<String, String>> JOURNEYS = List.of(
            Map.of("role", "买家", "entry", "首页", "keyActions", "注册登录→浏览商品→下单支付"),
            Map.of("role", "卖家", "entry", "商家后台登录页", "keyActions", "登录→上架商品→查看订单"));

    /** 旅程清单的解析形（断言侧期望值——与 JOURNEYS 逐条对应）。 */
    private static final List<BuildPlan.CoreJourney> PARSED_JOURNEYS = List.of(
            new BuildPlan.CoreJourney("买家", "首页", "注册登录→浏览商品→下单支付"),
            new BuildPlan.CoreJourney("卖家", "商家后台登录页", "登录→上架商品→查看订单"));

    @Test
    void given_journeys_and_sizes_when_called_then_plan_carries_both() {
        // #423 合法径：核心旅程清单（角色＋入口＋关键动作串）与每片规模标签（小/中/大
        // 封闭集）随切片计划同批结构化产出——事实登记取全量四件，不解析自由文本
        ToolResultBlock result = callFull(Map.of(
                "slices", List.of("用户能注册登录", "用户能下单支付"),
                "coreJourneys", List.of(
                        Map.of("role", "买家", "entry", "首页",
                                "keyActions", "注册登录→浏览商品→下单支付"),
                        Map.of("role", "卖家", "entry", "商家后台登录页",
                                "keyActions", "登录→上架商品→查看订单")),
                "sliceSizes", List.of("小", "大")));

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isEqualTo(new BuildPlan(
                List.of("用户能注册登录", "用户能下单支付"),
                BuildPlan.sequentialDependencies(List.of("用户能注册登录", "用户能下单支付")),
                List.of(new BuildPlan.CoreJourney("买家", "首页", "注册登录→浏览商品→下单支付"),
                        new BuildPlan.CoreJourney("卖家", "商家后台登录页", "登录→上架商品→查看订单")),
                List.of(SliceSizeLabel.SMALL, SliceSizeLabel.LARGE)));
        assertThat(resultText(result)).contains("2 个切片").contains("2 条核心旅程");
    }

    @Test
    void given_missing_journeys_or_sizes_when_called_then_error_without_record() {
        // #423 非法拒绝径一：两件必传——缺件即拒（计划工具输出含两件是硬契约，
        // 不降级接收无载荷计划）
        ToolResultBlock noJourneys = callFull(Map.of(
                "slices", List.of("用户能注册登录"),
                "sliceSizes", List.of("小")));
        ToolResultBlock noSizes = callFull(Map.of(
                "slices", List.of("用户能注册登录"),
                "coreJourneys", JOURNEYS));
        ToolResultBlock emptyJourneys = callFull(Map.of(
                "slices", List.of("用户能注册登录"),
                "coreJourneys", List.of(),
                "sliceSizes", List.of("小")));

        assertThat(noJourneys.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(noSizes.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(emptyJourneys.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("缺件/空旅程不落计划事实").isNull();
    }

    @Test
    void given_malformed_journey_entry_when_called_then_error_without_record() {
        // #423 非法拒绝径二：旅程条目结构校验——三要素（role/entry/keyActions）
        // 缺一或空白即拒（终验载荷契约不收残条目）
        ToolResultBlock missingRole = callFull(fullInputWithJourneys(
                List.of(Map.of("entry", "首页", "keyActions", "注册登录→下单"))));
        ToolResultBlock blankEntry = callFull(fullInputWithJourneys(
                List.of(Map.of("role", "买家", "entry", " ", "keyActions", "注册登录→下单"))));
        ToolResultBlock nonStringActions = callFull(fullInputWithJourneys(
                List.of(Map.of("role", "买家", "entry", "首页", "keyActions", 42))));

        assertThat(missingRole.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(blankEntry.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(nonStringActions.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("残旅程条目不落计划事实").isNull();
    }

    @Test
    void given_size_outside_closed_set_or_wrong_length_when_called_then_error_without_record() {
        // #423 非法拒绝径三：标签封闭集（只认 小/中/大）＋与 slices 等长——
        // 集外值或长度不符即拒（不入路由信号）
        ToolResultBlock outsideSet = callFull(fullInput(
                List.of("用户能注册登录", "用户能下单支付"), List.of("小", "超大")));
        ToolResultBlock wrongLength = callFull(fullInput(
                List.of("用户能注册登录", "用户能下单支付"), List.of("小")));
        ToolResultBlock nonString = callFull(fullInput(
                List.of("用户能注册登录"), List.of((Object) 7)));

        assertThat(outsideSet.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(wrongLength.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(nonString.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("集外/错长标签不落计划事实").isNull();
    }

    @Test
    void given_self_dependency_or_out_of_range_dep_when_called_then_error_without_record() {
        // 2026-11-11 生产事故收口：模型产出自依赖片序（切片 5 依赖 5），构造断言
        // 异常穿透 callAsync 直接杀死 run（补产轮随之耗尽，生成永久卡死）——
        // 值域/自依赖必须拒成模型可见错误（修正重调即可），不得穿透
        Map<String, Object> selfDep = new java.util.HashMap<>(fullInputWithJourneysAndSizes(
                List.of("片一", "片二", "片三", "片四", "片五"),
                JOURNEYS, List.of("小", "小", "中", "中", "大")));
        selfDep.put("dependsOn", List.of(
                List.of(), List.of(1), List.of(2), List.of(3), List.of(5))); // 片五自依赖
        Map<String, Object> outOfRange = new java.util.HashMap<>(fullInputWithJourneysAndSizes(
                List.of("片一", "片二"), JOURNEYS, List.of("小", "中")));
        outOfRange.put("dependsOn", List.of(List.of(), List.of(9))); // 越界片序

        ToolResultBlock selfResult = callFull(selfDep);
        ToolResultBlock rangeResult = callFull(outOfRange);

        assertThat(selfResult.getState()).as("自依赖＝模型可见错误，不穿透杀 run")
                .isEqualTo(ToolResultState.ERROR);
        assertThat(rangeResult.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).as("非法依赖不落计划事实").isNull();
    }

    @Test
    void given_valid_depends_on_when_called_then_plan_carries_dependencies() {
        Map<String, Object> input = new java.util.HashMap<>(fullInputWithJourneysAndSizes(
                List.of("片一", "片二", "片三"), JOURNEYS, List.of("小", "中", "大")));
        input.put("dependsOn", List.of(List.of(), List.of(1), List.of(1, 2)));

        ToolResultBlock result = callFull(input);

        assertThat(result.getState()).isNotEqualTo(ToolResultState.ERROR);
        BuildPlan plan = facts.consume("42");
        assertThat(plan).isNotNull();
        assertThat(plan.dependsOn(2)).containsExactlyInAnyOrder(1, 2);
    }

    // ---------- 内部 ----------

    private ToolResultBlock call(List<?> slices) {
        Map<String, Object> input = slices != null ? Map.of("slices", slices) : Map.of();
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("tc-1", SaveBuildPlanTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    private ToolResultBlock callFull(Map<String, Object> input) {
        ToolCallParam param = ToolCallParam.builder()
                .toolUseBlock(new ToolUseBlock("tc-1", SaveBuildPlanTool.NAME, input, null))
                .input(input)
                .build();
        return Mono.from(tool.callAsync(param)).block();
    }

    /** 全量合法输入拼装（#423 契约形：slices＋coreJourneys＋sliceSizes）。 */
    private static Map<String, Object> fullInput(List<?> slices, List<?> sizes) {
        return fullInputWithJourneysAndSizes(slices, JOURNEYS, sizes);
    }

    /** 全量输入（旅程可替换——条目结构非法径专用）。 */
    private static Map<String, Object> fullInputWithJourneys(
            List<Map<String, Object>> journeys) {
        return fullInputWithJourneysAndSizes(List.of("用户能注册登录"), journeys, List.of("小"));
    }

    private static Map<String, Object> fullInputWithJourneysAndSizes(List<?> slices,
            List<? extends Map<String, ?>> journeys, List<?> sizes) {
        return Map.of(
                "slices", slices,
                "coreJourneys", journeys,
                "sliceSizes", sizes);
    }

    private static String resultText(ToolResultBlock result) {
        return ((TextBlock) result.getOutput().get(0)).getText();
    }
}
