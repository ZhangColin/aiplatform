package com.aieducenter.aiplatform.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟源配置：提供可注入的 {@link Clock} 单点——时间语义（token 过期、心跳等）
 * 依赖时钟注入可测（测试以 {@code @MockitoBean Clock} 替换、快进断言，不真等）。
 * 生产恒 {@link Clock#systemUTC()}；其余组件（identity/SseChannelHub 等）仍就地构造
 * 系统时钟，不受本 bean 影响。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
