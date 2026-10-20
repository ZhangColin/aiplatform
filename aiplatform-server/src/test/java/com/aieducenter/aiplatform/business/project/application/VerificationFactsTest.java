package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 验证事实登记（#352 独立验证子智能体的平台观测面）：verify 子智能体经
 * report_verification 工具登记本轮逐项结果（平台从工具调用事实观测，不解析
 * 自由文本）——后写胜出（末轮快照即收口时刻状态）、轮次累加（修复—复验环的
 * 如实呈现）；生命周期对偶 {@link FinishEditFacts}：run 起跑清残留、收口消费
 * （取走即清）。
 */
class VerificationFactsTest {

    private final VerificationFacts facts = new VerificationFacts();

    @Test
    void given_no_registration_when_consume_then_null() {
        assertThat(facts.consume("42")).isNull();
    }

    @Test
    void given_one_round_when_consume_then_snapshot_and_rounds_one() {
        facts.record("42", List.of(
                new VerificationFacts.Check("注册流程可走通", "browser", true, null),
                new VerificationFacts.Check("留言数据落库", "db", true, "messages 表 3 行"),
                new VerificationFacts.Check("应用日志无报错", "log", true, null)));

        VerificationFacts.Facts consumed = facts.consume("42");

        assertThat(consumed.rounds()).isEqualTo(1);
        assertThat(consumed.lastRound()).hasSize(3);
        assertThat(consumed.lastRound().get(1).face()).isEqualTo("db");
        assertThat(consumed.lastRound().get(1).detail()).isEqualTo("messages 表 3 行");
    }

    @Test
    void given_reverify_rounds_when_consume_then_last_round_wins_and_rounds_accumulate() {
        // 修复—复验环（执行体修后再次委派复验）：首轮有未过项，末轮全过——
        // 收口事实取末轮快照（全过），轮次如实累计（2）
        facts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", false, "点了无反应"),
                new VerificationFacts.Check("留言数据落库", "db", false, "messages 表 0 行")));
        facts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", true, null),
                new VerificationFacts.Check("留言数据落库", "db", true, "messages 表 1 行")));

        VerificationFacts.Facts consumed = facts.consume("42");

        assertThat(consumed.rounds()).isEqualTo(2);
        assertThat(consumed.lastRound()).allMatch(VerificationFacts.Check::ok);
    }

    @Test
    void given_consumed_when_consume_again_then_null() {
        facts.record("42", List.of(new VerificationFacts.Check("首页可访问", "browser", true, null)));

        facts.consume("42");

        assertThat(facts.consume("42")).isNull();
    }

    @Test
    void given_residue_when_clear_then_consume_null() {
        // run 起跑清残留：上一轨的验证事实不进本轨（轨道层职责，本服务只保证可清）
        facts.record("42", List.of(new VerificationFacts.Check("首页可访问", "browser", true, null)));

        facts.clear("42");

        assertThat(facts.consume("42")).isNull();
    }

    @Test
    void given_two_workspaces_when_record_then_isolated() {
        // #351 切片并行：fork 副本与主工作区各跑各的验证——按工作区键隔离不串账
        facts.record("1", List.of(new VerificationFacts.Check("甲项", "browser", true, null)));
        facts.record("2", List.of(new VerificationFacts.Check("乙项", "log", false, "堆栈")));

        assertThat(facts.consume("1").lastRound().get(0).name()).isEqualTo("甲项");
        assertThat(facts.consume("2").lastRound().get(0).name()).isEqualTo("乙项");
    }
}
