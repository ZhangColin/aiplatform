package com.aieducenter.aiplatform.base.skills.application;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillPrecheckResponse.PrecheckHint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预检判定输出解析（#255 容错口径，classify parse 同款单测面）：裸 JSON 逐条
 * 映射提示四件；模型违规包裹（解释/代码块围栏）按首尾花括号截取仍可解；结构
 * 不合（无 overlaps、非对象条目、skills 非文本）与垃圾文本一律 null——调用方
 * 降级「未执行」（宁降级不编造）。
 */
class BackofficeSkillPrecheckAppServiceTest {

    @Test
    void given_bare_json_when_parse_then_hints_mapped_field_by_field() {
        assertThat(BackofficeSkillPrecheckAppService.parse(
                "{\"overlaps\":[{\"skills\":[\"a\",\"b\"],\"counterpart\":\"工作协议\","
                        + "\"overlap\":\"重叠在哪\",\"resolution\":\"消解方向\"}]}"))
                .containsExactly(new PrecheckHint(List.of("a", "b"), "工作协议",
                        "重叠在哪", "消解方向"));
        // counterpart/resolution 缺席容错读（null 不炸——模型少给字段仍有可用提示）
        assertThat(BackofficeSkillPrecheckAppService.parse(
                "{\"overlaps\":[{\"skills\":[\"a\"],\"overlap\":\"重叠在哪\"}]}"))
                .containsExactly(new PrecheckHint(List.of("a"), null, "重叠在哪", null));
    }

    @Test
    void given_wrapped_json_when_parse_then_extracted_by_braces() {
        assertThat(BackofficeSkillPrecheckAppService.parse(
                "好的，结果如下：\n```json\n{\"overlaps\":[]}\n```"))
                .isEmpty();
    }

    @Test
    void given_structurally_invalid_outputs_when_parse_then_null_for_degradation() {
        assertThat(BackofficeSkillPrecheckAppService.parse("{\"result\":\"无\"}")).isNull();
        assertThat(BackofficeSkillPrecheckAppService.parse("{\"overlaps\":[\"不是对象\"]}")).isNull();
        assertThat(BackofficeSkillPrecheckAppService.parse(
                "{\"overlaps\":[{\"skills\":\"不是数组\",\"overlap\":\"…\"}]}")).isNull();
        assertThat(BackofficeSkillPrecheckAppService.parse("完全没有结构")).isNull();
        assertThat(BackofficeSkillPrecheckAppService.parse("")).isNull();
        assertThat(BackofficeSkillPrecheckAppService.parse(null)).isNull();
    }
}
