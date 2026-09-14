package com.aieducenter.aiplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台单体入口。@EnableScheduling（#171 休眠器，全库首个 @Scheduled——定时扫描
 * 闲置沙箱休眠/清扫闲置快照，见 WorkspaceHibernationScheduler）。
 */
@SpringBootApplication
@EnableScheduling
public class AiPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiPlatformApplication.class, args);
    }
}
