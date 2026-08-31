package com.aieducenter.aiplatform.web;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误码前缀注册表不变量（ADR-0001：一 BC 一前缀，新 BC 建立即注册）。
 */
class ErrorCodePrefixTest {

    @Test
    void given_registry_when_collect_prefixes_then_all_unique() {
        List<String> prefixes = Arrays.stream(ErrorCodePrefix.values())
                .map(ErrorCodePrefix::prefix)
                .toList();

        assertThat(prefixes).doesNotHaveDuplicates();
    }

    @Test
    void given_registry_when_collect_bounded_contexts_then_all_unique() {
        List<String> contexts = Arrays.stream(ErrorCodePrefix.values())
                .map(ErrorCodePrefix::boundedContext)
                .toList();

        assertThat(contexts).doesNotHaveDuplicates();
    }

    @Test
    void given_adr_registry_when_compare_then_six_prefixes_registered() {
        // ADR-0001 前缀注册表：WSP_/KNW_/PRJ_/ORD_ + METER_/IDN_（AGT_/CHAT_ 随智能体层
        // 拆除注销——agentscope 薄包非 BC、无 REST 错误面；ORD_ 自 #18 建 BC、
        // #28 用户面 REST 错误面走线起登记）
        List<String> prefixes = Arrays.stream(ErrorCodePrefix.values())
                .map(ErrorCodePrefix::prefix)
                .toList();

        assertThat(prefixes).containsExactlyInAnyOrder(
                "WSP_", "KNW_", "PRJ_", "ORD_", "METER_", "IDN_");
    }
}
