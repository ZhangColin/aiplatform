package com.aieducenter.aiplatform.base.agentscope;

import java.util.List;
import java.util.Map;

/**
 * 挂起事实（converse / resume 的软终点面，#83 作答通道分家）：流以
 * RequireUserConfirmEvent 终止、run 未终态——engineRef 为续跑批复锚；
 * {@code question} 区分两族挂起的作答通道——ask_user 提问挂起走问答作答通道
 * （意见环），权限确认挂起走权限作答通道（run 执行环），互不串扰；
 * {@code toolCalls} 为待确认工具最小面（{id, name, input}，同挂起事件
 * data.toolCalls——重建 ConfirmResult 的入参形状）。
 */
public record AgentSuspension(String engineRef, boolean question,
        List<Map<String, Object>> toolCalls) {

    /** 权限确认挂起（非提问面）：run 执行环的确认卡驱动事实。 */
    public boolean permission() {
        return !question;
    }
}
