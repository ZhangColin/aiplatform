package com.aieducenter.aiplatform.base.eventhub.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 智能体事件通道配置（前缀 {@code app.agent-events}）：近期事件重放缓冲容量。
 * 容量上界即通道内存上界——长跑 run 不失控；缓冲为单实例内存态（重启即失，
 * 多实例化时需重估）。
 */
@Component
@ConfigurationProperties(prefix = "app.agent-events")
public class AgentEventProperties {

    /** 近期事件重放缓冲容量（每通道有界环形；新连接补发的最近事件数上限）。 */
    private int replayDepth = 1000;

    public int getReplayDepth() {
        return replayDepth;
    }

    public void setReplayDepth(int replayDepth) {
        this.replayDepth = replayDepth;
    }
}
