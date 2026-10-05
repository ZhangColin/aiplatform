package com.aieducenter.aiplatform.business.project.domain.port;

import java.util.Optional;

import com.cartisan.core.stereotype.Port;
import com.cartisan.core.stereotype.PortType;

/**
 * active 出图供数口（#288）：按平台配置选出当前供数方（{@code app.image-generation.
 * provider} 键，词表＝适配器 {@link ImageGenerationProvider#providerKey}——博查先例
 * 「按供应商选适配器」开关的端口面）。空＝未配置（调用方如实报 PRJ_045，平台
 * 照常起）；实现在 infrastructure 侧按配置装配四家适配器。
 */
@Port(PortType.CLIENT)
public interface ActiveImageGenerationProvider {

    /** active 适配器（未配置＝空）。 */
    Optional<ImageGenerationProvider> active();
}
