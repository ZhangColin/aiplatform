package com.semgravio.aiplatform.business.project.application;

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

    // ---------- 在场窥读口（#424 片轻验常开硬门＋单场轮次预算） ----------

    @Test
    void given_no_registration_when_presence_peek_then_false_and_null_snapshot() {
        // #424 验证在场硬门判据：无登记（verify 未跑/未报）＝零验证——窥读不消费
        // （收口统计仍由 consume 取走，两读不互斥）
        assertThat(facts.hasRegistration("42")).isFalse();
        assertThat(facts.peek("42")).isNull();
    }

    @Test
    void given_registration_when_presence_peek_then_true_rounds_counted_not_consumed() {
        // 修复—复验环逐轮登记：在场＝true、轮次累计（预算判据源）——窥读后收口
        // 消费照常取走全量事实
        facts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", false, "点了无反应")));
        facts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", true, null)));

        assertThat(facts.hasRegistration("42")).isTrue();
        assertThat(facts.peek("42").rounds()).isEqualTo(2);

        VerificationFacts.Facts consumed = facts.consume("42");
        assertThat(consumed.rounds()).isEqualTo(2);
        assertThat(facts.hasRegistration("42")).isFalse();
    }

    // ---------- 「能力已存在」确认读口（#420 空转片豁免判据） ----------

    @Test
    void given_confirmation_in_last_round_when_query_then_names_and_facts_kept() {
        // #420 豁免判据：末轮快照含 existing=true 且 ok=true 的确认项——「能力已存在」
        // 的确认依据（项名连缀）；existing=true 但未过（ok=false）不是确认；读口不
        // 消费（收口统计仍由 consume 取走，两读不互斥）
        facts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", true, null, true),
                new VerificationFacts.Check("首页可访问", "browser", true, null),
                new VerificationFacts.Check("矛盾项", "db", false, "表 0 行", true)));

        assertThat(facts.capabilityExistsConfirmation("42")).isEqualTo("留言提交");

        VerificationFacts.Facts consumed = facts.consume("42");
        assertThat(consumed.lastRound()).hasSize(3);
        assertThat(consumed.lastRound().get(0).existing()).isTrue();
        assertThat(consumed.lastRound().get(1).existing()).isFalse();
    }

    @Test
    void given_no_confirmation_when_query_then_null() {
        // 无登记 / 末轮全无 existing 确认——读口如实 null（不虚构豁免依据）
        assertThat(facts.capabilityExistsConfirmation("42")).isNull();
        facts.record("42", List.of(
                new VerificationFacts.Check("首页可访问", "browser", true, null),
                new VerificationFacts.Check("未过项", "db", false, "表 0 行")));
        assertThat(facts.capabilityExistsConfirmation("42")).isNull();
    }

    @Test
    void given_confirmation_then_reverify_round_when_query_then_confirmation_survives() {
        // #420 留档语义（#405 裁决「确认＝report_verification 正向结果留档」）：确认
        // 跨轮累计（对偶 rounds 口径）——确认轮之后的复验轮（修复—复验环全量上报、
        // 未再带 existing 标记）不覆写掉既有确认，零 diff 合法薄片的豁免依据不丢
        facts.record("42", List.of(
                new VerificationFacts.Check("留言提交", "browser", true, null, true)));
        facts.record("42", List.of(
                new VerificationFacts.Check("首页可访问", "browser", true, null),
                new VerificationFacts.Check("留言数据落库", "db", true, "1 行")));

        assertThat(facts.capabilityExistsConfirmation("42")).isEqualTo("留言提交");
        // 收口统计仍取末轮快照（两读不互斥——确认留档不进统计面）
        VerificationFacts.Facts consumed = facts.consume("42");
        assertThat(consumed.rounds()).isEqualTo(2);
        assertThat(consumed.lastRound()).noneMatch(VerificationFacts.Check::existing);
    }

    @Test
    void given_multiple_registrations_when_peek_then_cumulative_union_by_name() {
        // #427 整轨终验判定面：一场 run 内多次登记（逐旅程分别登记的 off-协议形态）
        // ——累计清单逐项名后写胜出并集：早登记的失败项不被末轮遮蔽（「旅程 A 未过＋
        // 旅程 B 已过」累计仍含 A 的失败），同项重验以最新为准；末轮快照口径不变
        facts.record("42", List.of(
                new VerificationFacts.Check("旅程 1·下单支付", "browser", false, "点击无响应")));
        facts.record("42", List.of(
                new VerificationFacts.Check("旅程 2·留言反馈", "browser", true, null)));

        VerificationFacts.Facts peeked = facts.peek("42");
        assertThat(peeked.cumulative()).extracting(
                check -> check.name() + ":" + check.ok())
                .containsExactly("旅程 1·下单支付:false", "旅程 2·留言反馈:true");
        // 末轮快照口径不变（#424 契约）：只看末轮登记
        assertThat(peeked.lastRound())
                .extracting(VerificationFacts.Check::name)
                .containsExactly("旅程 2·留言反馈");

        // 同项重验最新胜出：旅程 1 修后重登记通过——累计面该项翻绿，其余不动
        facts.record("42", List.of(
                new VerificationFacts.Check("旅程 1·下单支付", "browser", true, null)));
        assertThat(facts.peek("42").cumulative()).extracting(
                check -> check.name() + ":" + check.ok())
                .containsExactly("旅程 1·下单支付:true", "旅程 2·留言反馈:true");
    }
}
