package com.aieducenter.aiplatform.business.project.infrastructure;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationProvider;
import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationRequest;
import com.aieducenter.aiplatform.business.project.domain.port.ImageProviderResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 出图供数方选择器单测（#288「按供应商选适配器」开关）：空＝未配置（active 空、
 * 平台照常起）；已知键＝取对应适配器；未知键＝启动即拒（配置拼写错不静默空转）。
 */
class ImageGenerationProviderSelectorTest {

    /** 键固定的最小替身。 */
    private record KeyedProvider(String key) implements ImageGenerationProvider {

        @Override
        public String providerKey() {
            return key;
        }

        @Override
        public ImageProviderResult generate(ImageGenerationRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private final List<ImageGenerationProvider> adapters = List.of(
            new KeyedProvider("zhipu"), new KeyedProvider("volcano"),
            new KeyedProvider("openai"), new KeyedProvider("dashscope"));

    @Test
    void given_blank_config_when_construct_then_no_active_provider() {
        assertThat(new ImageGenerationProviderSelector(adapters, "").active()).isEmpty();
        assertThat(new ImageGenerationProviderSelector(adapters, null).active()).isEmpty();
        assertThat(new ImageGenerationProviderSelector(adapters, " ").active()).isEmpty();
    }

    @Test
    void given_known_key_when_construct_then_that_adapter_active() {
        ImageGenerationProvider active = new ImageGenerationProviderSelector(
                adapters, "volcano").active().orElseThrow();

        assertThat(active.providerKey()).isEqualTo("volcano");
    }

    @Test
    void given_unknown_key_when_construct_then_startup_rejected() {
        // 配置错误启动即拒：拼写错不该静默空转到运行期
        assertThatThrownBy(() -> new ImageGenerationProviderSelector(adapters, "zhipuu"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhipuu");
    }
}
