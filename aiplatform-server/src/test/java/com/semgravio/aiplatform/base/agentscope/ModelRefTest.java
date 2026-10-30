package com.semgravio.aiplatform.base.agentscope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link ModelRef} 模型串解析（形状校验单点）：ADR-0031 起前缀即协议路由——
 * 供应商通道键开放词表（volcengine/deepseek/未来新通道），前缀合法性由档位解析
 * 与 {@code ModelRegistry} 装配保障，本类只守 provider:model 形状。
 */
class ModelRefTest {

    @Test
    void given_deepseek_model_string_when_parse_then_provider_and_model_id_split() {
        ModelRef ref = ModelRef.parse("deepseek:deepseek-v4-flash");

        assertThat(ref.provider()).isEqualTo("deepseek");
        assertThat(ref.modelId()).isEqualTo("deepseek-v4-flash");
    }

    @Test
    void given_channel_prefixed_model_string_when_parse_then_open_vocabulary() {
        // 前缀即协议路由（开放词表）：volcengine 平台工厂、任意通道键皆可解析——
        // 白名单退役（ADR-0031），非法前缀由装配层报错（ModelRegistry 无法路由）
        ModelRef ref = ModelRef.parse("volcengine:deepseek-v4-flash-ga-260731");

        assertThat(ref.provider()).isEqualTo("volcengine");
        assertThat(ref.modelId()).isEqualTo("deepseek-v4-flash-ga-260731");
    }

    @Test
    void given_blank_model_string_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_string_without_colon_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek-v4-flash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_blank_model_id_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_string_with_multiple_colons_when_parse_then_rejected() {
        assertThatThrownBy(() -> ModelRef.parse("deepseek:deepseek-chat:extra"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void given_model_ref_when_to_model_string_then_original_recomposed() {
        String original = "volcengine:deepseek-v4-flash-ga-260731";

        assertThat(ModelRef.parse(original).toModelString()).isEqualTo(original);
    }
}
