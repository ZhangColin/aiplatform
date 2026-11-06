package com.semgravio.aiplatform.business.project.infrastructure.agentscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.semgravio.aiplatform.business.project.application.VerificationFacts;

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
    private final ReportVerificationTool tool =
            new ReportVerificationTool("42", facts, () -> 3);

    private ToolResultState call(Map<String, Object> input) {
        return call(tool, input).getState();
    }

    private io.agentscope.core.message.ToolResultBlock call(ReportVerificationTool target,
            Map<String, Object> input) {
        return Mono.from(target.callAsync(ToolCallParam.builder()
                        .toolUseBlock(new ToolUseBlock("tc-1", ReportVerificationTool.NAME,
                                input, null))
                        .input(input)
                        .build()))
                .block();
    }

    /** 回执全文（output 文本块拼接——预算指引等回执文案断言面）。 */
    private static String textOf(io.agentscope.core.message.ToolResultBlock block) {
        StringBuilder text = new StringBuilder();
        block.getOutput().forEach(part -> {
            if (part instanceof io.agentscope.core.message.TextBlock textBlock) {
                text.append(textBlock.getText());
            }
        });
        return text.toString();
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

    // ---------- 「能力已存在」确认形态（#420 空转片豁免路径） ----------

    @Test
    void given_confirmation_when_call_then_recorded_existing_and_queryable() {
        // #420：verify 确认能力已存在（活已被前片干完）——existing=true（须 ok=true）
        // 登记为平台事实，收口闸的豁免读口可查；未带 existing 的项照旧 false
        ToolResultState state = call(Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", true, "existing", true),
                Map.of("name", "首页可访问", "face", "browser", "ok", true))));

        assertThat(state).isNotEqualTo(ToolResultState.ERROR);
        assertThat(facts.capabilityExistsConfirmation("42")).isEqualTo("留言提交");
        VerificationFacts.Facts consumed = facts.consume("42");
        assertThat(consumed.lastRound()).containsExactly(
                new VerificationFacts.Check("留言提交", "browser", true, null, true),
                new VerificationFacts.Check("首页可访问", "browser", true, null));
    }

    @Test
    void given_existing_true_but_failed_when_call_then_error_and_nothing_recorded() {
        // 确认即通过：existing=true 须 ok=true——「已存在但未过」是矛盾态，报错引导
        // 改正、整轮不登记（不静默把矛盾态入账）
        assertThat(call(Map.of("checks", List.of(
                Map.of("name", "项", "face", "browser", "ok", false, "existing", true)))))
                .isEqualTo(ToolResultState.ERROR);
        assertThat(facts.consume("42")).isNull();
    }

    // ---------- 单场轮次预算（#424 验证 fail 修复复验回路防无限复验） ----------

    @Test
    void given_rounds_reaching_budget_with_fails_when_call_then_stop_instruction_and_facts_kept() {
        // 预算 2、两轮仍有未过项：第 2 轮登记回执带「预算已尽停止复验」指引（未过项
        // 如实带入收口，不粉饰）；事实照常登记（平台观测面不因预算截断——轮次如实）
        VerificationFacts budgeted = new VerificationFacts();
        ReportVerificationTool capped = new ReportVerificationTool("42", budgeted, () -> 2);
        call(capped, Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", false, "detail", "点了无反应"))));
        assertThat(textOf(call(capped, Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", false, "detail", "点了无反应"))))))
                .contains("预算")
                .contains("停止");
        assertThat(budgeted.consume("42").rounds()).isEqualTo(2);
    }

    @Test
    void given_rounds_reaching_budget_all_passing_when_call_then_no_stop_instruction() {
        // 全过即验完：即使轮数达预算也不带停止指引（循环自然终点，不打扰回交）
        VerificationFacts budgeted = new VerificationFacts();
        ReportVerificationTool capped = new ReportVerificationTool("42", budgeted, () -> 1);
        assertThat(textOf(call(capped, Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", true))))))
                .doesNotContain("停止");
    }

    @Test
    void given_budget_lowered_midway_when_call_then_new_budget_effective() {
        // 运营面改预算下一轮登记即生效（动态读不缓存——进行中 run 不定格）
        AtomicInteger budget = new AtomicInteger(3);
        VerificationFacts budgeted = new VerificationFacts();
        ReportVerificationTool dynamic = new ReportVerificationTool("42", budgeted, budget::get);
        call(dynamic, Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", false, "detail", "点了无反应"))));
        assertThat(textOf(call(dynamic, Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", false, "detail", "点了无反应"))))))
                .doesNotContain("停止"); // 预算 3：第 2 轮未达
        budget.set(2);
        assertThat(textOf(call(dynamic, Map.of("checks", List.of(
                Map.of("name", "留言提交", "face", "browser", "ok", false, "detail", "点了无反应"))))))
                .contains("停止"); // 预算改 2：已达（第 3 轮 ≥ 2）
        assertThat(budgeted.consume("42").rounds()).isEqualTo(3);
    }
}
