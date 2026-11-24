package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;

import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.semgravio.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * {@link AgentscopePartsMapper} 单点映射表（parts 契约的唯一生产方）：AgentScope
 * 事件 → 消息部件事件（part-signal / part-action）。口径：动作卡全生命周期（开始
 * 即出事件——工具调用发起即 started，参数落定 running，结果返回 completed/failed，
 * 同一 toolCallId 锚定）；模型文本不直播（ADR-0057/#608——解说段不出部件），机器
 * 语法吞段仍即时产脱轨信号（#240）；思考与读类工具不进部件。步骤分组已退役
 * （#115）：ModelCallStartEvent 不再产 part-step。
 * 每事件 payload 盖 runId/sessionId/engine + 部件字段（扁平，无 data 键）。
 */
class AgentscopePartsMapperTest {

    private static final String RUN_ID = "r1";
    private static final String SESSION_ID = "s1";
    private static final String ENGINE = "agentscope";

    private final AgentscopePartsMapper mapper = new AgentscopePartsMapper(RUN_ID, SESSION_ID, ENGINE);

    private List<String> types(List<AgentEvent> parts) {
        return parts.stream().map(AgentEvent::type).toList();
    }

    @Nested
    class NarrationNotPresented {

        /** 人话文本增量（含完整句）不出任何部件——模型文本不直播（ADR-0057）。 */
        @Test
        void given_narration_when_mapped_then_no_part() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在编写"))).isEmpty();
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "订单管理页面。"))).isEmpty();
        }

        /** 无句读尾段同样不出部件（无尾段可出——原件随 #608 退役）。 */
        @Test
        void given_tail_without_ender_when_mapped_then_no_part() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "马上就好"))).isEmpty();
        }
    }

    @Nested
    class ActionParts {

        @Test
        void given_write_action_when_full_lifecycle_then_started_running_completed_same_anchor() {
            List<AgentEvent> started = mapper.map(new ToolCallStartEvent("r", "tc-1", "write_file"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-1", "write_file",
                    "{\"path\":\"src/pages/订单管理.tsx\"}"));
            List<AgentEvent> running = mapper.map(new ToolCallEndEvent("r", "tc-1", "write_file"));
            List<AgentEvent> completed = mapper.map(
                    new ToolResultEndEvent("r", "tc-1", "write_file", ToolResultState.SUCCESS));

            assertThat(types(started)).containsExactly(AgentEventTypes.PART_ACTION);
            assertThat(started.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ENGINE_FIELD, ENGINE,
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "tc-1",
                    AgentEventTypes.PART_ACTION_TOOL_NAME_FIELD, "write_file",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_STARTED,
                    // 参数在途：动作对象为通用标签——动作卡出现即「进行中」（#77 补动作开始）
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【代码文件】"));
            // running：参数落定，动作对象具体化（无时态——时态由 state 表达）
            assertThat(running.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_RUNNING,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【订单管理】"));
            // completed：复述已锚定的具体对象，不闪回通用标签
            assertThat(completed.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_COMPLETED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "编写【订单管理】"));
        }

        @Test
        void given_error_result_when_lifecycle_completes_then_failed_state() {
            mapper.map(new ToolCallStartEvent("r", "tc-2", "execute"));
            mapper.map(new ToolCallEndEvent("r", "tc-2", "execute"));

            List<AgentEvent> failed = mapper.map(
                    new ToolResultEndEvent("r", "tc-2", "execute", ToolResultState.ERROR));

            assertThat(failed.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.PART_ACTION_TOOL_CALL_FIELD, "tc-2",
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_FAILED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行命令"));
        }

        @Test
        void given_denied_and_interrupted_results_when_lifecycle_completes_then_failed_state() {
            for (ToolResultState state : List.of(ToolResultState.DENIED, ToolResultState.INTERRUPTED)) {
                AgentscopePartsMapper fresh = new AgentscopePartsMapper(RUN_ID, SESSION_ID, ENGINE);
                fresh.map(new ToolCallStartEvent("r", "tc-9", "execute"));
                List<AgentEvent> parts = fresh.map(new ToolResultEndEvent("r", "tc-9", "execute", state));
                assertThat(parts.get(0).payload()).containsEntry(
                        AgentEventTypes.PART_ACTION_STATE_FIELD,
                        AgentEventTypes.PART_ACTION_STATE_FAILED);
            }
        }

        @Test
        void given_read_tools_when_mapped_then_no_action_parts() {
            assertThat(mapper.map(new ToolCallStartEvent("r", "tc-3", "read_file"))).isEmpty();
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-3", "read_file"))).isEmpty();
            assertThat(mapper.map(new ToolResultEndEvent("r", "tc-3", "read_file",
                    ToolResultState.SUCCESS))).isEmpty();
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-4", "grep_files"))).isEmpty();
        }

    }

    /**
     * 命令原值上滚动行（#228）：execute 的 label 动态化为命令原文首行——剥
     * `bash -c` / `sh -c` 壳与 `cd … &&` 前缀（其余裸显）、单行定宽截断；参数
     * 在途 / 解析不出回落「运行命令」。
     */
    @Nested
    class CommandLabels {

        @Test
        void given_execute_command_args_when_full_lifecycle_then_label_is_command_raw() {
            List<AgentEvent> started = mapper.map(new ToolCallStartEvent("r", "tc-c1", "execute"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-c1", "execute",
                    "{\"command\":\"npm test --filter auth\"}"));
            List<AgentEvent> running = mapper.map(new ToolCallEndEvent("r", "tc-c1", "execute"));
            List<AgentEvent> completed = mapper.map(
                    new ToolResultEndEvent("r", "tc-c1", "execute", ToolResultState.SUCCESS));

            // started：参数在途 → 通用标签（同写文件类通用对象形态）
            assertThat(started.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "运行命令");
            // running：参数落定 → 命令原值首行（滚动行 = live tail）
            assertThat(running.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "npm test --filter auth");
            // completed：复述已锚定命令原值，不闪回通用标签
            assertThat(completed.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "npm test --filter auth");
        }

        @Test
        void given_command_args_in_chunks_when_accumulated_then_label_is_whole_command() {
            mapper.map(new ToolCallStartEvent("r", "tc-c2", "execute"));
            // 参数增量分片到达（流式 JSON），累积后解析
            mapper.map(new ToolCallDeltaEvent("r", "tc-c2", "execute", "{\"comm"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-c2", "execute", "and\":\"pnpm build"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-c2", "execute", "\"}"));

            List<AgentEvent> running = mapper.map(new ToolCallEndEvent("r", "tc-c2", "execute"));

            assertThat(running.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "pnpm build");
        }

        @Test
        void given_wrapped_commands_when_settled_then_label_stripped_to_core() {
            assertCoreCommand("{\"command\":\"bash -c 'npm test'\"}", "npm test");
            assertCoreCommand("{\"command\":\"sh -c \\\"npm run build\\\"\"}", "npm run build");
            assertCoreCommand("{\"command\":\"cd /app && npm test\"}", "npm test");
            assertCoreCommand("{\"command\":\"cd 'src/ui' && pnpm lint\"}", "pnpm lint");
            // 双层包裹：壳内有 cd 前缀——两规则都剥
            assertCoreCommand("{\"command\":\"bash -c \\\"cd /app && npm test\\\"\"}", "npm test");
            // 多级 cd 链与带工作目录参数的命令：工作目录噪音全剥
            assertCoreCommand("{\"command\":\"cd /a && cd /b && mvn verify\"}", "mvn verify");
            // 其余连接形态裸显（不越权改写命令语义）
            assertCoreCommand("{\"command\":\"npm install && npm test\"}", "npm install && npm test");
        }

        @Test
        void given_multiline_command_when_settled_then_label_is_first_line_only() {
            assertCoreCommand("{\"command\":\"npm install\\nnpm test\\n\"}", "npm install");
        }

        @Test
        void given_overlong_command_when_settled_then_label_truncated_single_line() {
            // 定宽（含省略号）：超出截断——事件载荷的长度界，前端行内截断样式之外的第二道界
            String over = "y".repeat(ToolActionLines.COMMAND_LABEL_MAX + 40);
            assertCoreCommand("{\"command\":\"" + over + "\"}",
                    "y".repeat(ToolActionLines.COMMAND_LABEL_MAX - 1) + "…");
            // 恰在宽度内：原样不截
            String exact = "y".repeat(ToolActionLines.COMMAND_LABEL_MAX);
            assertCoreCommand("{\"command\":\"" + exact + "\"}", exact);
        }

        @Test
        void given_running_result_replay_when_args_already_settled_then_label_keeps_command_raw() {
            // 挂起重放（ToolResultEnd RUNNING）：参数已在落定点成形且保留至终态——
            // 重算不闪回通用标签（#228 review：锚存退役、非终态恒重算取最新）
            mapper.map(new ToolCallStartEvent("r", "tc-r1", "execute"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-r1", "execute",
                    "{\"command\":\"npm test\"}"));
            mapper.map(new ToolCallEndEvent("r", "tc-r1", "execute"));

            List<AgentEvent> stillRunning = mapper.map(
                    new ToolResultEndEvent("r", "tc-r1", "execute", ToolResultState.RUNNING));

            assertThat(stillRunning.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "npm test");
        }

        @Test
        void given_no_or_unparsable_command_args_when_settled_then_generic_label() {
            // 无参数增量（调用落定但命令未到达）→ 通用标签
            assertExecuteLabel(null, "运行命令");
            // 参数流不完整（非 JSON）→ 通用标签
            assertExecuteLabel("{\"comm", "运行命令");
            // command 非字符串 / 空白 → 通用标签
            assertExecuteLabel("{\"timeout\":60}", "运行命令");
            assertExecuteLabel("{\"command\":\"   \"}", "运行命令");
        }

        private void assertCoreCommand(String args, String expected) {
            assertExecuteLabel(args, expected);
        }

        /** 断言序列号（参数保留至终态的新语义下，多断言不可共用 toolCallId）。 */
        private int seq;

        private void assertExecuteLabel(String args, String expected) {
            String id = "tc-lx" + seq++;
            mapper.map(new ToolCallStartEvent("r", id, "execute"));
            if (args != null) {
                mapper.map(new ToolCallDeltaEvent("r", id, "execute", args));
            }
            List<AgentEvent> running = mapper.map(new ToolCallEndEvent("r", id, "execute"));
            assertThat(running.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, expected);
        }
    }

    /**
     * 失败留痕带原因（#229）：part-action 新增可选 error 字段——仅 failed 携带，
     * 取工具结果文本（错误/stderr，经 ToolResultTextDelta 累积——同参数增量口径）
     * 首行截断（与 label 各管各的额度）；completed / 无结果文本不携带。
     */
    @Nested
    class FailureTraces {

        @Test
        void given_failed_result_with_text_when_terminal_then_error_first_line_alongside_label() {
            mapper.map(new ToolCallStartEvent("r", "tc-e1", "execute"));
            mapper.map(new ToolCallDeltaEvent("r", "tc-e1", "execute",
                    "{\"command\":\"npm test\"}"));
            mapper.map(new ToolResultTextDeltaEvent("r", "tc-e1", "execute", "Error: 容器不可达"));
            List<AgentEvent> failed = mapper.map(
                    new ToolResultEndEvent("r", "tc-e1", "execute", ToolResultState.ERROR));

            // 失败红行双要素：label 复述命令原值 + error 首行（没做成＋为什么）
            assertThat(failed.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.PART_ACTION_STATE_FIELD, AgentEventTypes.PART_ACTION_STATE_FAILED,
                    AgentEventTypes.PART_ACTION_LABEL_FIELD, "npm test",
                    AgentEventTypes.PART_ACTION_ERROR_FIELD, "Error: 容器不可达"));
        }

        @Test
        void given_chunked_multiline_failure_output_when_terminal_then_accumulated_first_line_only() {
            mapper.map(new ToolCallStartEvent("r", "tc-e2", "execute"));
            // 结果文本增量分片到达（流式），累积后取首行
            mapper.map(new ToolResultTextDeltaEvent("r", "tc-e2", "execute", "npm err! code ELIFECYCLE\n"));
            mapper.map(new ToolResultTextDeltaEvent("r", "tc-e2", "execute", "npm err! syscall spawn"));

            List<AgentEvent> failed = mapper.map(
                    new ToolResultEndEvent("r", "tc-e2", "execute", ToolResultState.ERROR));

            assertThat(failed.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_ERROR_FIELD, "npm err! code ELIFECYCLE");
        }

        @Test
        void given_overlong_error_line_when_terminal_then_truncated_own_budget() {
            // 各管各的额度：error 截断界独立于 label（COMMAND_LABEL_MAX）——超长首行截断
            String over = "e".repeat(ToolActionLines.ERROR_LINE_MAX + 40);
            assertFailedError(over, "e".repeat(ToolActionLines.ERROR_LINE_MAX - 1) + "…");
            // 恰在宽度内：原样不截
            String exact = "e".repeat(ToolActionLines.ERROR_LINE_MAX);
            assertFailedError(exact, exact);
        }

        @Test
        void given_completed_result_with_text_when_terminal_then_no_error_field() {
            mapper.map(new ToolCallStartEvent("r", "tc-e3", "write_file"));
            mapper.map(new ToolResultTextDeltaEvent("r", "tc-e3", "write_file", "文件已写入"));
            List<AgentEvent> completed = mapper.map(
                    new ToolResultEndEvent("r", "tc-e3", "write_file", ToolResultState.SUCCESS));

            assertThat(completed.get(0).payload())
                    .containsEntry(AgentEventTypes.PART_ACTION_STATE_FIELD,
                            AgentEventTypes.PART_ACTION_STATE_COMPLETED)
                    .doesNotContainKey(AgentEventTypes.PART_ACTION_ERROR_FIELD);
        }

        @Test
        void given_failed_without_result_text_when_terminal_then_no_error_field() {
            mapper.map(new ToolCallStartEvent("r", "tc-e4", "execute"));
            List<AgentEvent> failed = mapper.map(
                    new ToolResultEndEvent("r", "tc-e4", "execute", ToolResultState.INTERRUPTED));

            assertThat(failed.get(0).payload()).doesNotContainKey(AgentEventTypes.PART_ACTION_ERROR_FIELD);
        }

        @Test
        void given_denied_result_with_text_when_terminal_then_error_carried() {
            // 被拒也是失败族：拒绝文案即错误首行
            mapper.map(new ToolCallStartEvent("r", "tc-e5", "execute"));
            mapper.map(new ToolResultTextDeltaEvent("r", "tc-e5", "execute",
                    "Permission denied by rules"));
            List<AgentEvent> failed = mapper.map(
                    new ToolResultEndEvent("r", "tc-e5", "execute", ToolResultState.DENIED));

            assertThat(failed.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_ERROR_FIELD, "Permission denied by rules");
        }

        private int seq;

        /** 断言序列号（结果文本终态取走，多断言不可共用 toolCallId）。 */
        private void assertFailedError(String resultText, String expected) {
            String id = "tc-ex" + seq++;
            mapper.map(new ToolCallStartEvent("r", id, "execute"));
            mapper.map(new ToolResultTextDeltaEvent("r", id, "execute", resultText));
            List<AgentEvent> failed = mapper.map(
                    new ToolResultEndEvent("r", id, "execute", ToolResultState.ERROR));
            assertThat(failed.get(0).payload()).containsEntry(
                    AgentEventTypes.PART_ACTION_ERROR_FIELD, expected);
        }
    }

    /**
     * 机器语法守卫（#234 叙事段堵漏）：模型以原生工具参数语法（DSML 工具调用标记族）
     * 直接发射、引擎未识别为工具调用的文本增量，识别即丢弃——不转译、不挪位到其他
     * 面，只出脱轨信号（#240）；正常人话文本不出部件（ADR-0057）。canonical 形态与
     * 全角竖线连打变体（活体留痕 2026-09-21）同族，均吞。
     */
    @Nested
    class MachineSyntaxGuard {

        private static final String DSML_BLOCK =
                "<｜DSML｜tool_calls><｜DSML｜invoke></｜DSML｜invoke></｜DSML｜tool_calls>";

        /** DSML 块夹在人话中：整段吞，只有一件脱轨信号，任何载荷值不含机器语法原文。 */
        @Test
        void given_dsml_block_between_narration_when_mapped_then_only_signal() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "先跑一遍验证。" + DSML_BLOCK + "全部通过。"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(parts.get(0).payload().values().toString())
                    .doesNotContain("DSML").doesNotContain("docker exec");
        }

        /** 标记头跨增量 split：残头按住期无部件，拼全后整段吞、出一次信号。 */
        @Test
        void given_marker_split_across_deltas_when_mapped_then_head_never_leaks() {
            List<AgentEvent> first = mapper.map(new TextBlockDeltaEvent("r", "b-1", "看日志确认。<｜DSM"));
            List<AgentEvent> second = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "L｜tool_calls></｜DSML｜tool_calls>"));
            List<AgentEvent> third = mapper.map(new TextBlockDeltaEvent("r", "b-1", "收口了。"));

            assertThat(first).isEmpty();
            assertThat(types(second)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(third).isEmpty();
        }

        /** 无闭合标记 = 模型脱轨：吞至 run 尾（堵漏优先），信号只出一次。 */
        @Test
        void given_unclosed_dsml_when_mapped_then_swallowed_to_run_end() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "开始了。<｜DSML｜tool_calls><｜DSML｜invoke>"));
            List<AgentEvent> more = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜parameter>docker exec ws-1 ls"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(more).isEmpty();
        }

        /** 全角竖线连打变体（活体留痕形态）：同族同吞。 */
        @Test
        void given_doubled_bar_variant_when_mapped_then_swallowed() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜｜DSML｜｜tool_calls><｜｜DSML｜｜invoke></｜｜DSML｜｜invoke></｜｜DSML｜｜tool_calls>好了。"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_SIGNAL);
        }

        /** 人话含角括号与竖线（含全角）：非标记族，守卫不误伤（无信号）。 */
        @Test
        void given_plain_narration_with_angle_and_pipes_when_mapped_then_no_signal() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "比较 a < b，再数 1｜2｜3 栏 | 末列 |。"));

            assertThat(parts).isEmpty();
        }

        /** 尾随角括号被活标记头按住：下一增量落定成死文本——两拍都不出部件、不出信号。 */
        @Test
        void given_trailing_angle_when_next_delta_resolves_then_no_parts() {
            List<AgentEvent> first = mapper.map(new TextBlockDeltaEvent("r", "b-1", "箭头写作 <"));
            List<AgentEvent> second = mapper.map(new TextBlockDeltaEvent("r", "b-1", "— 像这样。"));

            assertThat(first).isEmpty();
            assertThat(second).isEmpty();
        }

        /** 长句硬切挨着标记头：残头按住不随硬切漏出，拼全成真标记即吞段——只出信号。 */
        @Test
        void given_hard_cut_next_to_marker_head_when_mapped_then_head_held_back() {
            String headless = "x".repeat(156);
            List<AgentEvent> first = mapper.map(new TextBlockDeltaEvent("r", "b-1", headless + "<｜DS"));
            List<AgentEvent> second = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "ML｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(first).isEmpty();
            assertThat(types(second)).containsExactly(AgentEventTypes.PART_SIGNAL);
        }

        /** 标记起点即段边界：标记体吞掉，随后动作部件照常。 */
        @Test
        void given_dsml_then_action_boundary_when_mapped_then_action_only() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在改配色"));
            List<AgentEvent> atSyntax = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    DSML_BLOCK));
            List<AgentEvent> atAction = mapper.map(new ToolCallStartEvent("r", "tc-g1", "execute"));

            assertThat(types(atSyntax)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(types(atAction)).containsExactly(AgentEventTypes.PART_ACTION);
        }

        /** 吞段中撞动作边界：动作部件照常。 */
        @Test
        void given_action_boundary_mid_swallow_when_mapped_then_action_only() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "<｜DSML｜tool_calls>"));
            List<AgentEvent> atAction = mapper.map(new ToolCallStartEvent("r", "tc-g2", "execute"));

            assertThat(types(atAction)).containsExactly(AgentEventTypes.PART_ACTION);
        }
    }

    /**
     * 脱轨留痕（#240 机器语法吞段的人话信号）：守卫识别到机器语法段（标记起点）
     * 即产 part-signal（signal=derailed，封闭词表）——只报发生事实，不携带任何
     * 原文（原文出口 = 后端 trace 日志）；不产生动作部件（脱轨 = 什么都没跑，
     * 不伪造「在执行」）。呈现形态 = 活性行脱轨变体（#235 变体族，前端静态面
     * 无痕）。频次 = 每次吞段一句（标记起点判定，吞段中的后续增量不再刷）。
     */
    @Nested
    class DerailmentSignals {

        /** 吞段即信号：只有一件 part-signal；信号载荷不含任何机器语法原文。 */
        @Test
        void given_dsml_swallow_when_mapped_then_signal_without_syntax() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "先跑一遍验证。<｜DSML｜tool_calls>\n<｜DSML｜invoke name=\"execute\">\n"
                            + "<｜DSML｜parameter name=\"command\" string=\"true\">docker exec ws-1 pnpm test"
                            + "</｜DSML｜parameter>\n</｜DSML｜invoke>\n</｜DSML｜tool_calls>"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_SIGNAL);
            AgentEvent signal = parts.get(0);
            assertThat(signal.payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ENGINE_FIELD, ENGINE,
                    AgentEventTypes.PART_SIGNAL_SIGNAL_FIELD, AgentEventTypes.PART_SIGNAL_DERAILED));
            // 信号不携带原文：任何载荷值都不含机器语法片段
            assertThat(signal.payload().values().toString())
                    .doesNotContain("DSML").doesNotContain("docker exec");
        }

        /** 无闭合 = 脱轨吞至 run 尾：标记起点出一次信号，吞段中增量不刷。 */
        @Test
        void given_unclosed_derailment_when_swallowed_to_run_end_then_signal_once() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "开始了。<｜DSML｜tool_calls>\n<｜DSML｜parameter name=\"command\">docker exec"));
            List<AgentEvent> more = mapper.map(new TextBlockDeltaEvent("r", "b-1", " exec ws-1 ls"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(more).isEmpty();
        }

        /** 标记头跨增量 split：拼全成真标记才有信号（残头按住期无部件）。 */
        @Test
        void given_marker_split_across_deltas_when_completed_then_signal_fires_once() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "看日志。<｜DSM")))
                    .isEmpty();

            List<AgentEvent> completed = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "L｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(types(completed)).containsExactly(AgentEventTypes.PART_SIGNAL);
        }

        /** 每次吞段一句：同一增量内两段独立标记，各出一次信号（中间人话不出部件）。 */
        @Test
        void given_two_separate_swallows_when_mapped_then_signal_per_swallow() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜tool_calls></｜DSML｜tool_calls>中间没脱轨。"
                            + "<｜DSML｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(types(parts)).containsExactly(
                    AgentEventTypes.PART_SIGNAL, AgentEventTypes.PART_SIGNAL);
        }

        /** 正常人话（含角括号与全角竖线）与死文本残留：不出信号（守卫回归）。 */
        @Test
        void given_plain_narration_when_mapped_then_no_signal() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "比较 a < b，数 1｜2｜3。"));

            assertThat(parts).isEmpty();
        }

        /** #95 委派位：子智能体脱轨信号带来源归属（过程呈现分角色的依据）。 */
        @Test
        void given_subagent_derailment_when_mapped_then_signal_carries_source() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜tool_calls></｜DSML｜tool_calls>").withSource("platform-agent/verify"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(parts.get(0).payload())
                    .containsEntry(AgentEventTypes.SOURCE_FIELD, "verify");
        }

        /** 信封契约：信号载荷扁平（无 data 键），只有关联字段 + 信号值。 */
        @Test
        void given_signal_part_when_built_then_flat_keys_only() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(parts).singleElement().satisfies(part ->
                    assertThat(part.payload()).containsOnlyKeys(
                            AgentEventTypes.RUN_FIELD, AgentEventTypes.SESSION_FIELD,
                            AgentEventTypes.ENGINE_FIELD, AgentEventTypes.PART_SIGNAL_SIGNAL_FIELD));
        }
    }

    // 步骤清单部件（part-plan）的映射用例随 update_plan 工具一并删除（#604），
    // 事件类型与呈现件随 #608 呈现面清场整体退役。

    @Nested
    class StepsAndBoundaries {

        @Test
        void given_model_call_starts_when_mapped_then_no_part_step() {
            // 步骤分组已退役（#115）：ModelCallStartEvent 不再产 part-step——
            // 步骤序号对用户零信息，部件按序竖排
            assertThat(mapper.map(new ModelCallStartEvent("reply-1"))).isEmpty();
            assertThat(mapper.map(new ModelCallStartEvent("reply-2"))).isEmpty();
        }

        @Test
        void given_pending_narration_when_action_arrives_then_action_part_only() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "开始搭数据库"));

            List<AgentEvent> atAction = mapper.map(new ToolCallStartEvent("r", "tc-5", "execute"));
            assertThat(types(atAction)).containsExactly(AgentEventTypes.PART_ACTION);

            // 步骤分组退役：模型调用开始不再产部件
            List<AgentEvent> atStep = mapper.map(new ModelCallStartEvent("reply-2"));
            assertThat(atStep).isEmpty();
        }

        @Test
        void given_thinking_and_unmapped_events_when_mapped_then_no_parts() {
            assertThat(mapper.map(new ThinkingBlockDeltaEvent("r", "b-9", "内部思考"))).isEmpty();
            assertThat(mapper.map(new AgentEndEvent("reply-9"))).isEmpty();
        }
    }

    @Nested
    class WireShape {

        @Test
        void given_any_part_when_built_then_flat_keys_and_no_type_or_data_key() {
            // 信封契约：payload 顶层禁 type 键名；部件载荷扁平（无 data 键——前端
            // 透传收窄以 data 键为准，双发射期部件不被旧前端误收）
            mapper.map(new ToolCallStartEvent("r", "tc-1", "execute"));
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(parts).singleElement().satisfies(part ->
                    assertThat(part.payload()).containsOnlyKeys(
                            AgentEventTypes.RUN_FIELD, AgentEventTypes.SESSION_FIELD,
                            AgentEventTypes.ENGINE_FIELD, AgentEventTypes.PART_SIGNAL_SIGNAL_FIELD));
        }
    }

    @DisplayName("烟囱：一段真实形态的事件序列出部件有序")
    @Test
    void given_real_shaped_sequence_when_mapped_then_ordered_parts() {
        List<AgentEvent> all = List.of();
        all = concat(all, mapper.map(new ModelCallStartEvent("reply-1"))); // 步骤边界：已退役，不出部件
        all = concat(all, mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在准备演示数据。")));
        all = concat(all, mapper.map(new ToolCallStartEvent("r", "tc-1", "write_file")));
        all = concat(all, mapper.map(new ToolCallDeltaEvent("r", "tc-1", "write_file",
                "{\"path\":\"data/seed.sql\"}")));
        all = concat(all, mapper.map(new ToolCallEndEvent("r", "tc-1", "write_file")));
        all = concat(all, mapper.map(new ToolResultEndEvent("r", "tc-1", "write_file",
                ToolResultState.SUCCESS)));
        all = concat(all, mapper.map(new TextBlockDeltaEvent("r", "b-2", "数据库就绪")));

        assertThat(all).extracting(AgentEvent::type).containsExactly(
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION);
    }

    private static List<AgentEvent> concat(List<AgentEvent> base, List<AgentEvent> more) {
        var merged = new java.util.ArrayList<>(base);
        merged.addAll(more);
        return merged;
    }

    @Nested
    class SourceAttribution {

        /** #95 委派位：执行体自身部件不携带 source（缺省 = 执行体）。 */
        @Test
        void given_parent_action_when_mapped_then_no_source_field() {
            List<AgentEvent> parts = mapper.map(new ToolCallStartEvent("r", "tc-0", "execute"));

            assertThat(parts).singleElement().satisfies(part ->
                    assertThat(part.payload()).doesNotContainKey(AgentEventTypes.SOURCE_FIELD));
        }

        /** 子智能体动作卡带来源（动作卡分角色播——验证清单式播报的呈现依据）。 */
        @Test
        void given_subagent_action_when_mapped_then_part_action_carries_source() {
            List<AgentEvent> parts = mapper.map(
                    new ToolCallStartEvent("r", "tc-1", "execute").withSource("verify"));

            assertThat(parts).singleElement().satisfies(part -> {
                assertThat(part.type()).isEqualTo(AgentEventTypes.PART_ACTION);
                assertThat(part.payload()).containsEntry(AgentEventTypes.SOURCE_FIELD, "verify");
            });
        }
    }
}
