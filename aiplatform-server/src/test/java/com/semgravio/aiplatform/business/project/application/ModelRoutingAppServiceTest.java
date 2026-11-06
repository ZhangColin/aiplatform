package com.semgravio.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.semgravio.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.semgravio.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.semgravio.aiplatform.business.project.domain.enums.SliceSizeLabel;

/**
 * 模型路由判定单测（#354 判定单点 {@link ModelRoutingAppService#decide} 纯函数
 * 直测——协同口径：路由判定优先、人工覆盖兜底；钉死位＝路由关；信号任一越阈即
 * 升强档）＋文件面大小信号采集（exec 失败/不可解析＝无此信号、降级不升档；探针
 * 输出已是「扣基座基数后」的应用文件面）。信号面＝#426 修订：规模标签（小/中/大
 * 封闭集，主智能体计划自报——#423）替换枚举密度信号（度量描述写法非实现面积，
 * 退役）；文件面排除基座基数后阈值重校。档位值为语义键（ADR-0031：flash/pro
 * ——模型串与通道注入由 ModelTierAppService 解析，判定层只见键）。
 */
class ModelRoutingAppServiceTest {

    /** 路由开的 execute 座生效配置（无人工档覆盖、缺省策略——兜底＝枚举缺省快档）。 */
    private static AgentConfigAppService.EffectiveConfig routedDefault() {
        return new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                true, true, false, true, null, null, null, null, 3);
    }

    /** 路由开＋人工档覆盖（覆盖＝兜底基础档——路由判定仍可升强档）。 */
    private static AgentConfigAppService.EffectiveConfig routedWithOverride() {
        return new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                true, true, true, true, null, null, null, null, 3);
    }

    /** 路由关＋人工档覆盖（钉死位——人工档恒生效）。 */
    private static AgentConfigAppService.EffectiveConfig pinned() {
        return new AgentConfigAppService.EffectiveConfig("prompt", "pro",
                true, true, true, false, null, null, null, null, 3);
    }

    /** 路由开＋自定义映射（升档规模标签集＝中＋大）与文件面阈值覆盖。 */
    private static AgentConfigAppService.EffectiveConfig routedWithMapping(
            Set<SliceSizeLabel> escalating, Integer fileThreshold) {
        return new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                true, true, false, true, null, fileThreshold, escalating, null, 3);
    }

    // ---------- 协同口径：路由判定优先、人工覆盖兜底；钉死位＝路由关 ----------

    @Test
    void given_routing_on_no_signals_when_decide_then_fast_default() {
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, 10, null);
        assertThat(selection.modelId()).isEqualTo("flash");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_ROUTED);
        assertThat(selection.reasons()).isEmpty();
    }

    @Test
    void given_override_without_signals_when_decide_then_manual_fallback_base_tier() {
        // 人工覆盖兜底：无信号时回落人工档（覆盖即基础档——快档位可经 modelId
        // 配置、不关路由）
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedWithOverride(), 1, 1, 10, null);
        assertThat(selection.modelId()).isEqualTo("flash");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_ROUTED);
        assertThat(selection.reasons()).isEmpty();
    }

    @Test
    void given_override_with_signals_when_decide_then_routing_escalation_wins() {
        // 路由判定优先：信号升强档优先于人工档（覆盖是兜底不是钉死）
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedWithOverride(), 2, 1, null, null);
        assertThat(selection.modelId()).isEqualTo("pro");
        assertThat(selection.reasons()).containsExactly("重试升档（第 2 次尝试）");
    }

    // ---------- 信号一（保留，#426）：历史失败——重试/回滚重跑升档 ----------

    @Test
    void given_retry_attempt_when_decide_then_escalates_to_strong() {
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 2, 1, null, null);
        assertThat(selection.modelId()).isEqualTo("pro");
        assertThat(selection.reasons()).containsExactly("重试升档（第 2 次尝试）");
    }

    @Test
    void given_rerun_round_when_decide_then_escalates_to_strong() {
        // #353 外环：首轮重试耗尽回滚重跑——首试（attempt 1）也升强档
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 2, null, null);
        assertThat(selection.modelId()).isEqualTo("pro");
        assertThat(selection.reasons()).containsExactly("回滚重跑升档（首轮重试耗尽）");
    }

    // ---------- 信号二（#426 重校）：文件面大小——应用文件面（已扣基座基数）越阈 ----------

    @Test
    void given_file_count_at_threshold_when_decide_then_escalates_with_boundary() {
        int threshold = ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD;
        ModelRoutingAppService.TierSelection atThreshold =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, threshold, null);
        assertThat(atThreshold.modelId()).isEqualTo("pro");
        assertThat(atThreshold.reasons()).containsExactly(
                "文件面超出阈值（" + threshold + " ≥ " + threshold + "）");
        // 阈值下界不升档（越阈判定的边界语义）
        ModelRoutingAppService.TierSelection below =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, threshold - 1, null);
        assertThat(below.modelId()).isEqualTo("flash");
    }

    // ---------- 信号三（#426 替换）：规模标签映射——缺省「大」升强档、小/中回落 ----------

    @Test
    void given_large_slice_when_decide_then_escalates_by_default_mapping() {
        // 缺省映射＝「大」升强档（多页面多接口的重功能片——描述短功能重的锚案
        // 大片不再被误留快档）；理由携标签快照
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, null, SliceSizeLabel.LARGE);
        assertThat(selection.modelId()).isEqualTo("pro");
        assertThat(selection.reasons()).containsExactly("规模标签升档（大）");
    }

    @Test
    void given_small_or_medium_slice_when_decide_then_stay_fast_by_default() {
        // 小/中回落人工档兜底（快档）——缺省映射只升「大」
        assertThat(ModelRoutingAppService.decide(routedDefault(), 1, 1, null,
                SliceSizeLabel.SMALL).modelId()).isEqualTo("flash");
        assertThat(ModelRoutingAppService.decide(routedDefault(), 1, 1, null,
                SliceSizeLabel.MEDIUM).modelId()).isEqualTo("flash");
    }

    @Test
    void given_unlabeled_slice_when_decide_then_no_size_signal() {
        // 未设标签（存量计划/阶段 0）＝无此信号——存量不回补、降级不升档
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, null, null);
        assertThat(selection.modelId()).isEqualTo("flash");
        assertThat(selection.reasons()).isEmpty();
        assertThat(selection.signals()).containsEntry("sizeLabel", null);
    }

    @Test
    void given_mapping_override_when_decide_then_configured_labels_escalate() {
        // 映射经路由策略配置面可调（不发版）：运营改映射为「中＋大」→ 中片也升档
        AgentConfigAppService.EffectiveConfig mediumAndLarge =
                routedWithMapping(Set.of(SliceSizeLabel.MEDIUM, SliceSizeLabel.LARGE), null);
        assertThat(ModelRoutingAppService.decide(mediumAndLarge, 1, 1, null,
                SliceSizeLabel.MEDIUM).modelId()).isEqualTo("pro");
        assertThat(ModelRoutingAppService.decide(mediumAndLarge, 1, 1, null,
                SliceSizeLabel.MEDIUM).reasons()).containsExactly("规模标签升档（中）");
        // 小片不在映射内仍回落
        assertThat(ModelRoutingAppService.decide(mediumAndLarge, 1, 1, null,
                SliceSizeLabel.SMALL).modelId()).isEqualTo("flash");
        // 映射收窄为仅「大」→ 中片回落（缺省映射可经配置收回）
        AgentConfigAppService.EffectiveConfig largeOnly =
                routedWithMapping(Set.of(SliceSizeLabel.LARGE), null);
        assertThat(ModelRoutingAppService.decide(largeOnly, 1, 1, null,
                SliceSizeLabel.MEDIUM).modelId()).isEqualTo("flash");
    }

    @Test
    void given_empty_mapping_when_decide_then_size_signal_never_escalates() {
        // 空映射＝规模标签信号整体关停（合法运营选择——重试/文件面信号不受影响，
        // 对偶旧阈值「0＝恒升强档」的关停端）
        AgentConfigAppService.EffectiveConfig emptyMapping =
                routedWithMapping(Set.of(), null);
        assertThat(ModelRoutingAppService.decide(emptyMapping, 1, 1, null,
                SliceSizeLabel.LARGE).modelId()).isEqualTo("flash");
        // 文件面信号独立在场仍升档（OR 组合——信号互不掩护）
        assertThat(ModelRoutingAppService.decide(emptyMapping, 1, 1,
                ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD,
                SliceSizeLabel.LARGE).reasons())
                .containsExactly("文件面超出阈值（"
                        + ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD
                        + " ≥ " + ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD + "）");
    }

    @Test
    void given_multiple_signals_when_decide_then_reasons_list_all() {
        int threshold = ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD;
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 3, 1, threshold,
                        SliceSizeLabel.LARGE);
        assertThat(selection.modelId()).isEqualTo("pro");
        assertThat(selection.reasons()).hasSize(3);
    }

    // ---------- 钉死位（路由关）与缺省档 ----------

    @Test
    void given_routing_off_with_override_when_decide_then_pinned_manual_tier() {
        // 钉死位＝路由关：人工档恒生效——重试/文件面/规模标签全在场也不升档
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(pinned(), 3, 2, 999, SliceSizeLabel.LARGE);
        assertThat(selection.modelId()).isEqualTo("pro");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_PINNED);
        assertThat(selection.reasons()).isEmpty();
    }

    @Test
    void given_routing_off_without_override_when_decide_then_enum_default_even_on_failure() {
        AgentConfigAppService.EffectiveConfig disabledDefault =
                new AgentConfigAppService.EffectiveConfig("prompt", "flash",
                        true, true, false, false, null, null, null, null, 3);
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(disabledDefault, 3, 2, 999, null);
        assertThat(selection.modelId()).isEqualTo("flash");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_DEFAULT);
    }

    @Test
    void given_config_custom_strategy_when_decide_then_override_values_win() {
        AgentConfigAppService.EffectiveConfig custom =
                routedWithMapping(Set.of(SliceSizeLabel.MEDIUM, SliceSizeLabel.LARGE), 5);
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(custom, 1, 1, 5, SliceSizeLabel.MEDIUM);
        assertThat(selection.modelId()).isEqualTo("pro");
        assertThat(selection.reasons()).hasSize(2);
        assertThat(selection.signals())
                .containsEntry("fileCountThreshold", 5)
                .containsEntry("sizeLabel", "中")
                .containsEntry("escalatingSizeLabels", java.util.List.of("中", "大"));
    }

    @Test
    void given_selection_when_payload_then_carry_tier_source_reasons_and_signals() {
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, null, SliceSizeLabel.LARGE);
        Map<String, Object> payload = selection.payload();
        assertThat(payload).containsEntry("modelId", "pro")
                .containsEntry("source", ModelRoutingAppService.SOURCE_ROUTED);
        assertThat(payload).containsKey("reasons").containsKey("signals");
        assertThat((Map<String, Object>) payload.get("signals"))
                .containsEntry("sizeLabel", "大")
                .containsEntry("escalatingSizeLabels", java.util.List.of("大"));
        // 快档判定（无升档）不携带 reasons（缺省不携带——留档只记为什么升）
        assertThat(ModelRoutingAppService.decide(routedDefault(), 1, 1, null, null)
                .payload()).doesNotContainKey("reasons");
    }

    // ---------- 文件面大小信号采集（探针输出＝已扣基座基数的应用文件面） ----------

    @Test
    void given_exec_ok_when_count_files_then_parsed_count() {
        WorkspaceLifecycleAppService workspaces = mock(WorkspaceLifecycleAppService.class);
        when(workspaces.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse(" 65\n", "", 0));
        ModelRoutingAppService service = new ModelRoutingAppService(workspaces);
        assertThat(service.countTrackedFiles("1")).isEqualTo(65);
    }

    @Test
    void given_negative_probe_output_when_count_files_then_clamped_to_zero() {
        // 阶段 0 起跑面：空仓库 tracked=0 − 基座基数 → 负值＝尚无应用文件，钳 0
        WorkspaceLifecycleAppService workspaces = mock(WorkspaceLifecycleAppService.class);
        when(workspaces.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("-22\n", "", 0));
        ModelRoutingAppService service = new ModelRoutingAppService(workspaces);
        assertThat(service.countTrackedFiles("1")).isZero();
    }

    @Test
    void given_exec_failure_when_count_files_then_null_signal() {
        WorkspaceLifecycleAppService workspaces = mock(WorkspaceLifecycleAppService.class);
        when(workspaces.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("", "not a repo", 128));
        ModelRoutingAppService service = new ModelRoutingAppService(workspaces);
        assertThat(service.countTrackedFiles("1")).isNull();
    }

    @Test
    void given_unparseable_or_throwing_exec_when_count_files_then_null_signal() {
        WorkspaceLifecycleAppService workspaces = mock(WorkspaceLifecycleAppService.class);
        when(workspaces.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse("garbage", "", 0))
                .thenThrow(new IllegalStateException("workspace down"));
        ModelRoutingAppService service = new ModelRoutingAppService(workspaces);
        assertThat(service.countTrackedFiles("1")).isNull();
        assertThat(service.countTrackedFiles("1")).isNull();
    }

    @Test
    void given_routing_off_when_select_then_zero_exec_signal_collection() {
        // 钉死路径（路由关）零信号采集——exec 不白跑（判定先于采集；路由开＋
        // 覆盖的兜底路径仍需采集——升档可能越过覆盖）
        WorkspaceLifecycleAppService workspaces = mock(WorkspaceLifecycleAppService.class);
        ModelRoutingAppService service = new ModelRoutingAppService(workspaces);
        ModelRoutingAppService.TierSelection selection =
                service.select(pinned(), "1", 1, 1, ModelRoutingAppService.SegmentSignals.none());
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_PINNED);
        verifyNoInteractions(workspaces);
    }
}
