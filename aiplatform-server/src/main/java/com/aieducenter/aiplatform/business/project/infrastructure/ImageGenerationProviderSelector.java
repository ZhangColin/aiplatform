package com.aieducenter.aiplatform.business.project.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.business.project.domain.port.ActiveImageGenerationProvider;
import com.aieducenter.aiplatform.business.project.domain.port.ImageGenerationProvider;

/**
 * 出图供数方选择器（#288，博查先例「按供应商选适配器」开关的落地——四家全接即
 * 开关成立日）：按 {@code app.image-generation.provider} 配置键选 active 适配器
 * （zhipu／volcano／openai／dashscope 四键）。空＝未配置（{@link #active} 空——
 * 出图如实 PRJ_045，平台照常起）；配了但无对应适配器＝配置错误，启动即拒（拼写
 * 错不该静默空转到运行期）。
 */
@Adapter(PortType.CLIENT)
public class ImageGenerationProviderSelector implements ActiveImageGenerationProvider {

    private final Map<String, ImageGenerationProvider> byKey;
    private final String activeKey;

    public ImageGenerationProviderSelector(
            List<ImageGenerationProvider> adapters,
            @Value("${app.image-generation.provider:}") String activeKey) {
        this.byKey = adapters.stream().collect(Collectors.toUnmodifiableMap(
                ImageGenerationProvider::providerKey, Function.identity()));
        if (activeKey != null && !activeKey.isBlank() && !byKey.containsKey(activeKey.strip())) {
            throw new IllegalStateException("app.image-generation.provider 配置了未知供应商 "
                    + activeKey + "（可选: " + byKey.keySet() + "）");
        }
        this.activeKey = activeKey == null ? "" : activeKey.strip();
    }

    @Override
    public Optional<ImageGenerationProvider> active() {
        return activeKey.isBlank() ? Optional.empty() : Optional.of(byKey.get(activeKey));
    }
}
