package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;

/**
 * 模型路由判定单测（#354 判定单点 {@link ModelRoutingAppService#decide} 纯函数
 * 直测——协同口径：路由判定优先、人工覆盖兜底；钉死位＝路由关；信号三件任一
 * 越阈即升强档）＋文件面大小信号采集（exec 失败/不可解析＝无此信号、降级不升档）。
 */
class ModelRoutingAppServiceTest {

    /** 路由开的 execute 座生效配置（无人工档覆盖、缺省策略——兜底＝枚举缺省快档）。 */
    private static AgentConfigAppService.EffectiveConfig routedDefault() {
        return new AgentConfigAppService.EffectiveConfig("prompt", "deepseek-v4-flash",
                "deepseek:deepseek-v4-flash", true, true, false, true, null, null, null);
    }

    /** 路由开＋人工档覆盖（覆盖＝兜底基础档——路由判定仍可升强档）。 */
    private static AgentConfigAppService.EffectiveConfig routedWithOverride() {
        return new AgentConfigAppService.EffectiveConfig("prompt", "deepseek-v4-flash",
                "deepseek:deepseek-v4-flash", true, true, true, true, null, null, null);
    }

    /** 路由关＋人工档覆盖（钉死位——人工档恒生效）。 */
    private static AgentConfigAppService.EffectiveConfig pinned() {
        return new AgentConfigAppService.EffectiveConfig("prompt", "deepseek-v4-pro",
                "deepseek:deepseek-v4-pro", true, true, true, false, null, null, null);
    }

    // ---------- 协同口径：路由判定优先、人工覆盖兜底；钉死位＝路由关 ----------

    @Test
    void given_routing_on_no_signals_when_decide_then_fast_default() {
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, 10, "用户能注册登录");
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(selection.chatModelString()).isEqualTo("deepseek:deepseek-v4-flash");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_ROUTED);
        assertThat(selection.reasons()).isEmpty();
    }

    @Test
    void given_override_without_signals_when_decide_then_manual_fallback_base_tier() {
        // 人工覆盖兜底：无信号时回落人工档（覆盖即基础档——快档位可经 modelId
        // 配置、不关路由）
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedWithOverride(), 1, 1, 10, null);
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_ROUTED);
        assertThat(selection.reasons()).isEmpty();
    }

    @Test
    void given_override_with_signals_when_decide_then_routing_escalation_wins() {
        // 路由判定优先：信号升强档优先于人工档（覆盖是兜底不是钉死）
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedWithOverride(), 2, 1, null, null);
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(selection.reasons()).containsExactly("重试升档（第 2 次尝试）");
    }

    @Test
    void given_retry_attempt_when_decide_then_escalates_to_strong() {
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 2, 1, null, null);
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(selection.reasons()).containsExactly("重试升档（第 2 次尝试）");
    }

    @Test
    void given_rerun_round_when_decide_then_escalates_to_strong() {
        // #353 外环：首轮重试耗尽回滚重跑——首试（attempt 1）也升强档
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 2, null, null);
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(selection.reasons()).containsExactly("回滚重跑升档（首轮重试耗尽）");
    }

    @Test
    void given_file_count_at_threshold_when_decide_then_escalates_with_boundary() {
        int threshold = ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD;
        ModelRoutingAppService.TierSelection atThreshold =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, threshold, null);
        assertThat(atThreshold.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(atThreshold.reasons()).containsExactly(
                "文件面超出阈值（" + threshold + " ≥ " + threshold + "）");
        // 阈值下界不升档（越阈判定的边界语义）
        ModelRoutingAppService.TierSelection below =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, threshold - 1, null);
        assertThat(below.modelId()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void given_long_slice_description_when_decide_then_escalates() {
        int threshold = ModelRoutingAppService.DEFAULT_SLICE_LENGTH_THRESHOLD;
        String fat = "用户能注册登录、浏览商品、下单支付并查看历史订单";
        assertThat(fat.strip().length()).isGreaterThanOrEqualTo(threshold);
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 1, 1, null, fat);
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(selection.reasons()).hasSize(1)
                .anySatisfy(reason -> assertThat(reason).contains("片复杂度超出阈值"));
    }

    @Test
    void given_multiple_signals_when_decide_then_reasons_list_all() {
        int threshold = ModelRoutingAppService.DEFAULT_FILE_COUNT_THRESHOLD;
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 3, 1, threshold, "用户能注册登录"
                        + "、浏览商品、下单支付、管理订单、查看报表与个人中心");
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(selection.reasons()).hasSize(3);
    }

    @Test
    void given_routing_off_with_override_when_decide_then_pinned_manual_tier() {
        // 钉死位＝路由关：人工档恒生效——重试/文件面/片复杂度全在场也不升档
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(pinned(), 3, 2, 999, "用户能注册登录、浏览商品"
                        + "、下单支付、管理订单、查看报表与个人中心、后台统计与导出");
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-pro");
        assertThat(selection.chatModelString()).isEqualTo("deepseek:deepseek-v4-pro");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_PINNED);
        assertThat(selection.reasons()).isEmpty();
    }

    @Test
    void given_routing_off_without_override_when_decide_then_enum_default_even_on_failure() {
        AgentConfigAppService.EffectiveConfig disabledDefault =
                new AgentConfigAppService.EffectiveConfig("prompt", "deepseek-v4-flash",
                        "deepseek:deepseek-v4-flash", true, true, false, false, null, null, null);
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(disabledDefault, 3, 2, 999, null);
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-flash");
        assertThat(selection.source()).isEqualTo(ModelRoutingAppService.SOURCE_DEFAULT);
    }

    @Test
    void given_config_custom_strategy_when_decide_then_override_values_win() {
        AgentConfigAppService.EffectiveConfig custom =
                new AgentConfigAppService.EffectiveConfig("prompt", "deepseek-v4-flash",
                        "deepseek:deepseek-v4-flash", true, true, false, true,
                        "deepseek-v4-ultra", 5, 7);
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(custom, 1, 1, 5, "用户能下单支付");
        assertThat(selection.modelId()).isEqualTo("deepseek-v4-ultra");
        assertThat(selection.reasons()).hasSize(2);
        assertThat(selection.signals()).containsEntry("fileCountThreshold", 5)
                .containsEntry("sliceLengthThreshold", 7);
    }

    @Test
    void given_selection_when_payload_then_carry_tier_source_reasons_and_signals() {
        ModelRoutingAppService.TierSelection selection =
                ModelRoutingAppService.decide(routedDefault(), 2, 1, 87, "用户能注册登录");
        Map<String, Object> payload = selection.payload();
        assertThat(payload).containsEntry("modelId", "deepseek-v4-pro")
                .containsEntry("source", ModelRoutingAppService.SOURCE_ROUTED);
        assertThat(payload).containsKey("reasons").containsKey("signals");
        // 快档判定（无升档）不携带 reasons（缺省不携带——留档只记为什么升）
        assertThat(ModelRoutingAppService.decide(routedDefault(), 1, 1, null, null)
                .payload()).doesNotContainKey("reasons");
    }

    // ---------- 文件面大小信号采集 ----------

    @Test
    void given_exec_ok_when_count_files_then_parsed_count() {
        WorkspaceLifecycleAppService workspaces = mock(WorkspaceLifecycleAppService.class);
        when(workspaces.exec(anyString(), any(WorkspaceExecCommand.class)))
                .thenReturn(new ExecResultResponse(" 87\n", "", 0));
        ModelRoutingAppService service = new ModelRoutingAppService(workspaces);
        assertThat(service.countTrackedFiles("1")).isEqualTo(87);
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
