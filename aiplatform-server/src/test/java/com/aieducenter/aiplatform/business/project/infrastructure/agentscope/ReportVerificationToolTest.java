package com.aieducenter.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.business.project.application.VerificationFacts;

import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

/**
 * 验证结果登记工具（#352）：verify 子智能体把本轮三面验证逐项结果登记为平台
 * 事实（收尾卡验证统计的事实源——平台从工具调用事实观测，不解析自由文本）。
 * 校验面：checks 必传非空、每项 name/face/ok 必传、face 封闭集（browser/db/log
 * ——三面是票的硬约定，未知面报错引导模型改正）；detail 可缺省（通过项不附证据）。
 */
class ReportVerificationToolTest {

    private final VerificationFacts facts = new VerificationFacts();
    private final ReportVerificationTool tool = new ReportVerificationTool("42", facts);

    private ToolResultState call(Map<String, Object> input) {
        return Mono.from(tool.callAsync(ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("tc-1", ReportVerificationTool.NAME,
                                input, null))
                        .input(input)
                        .build()))
                .block().getState();
    }

    @Test
    void given_full_checks_when_call_then_recorded_with_three_faces() {
        ToolResultState state = call(Map.of("checks", List.of(
                Map.of("name", "注册流程可走通", "face", "browser", "ok", true),
                Map.of("name", "留言提交", "face", "browser", "ok", false, "detail", "点了无反应"),
                Map.of("name", "留言数据落库", "face", "db", "ok", false, "detail", "messages 表 0 行"),
                Map.of("name", "应用日志无报错", "face", "log", "ok", true))));

        assertThat(state).isNotEqualTo(ToolResultState.ERROR);
        VerificationFacts.Facts consumed = facts.consume("42");
        assertThat(consumed.rounds()).isEqualTo(1);
        assertThat(consumed.lastRound()).containsExactly(
                new VerificationFacts.Check("注册流程可走通", "browser", true, null),
                new VerificationFacts.Check("留言提交", "browser", false, "点了无反应"),
                new VerificationFacts.Check("留言数据落库", "db", false, "messages 表 0 行"),
                new VerificationFacts.Check("应用日志无报错", "log", true, null));
    }

    @Test
    void given_reverify_when_call_then_last_round_wins_and_rounds_accumulate() {
        call(Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", false, "detail", "点了无反应"))));
        call(Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", true))));

        VerificationFacts.Facts consumed = facts.consume("42");
        assertThat(consumed.rounds()).isEqualTo(2);
        assertThat(consumed.lastRound()).containsExactly(
                new VerificationFacts.Check("留言提交", "browser", true, null));
    }

    @Test
    void given_missing_or_empty_checks_when_call_then_error_and_nothing_recorded() {
        assertThat(call(Map.of())).isEqualTo(ToolResultState.ERROR);
        assertThat(call(Map.of("checks", List.of()))).isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isNull();
    }

    @Test
    void given_unknown_face_when_call_then_error_and_nothing_recorded() {
        // face 是封闭集（browser/db/log 三面）——未知面报错引导改正，不静默入账
        assertThat(call(Map.of("checks", List.of(
                Map.of("name", "项", "face", "api", "ok", true)))))
                .isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isNull();
    }

    @Test
    void given_item_missing_required_field_when_call_then_error_and_nothing_recorded() {
        assertThat(call(Map.of("checks", List.of(
                Map.of("face", "browser", "ok", true))))) // 缺 name
                .isEqualTo(ToolResultState.ERROR);
        assertThat(call(Map.of("checks", List.of(
                Map.of("name", "项", "face", "browser"))))) // 缺 ok
                .isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isNull();
    }

    @Test
    void given_one_bad_item_among_good_when_call_then_error_and_nothing_recorded() {
        // 整轮原子：任一项坏即整轮不登记（部分登记会让收尾统计失真）
        assertThat(call(Map.of("checks", List.of(
                Map.of("name", "首页可访问", "face", "browser", "ok", true),
                Map.of("name", "项", "face", "bad", "ok", true)))))
                .isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isNull();
    }
}
