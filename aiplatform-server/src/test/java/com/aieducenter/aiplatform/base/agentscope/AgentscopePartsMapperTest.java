package com.aieducenter.aiplatform.base.agentscope;

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

import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEvent;
import com.aieducenter.aiplatform.base.eventhub.domain.model.AgentEventTypes;

/**
 * {@link AgentscopePartsMapper} 单点映射表（#77 parts 契约的唯一生产方）：AgentScope
 * 事件 → 消息部件事件（part-text / part-signal / part-action）。口径：动作卡全生命
 * 周期（开始即出事件——工具调用发起即 started，参数落定 running，结果返回
 * completed/failed，同一 toolCallId 锚定）；解说切段与直播同内核；机器语法吞段
 * 即时产脱轨信号（#240）；思考与读类工具不进部件。步骤分组已退役（#115）：
 * ModelCallStartEvent 不再产 part-step。
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
    class TextParts {

        @Test
        void given_narration_accumulating_when_sentence_settles_then_part_text() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在编写"))).isEmpty();

            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1", "订单管理页面。"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_TEXT);
            assertThat(parts.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ENGINE_FIELD, ENGINE,
                    AgentEventTypes.PART_TEXT_FIELD, "正在编写订单管理页面。"));
        }

        @Test
        void given_tail_without_ender_when_drain_then_single_part_at_run_end() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "马上就好"))).isEmpty();

            List<AgentEvent> tail = mapper.drain();

            assertThat(tail).singleElement().satisfies(part -> {
                assertThat(part.type()).isEqualTo(AgentEventTypes.PART_TEXT);
                assertThat(part.payload()).containsEntry(AgentEventTypes.PART_TEXT_FIELD, "马上就好");
            });
            // 收尾 drain 幂等：不重复出段
            assertThat(mapper.drain()).isEmpty();
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
     * 直接发射、引擎未识别为工具调用的文本增量，经映射不产生携带该文本的解说部件——
     * 识别即丢弃（不转译、不挪位到其他面）；正常人话解说不受守卫误伤。活体留痕形态
     * （2026-09-21 后端 trace）：`<｜｜DSML｜｜tool_calls>…<｜｜DSML｜｜invoke
     * name="execute">…<｜｜DSML｜｜parameter name="command">docker exec …` ——半角
     * 角括号 + 全角竖线连打变体，与 canonical 形态同族，均吞。
     */
    @Nested
    class MachineSyntaxGuard {

        /** DSML 块单增量到达：人话前后段照常出段，标记体无任何部件携带（识别即丢弃）。 */
        @Test
        void given_dsml_block_between_narration_when_mapped_then_no_part_carries_syntax() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "先跑一遍自测。<｜DSML｜tool_calls>\n<｜DSML｜invoke name=\"execute\">\n"
                            + "<｜DSML｜parameter name=\"command\" string=\"true\">docker exec ws-1 pnpm test"
                            + "</｜DSML｜parameter>\n</｜DSML｜invoke>\n</｜DSML｜tool_calls>全部通过。"));

            assertThat(texts(parts)).containsExactly("先跑一遍自测。", "全部通过。");
        }

        /** 标记头跨增量 split：残头从不入段（含 split 当拍的映射结果），拼全后整段吞。 */
        @Test
        void given_marker_split_across_deltas_when_mapped_then_head_never_leaks() {
            List<AgentEvent> first = mapper.map(new TextBlockDeltaEvent("r", "b-1", "看日志确认。<｜DSM"));
            List<AgentEvent> second = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "L｜tool_calls>\n<｜DSML｜invoke name=\"execute\"></｜DSML｜invoke>\n</｜DSML｜tool_calls>"));
            List<AgentEvent> third = mapper.map(new TextBlockDeltaEvent("r", "b-1", "收口了。"));

            assertThat(texts(first)).containsExactly("看日志确认。");
            // 标记拼全即吞段开始：无解说部件携带（标记体仍不漏），只出脱轨信号（#240）
            assertThat(types(second)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(texts(third)).containsExactly("收口了。");
        }

        /** 无闭合标记 = 模型脱轨：吞至 run 尾（堵漏优先），drain 不出携带尾段。 */
        @Test
        void given_unclosed_dsml_when_drained_then_swallowed_to_run_end() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "开始了。<｜DSML｜tool_calls>\n<｜DSML｜invoke name=\"execute\">"));
            List<AgentEvent> more = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜parameter name=\"command\" string=\"true\">docker exec ws-1 ls"));

            assertThat(texts(parts)).containsExactly("开始了。");
            assertThat(more).isEmpty();
            assertThat(mapper.drain()).isEmpty();
        }

        /** 全角竖线连打变体（活体留痕形态）：同族同吞，后续人话照常。 */
        @Test
        void given_doubled_bar_variant_when_mapped_then_swallowed() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"execute\">\n"
                            + "<｜｜DSML｜｜parameter name=\"command\" string=\"true\">docker exec ws-1 ls"
                            + "</｜｜DSML｜｜parameter>\n</｜｜DSML｜｜invoke>\n</｜｜DSML｜｜tool_calls>好了。"));

            assertThat(texts(parts)).containsExactly("好了。");
        }

        /** 人话含角括号与竖线（含全角）：非标记族，守卫不误伤，原样出段。 */
        @Test
        void given_plain_narration_with_angle_and_pipes_when_mapped_then_untouched() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "比较 a < b，再数 1｜2｜3 栏 | 末列 |。"));

            assertThat(texts(parts)).containsExactly("比较 a < b，再数 1｜2｜3 栏 | 末列 |。");
        }

        /** 尾随角括号被活标记头按住：下一增量落定成死文本后整句原样出（不丢字）。 */
        @Test
        void given_trailing_angle_when_next_delta_resolves_then_text_intact() {
            List<AgentEvent> first = mapper.map(new TextBlockDeltaEvent("r", "b-1", "箭头写作 <"));
            List<AgentEvent> second = mapper.map(new TextBlockDeltaEvent("r", "b-1", "— 像这样。"));

            assertThat(first).isEmpty();
            assertThat(texts(second)).containsExactly("箭头写作 <— 像这样。");
        }

        /** 长句硬切挨着标记头：残头按住不随硬切漏出（切出段不含标记片段）。 */
        @Test
        void given_hard_cut_next_to_marker_head_when_mapped_then_head_held_back() {
            String headless = "x".repeat(156);
            List<AgentEvent> first = mapper.map(new TextBlockDeltaEvent("r", "b-1", headless + "<｜DS"));
            List<AgentEvent> second = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "ML｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(texts(first)).containsExactly(headless);
            // 残头按住不随硬切漏出，拼全成真标记即吞段——只出脱轨信号（#240）
            assertThat(types(second)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(mapper.drain()).isEmpty();
        }

        /** 标记起点即段边界：人话余段先出再进动作部件，边界 drain 不带出标记体。 */
        @Test
        void given_dsml_then_action_boundary_when_mapped_then_narration_clean() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "正在改配色"));
            List<AgentEvent> atSyntax = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜tool_calls></｜DSML｜tool_calls>"));
            List<AgentEvent> atAction = mapper.map(new ToolCallStartEvent("r", "tc-g1", "execute"));

            assertThat(texts(atSyntax)).containsExactly("正在改配色");
            assertThat(types(atAction)).containsExactly(AgentEventTypes.PART_ACTION);
            assertThat(mapper.drain()).isEmpty();
        }

        /** 吞段中撞动作边界：drain 空（标记体已被吞），动作部件照常。 */
        @Test
        void given_action_boundary_mid_swallow_when_mapped_then_drain_empty() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "<｜DSML｜tool_calls>"));
            List<AgentEvent> atAction = mapper.map(new ToolCallStartEvent("r", "tc-g2", "execute"));

            assertThat(types(atAction)).containsExactly(AgentEventTypes.PART_ACTION);
            assertThat(mapper.drain()).isEmpty();
        }

        private List<String> texts(List<AgentEvent> parts) {
            // 断言面 = 全部部件的解说文本（无解说部件即空表——DSML 片段出现在任何
            // 部件文本里都会直接挂掉 containsExactly 比对）
            return parts.stream()
                    .map(part -> String.valueOf(
                            part.payload().getOrDefault(AgentEventTypes.PART_TEXT_FIELD, "")))
                    .filter(text -> !text.isEmpty())
                    .toList();
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

        /** 吞段即信号：人话余段先出、随后 part-signal；信号载荷不含任何机器语法原文。 */
        @Test
        void given_dsml_swallow_when_mapped_then_signal_after_human_text_without_syntax() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "先跑一遍自测。<｜DSML｜tool_calls>\n<｜DSML｜invoke name=\"execute\">\n"
                            + "<｜DSML｜parameter name=\"command\" string=\"true\">docker exec ws-1 pnpm test"
                            + "</｜DSML｜parameter>\n</｜DSML｜invoke>\n</｜DSML｜tool_calls>"));

            assertThat(types(parts)).containsExactly(
                    AgentEventTypes.PART_TEXT, AgentEventTypes.PART_SIGNAL);
            AgentEvent signal = parts.get(1);
            assertThat(signal.payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ENGINE_FIELD, ENGINE,
                    AgentEventTypes.PART_SIGNAL_SIGNAL_FIELD, AgentEventTypes.PART_SIGNAL_DERAILED));
            // 信号不携带原文：无解说文本键，任何载荷值都不含机器语法片段
            assertThat(signal.payload()).doesNotContainKey(AgentEventTypes.PART_TEXT_FIELD);
            assertThat(signal.payload().values().toString())
                    .doesNotContain("DSML").doesNotContain("docker exec");
        }

        /** 无闭合 = 脱轨吞至 run 尾：标记起点出一次信号，吞段中增量不刷、drain 无尾段。 */
        @Test
        void given_unclosed_derailment_when_swallowed_to_run_end_then_signal_once() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "开始了。<｜DSML｜tool_calls>\n<｜DSML｜parameter name=\"command\">docker exec"));
            List<AgentEvent> more = mapper.map(new TextBlockDeltaEvent("r", "b-1", " exec ws-1 ls"));

            assertThat(types(parts)).containsExactly(
                    AgentEventTypes.PART_TEXT, AgentEventTypes.PART_SIGNAL);
            assertThat(more).isEmpty();
            assertThat(mapper.drain()).isEmpty();
        }

        /** 标记头跨增量 split：拼全成真标记才有信号（残头按住期不出；死文本照常出段、同样不出信号——#234 口径）。 */
        @Test
        void given_marker_split_across_deltas_when_completed_then_signal_fires_once() {
            assertThat(mapper.map(new TextBlockDeltaEvent("r", "b-1", "看日志。<｜DSM")))
                    .hasSize(1);

            List<AgentEvent> completed = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "L｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(types(completed)).containsExactly(AgentEventTypes.PART_SIGNAL);
        }

        /** 每次吞段一句：同一增量内两段独立标记，各出一次信号，间夹人话照常出段。 */
        @Test
        void given_two_separate_swallows_when_mapped_then_signal_per_swallow() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜tool_calls></｜DSML｜tool_calls>中间没脱轨。"
                            + "<｜DSML｜tool_calls></｜DSML｜tool_calls>"));

            assertThat(types(parts)).containsExactly(
                    AgentEventTypes.PART_SIGNAL, AgentEventTypes.PART_TEXT, AgentEventTypes.PART_SIGNAL);
        }

        /** 正常人话（含角括号与全角竖线）与死文本残留：不出信号（守卫回归）。 */
        @Test
        void given_plain_narration_when_mapped_then_no_signal() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "比较 a < b，数 1｜2｜3。"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_TEXT);
        }

        /** #95 委派位：子智能体脱轨信号带来源归属（过程呈现分角色的依据）。 */
        @Test
        void given_subagent_derailment_when_mapped_then_signal_carries_source() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1",
                    "<｜DSML｜tool_calls></｜DSML｜tool_calls>").withSource("platform-agent/self-test"));

            assertThat(types(parts)).containsExactly(AgentEventTypes.PART_SIGNAL);
            assertThat(parts.get(0).payload())
                    .containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
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

    @Nested
    class PlanParts {

        /** 一次 update_plan 全生命周期：参数落定出 part-plan 全量快照，全程无 part-action。 */
        @Test
        void given_update_plan_call_when_args_settle_then_part_plan_snapshot_and_no_action() {
            assertThat(mapper.map(new ToolCallStartEvent("r", "tc-p1", "update_plan"))).isEmpty();
            // 参数增量分块到达（JSON 裂两半）——快照在参数落定点才成型
            assertThat(mapper.map(new ToolCallDeltaEvent("r", "tc-p1", "update_plan",
                    "{\"steps\":[{\"id\":\"s1\",\"title\":\"读取现有配色\",\"state\":\"completed\"},")))
                    .isEmpty();
            assertThat(mapper.map(new ToolCallDeltaEvent("r", "tc-p1", "update_plan",
                    "{\"id\":\"s2\",\"title\":\"调整主题色\",\"state\":\"in_progress\"},"
                            + "{\"id\":\"s3\",\"title\":\"重启服务验证\",\"state\":\"pending\"}]}")))
                    .isEmpty();

            List<AgentEvent> atSettle = mapper.map(new ToolCallEndEvent("r", "tc-p1", "update_plan"));

            assertThat(types(atSettle)).containsExactly(AgentEventTypes.PART_PLAN);
            assertThat(atSettle.get(0).payload()).containsAllEntriesOf(Map.of(
                    AgentEventTypes.RUN_FIELD, RUN_ID,
                    AgentEventTypes.SESSION_FIELD, SESSION_ID,
                    AgentEventTypes.ENGINE_FIELD, ENGINE));
            assertThat(stepsOf(atSettle.get(0))).isEqualTo(List.of(
                    Map.of("id", "s1", "title", "读取现有配色", "state", "completed"),
                    Map.of("id", "s2", "title", "调整主题色", "state", "in_progress"),
                    Map.of("id", "s3", "title", "重启服务验证", "state", "pending")));
            // 不走动作行：工具结果（ack）不出部件——计划变化不留动作痕
            assertThat(mapper.map(new ToolResultEndEvent("r", "tc-p1", "update_plan",
                    ToolResultState.SUCCESS))).isEmpty();
        }

        /** 执行中再调：事件携带新全量快照（整表替换语义在消费端，平台不合并）。 */
        @Test
        void given_plan_called_again_when_settled_then_second_event_is_full_replacement() {
            callUpdatePlan("tc-p1", "{\"steps\":[{\"id\":\"s1\",\"title\":\"定位文件\","
                    + "\"state\":\"completed\"},{\"id\":\"s2\",\"title\":\"改配色\",\"state\":\"in_progress\"}]}");

            List<AgentEvent> second = callUpdatePlan("tc-p2",
                    "{\"steps\":[{\"id\":\"s1\",\"title\":\"定位文件\",\"state\":\"completed\"},"
                            + "{\"id\":\"s2\",\"title\":\"改配色\",\"state\":\"completed\"},"
                            + "{\"id\":\"s3\",\"title\":\"验证\",\"state\":\"in_progress\"}]}");

            assertThat(types(second)).containsExactly(AgentEventTypes.PART_PLAN);
            assertThat(stepsOf(second.get(0))).hasSize(3);
            assertThat(stepsOf(second.get(0)).get(2))
                    .containsEntry(AgentEventTypes.PART_PLAN_STEP_STATE_FIELD,
                            AgentEventTypes.PART_PLAN_STATE_IN_PROGRESS);
        }

        /** 计划更新是工具边界：先出解说余段再出快照（段与段有序不串，同动作边界语义）。 */
        @Test
        void given_pending_narration_when_plan_settles_then_drained_before_plan() {
            // 无句读结尾：余段挂在工具边界出（同动作边界先例——start 边界先 drain）
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "开始改配色"));

            List<AgentEvent> atBoundary = new ArrayList<>(mapper.map(
                    new ToolCallStartEvent("r", "tc-p1", "update_plan")));
            mapper.map(new ToolCallDeltaEvent("r", "tc-p1", "update_plan",
                    "{\"steps\":[{\"id\":\"s1\",\"title\":\"改配色\",\"state\":\"in_progress\"}]}"));
            atBoundary.addAll(mapper.map(new ToolCallEndEvent("r", "tc-p1", "update_plan")));

            assertThat(types(atBoundary)).containsExactly(
                    AgentEventTypes.PART_TEXT, AgentEventTypes.PART_PLAN);
        }

        /** 解析不出快照（非 JSON / steps 缺失或非数组 / 空表 / 参数未流出）→ 不发事件（不产就不显示）。 */
        @Test
        void given_missing_empty_or_unparsable_steps_when_settled_then_no_part_plan() {
            assertThat(callUpdatePlan("tc-e1", "not json")).isEmpty();
            assertThat(callUpdatePlan("tc-e2", "{\"steps\":\"nope\"}")).isEmpty();
            assertThat(callUpdatePlan("tc-e3", "{\"steps\":[]}")).isEmpty();
            assertThat(callUpdatePlan("tc-e4", "{\"other\":1}")).isEmpty();
            // 无参数增量（参数未以增量流出）同样不出快照——快照只从参数增量成型
            assertThat(mapper.map(new ToolCallEndEvent("r", "tc-e5", "update_plan"))).isEmpty();
        }

        /** 畸形条目防御归一：缺 id/标题或空白标题丢弃、未知状态回落 pending、重号首见胜出。 */
        @Test
        void given_malformed_step_entries_when_settled_then_normalized_snapshot() {
            List<AgentEvent> parts = callUpdatePlan("tc-n1", "{\"steps\":["
                    + "{\"id\":\"s1\",\"title\":\"读文件\"},"                       // 缺 state → pending
                    + "{\"id\":\"s2\",\"title\":\"改色\",\"state\":\"done\"},"      // 未知 state → pending
                    + "{\"id\":\"s3\",\"state\":\"in_progress\"},"                  // 缺 title → 丢弃
                    + "{\"title\":\"无 id\",\"state\":\"pending\"},"                // 缺 id → 丢弃
                    + "{\"id\":\"s1\",\"title\":\"重号\",\"state\":\"completed\"}," // 重号 → 首见胜出
                    + "{\"id\":\"s4\",\"title\":\"   \"}]}");                        // 空白 title → 丢弃

            assertThat(stepsOf(parts.get(0))).isEqualTo(List.of(
                    Map.of("id", "s1", "title", "读文件",
                            "state", AgentEventTypes.PART_PLAN_STATE_PENDING),
                    Map.of("id", "s2", "title", "改色",
                            "state", AgentEventTypes.PART_PLAN_STATE_PENDING)));
        }

        /** 完整调用剧本（start → delta → end → result）。 */
        private List<AgentEvent> callUpdatePlan(String toolCallId, String args) {
            mapper.map(new ToolCallStartEvent("r", toolCallId, "update_plan"));
            mapper.map(new ToolCallDeltaEvent("r", toolCallId, "update_plan", args));
            return mapper.map(new ToolCallEndEvent("r", toolCallId, "update_plan"));
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> stepsOf(AgentEvent part) {
            return (List<Map<String, Object>>) part.payload()
                    .get(AgentEventTypes.PART_PLAN_STEPS_FIELD);
        }
    }

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
        void given_pending_narration_when_action_arrives_then_drained_before_action_part() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "开始搭数据库"));

            List<AgentEvent> atAction = mapper.map(new ToolCallStartEvent("r", "tc-5", "execute"));
            assertThat(types(atAction)).containsExactly(
                    AgentEventTypes.PART_TEXT, AgentEventTypes.PART_ACTION);

            // 步骤分组退役：模型调用开始不再产部件（也不触发叙事切段）
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
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1", "一段。"));

            assertThat(parts).singleElement().satisfies(part ->
                    assertThat(part.payload()).containsOnlyKeys(
                            AgentEventTypes.RUN_FIELD, AgentEventTypes.SESSION_FIELD,
                            AgentEventTypes.ENGINE_FIELD, AgentEventTypes.PART_TEXT_FIELD));
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
        all = concat(all, mapper.drain());

        assertThat(all).extracting(AgentEvent::type).containsExactly(
                AgentEventTypes.PART_TEXT,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_ACTION,
                AgentEventTypes.PART_TEXT);
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
        void given_parent_narration_when_mapped_then_no_source_field() {
            List<AgentEvent> parts = mapper.map(new TextBlockDeltaEvent("r", "b-1", "执行体自述。"));

            assertThat(parts).singleElement().satisfies(part ->
                    assertThat(part.payload()).doesNotContainKey(AgentEventTypes.SOURCE_FIELD));
        }

        /** #95：子智能体解说段带来源归属（过程呈现分角色播的依据）。 */
        @Test
        void given_subagent_narration_when_mapped_then_part_text_carries_source() {
            List<AgentEvent> parts = mapper.map(
                    new TextBlockDeltaEvent("r", "b-1", "自测逐项通过。")
                            .withSource("platform-agent/self-test"));

            assertThat(parts).singleElement().satisfies(part -> {
                assertThat(part.type()).isEqualTo(AgentEventTypes.PART_TEXT);
                assertThat(part.payload()).containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
            });
        }

        /** 子智能体动作卡带来源（动作卡分角色播——自测清单式播报的呈现依据）。 */
        @Test
        void given_subagent_action_when_mapped_then_part_action_carries_source() {
            List<AgentEvent> parts = mapper.map(
                    new ToolCallStartEvent("r", "tc-1", "execute").withSource("self-test"));

            assertThat(parts).singleElement().satisfies(part -> {
                assertThat(part.type()).isEqualTo(AgentEventTypes.PART_ACTION);
                assertThat(part.payload()).containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
            });
        }

        /** 来源切换即段边界：执行体余段先出（执行体来源），子智能体段随后（子智能体来源）。 */
        @Test
        void given_source_switch_mid_narration_when_mapped_then_segments_split_by_source() {
            mapper.map(new TextBlockDeltaEvent("r", "b-1", "执行体说半句"));

            List<AgentEvent> parts = mapper.map(
                    new TextBlockDeltaEvent("r", "b-2", "子智能体接上。").withSource("self-test"));

            assertThat(parts).hasSize(2);
            assertThat(parts.get(0).payload())
                    .containsEntry(AgentEventTypes.PART_TEXT_FIELD, "执行体说半句")
                    .doesNotContainKey(AgentEventTypes.SOURCE_FIELD);
            assertThat(parts.get(1).payload())
                    .containsEntry(AgentEventTypes.PART_TEXT_FIELD, "子智能体接上。")
                    .containsEntry(AgentEventTypes.SOURCE_FIELD, "self-test");
        }
    }
}
